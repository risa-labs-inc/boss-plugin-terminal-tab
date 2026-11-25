package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.SupabaseDataProvider
import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import ai.rever.bossterm.compose.share.HostAccountSessions
import ai.rever.bossterm.compose.share.HostTerminalPreferences
import ai.rever.bossterm.compose.share.HostTerminalRelay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** The host owns login and token refresh; only identity and authenticated RPC results reach BossTerm. */
internal class HostAccountSessionBridge(
    private val auth: AuthDataProvider?,
    private val database: SupabaseDataProvider?,
    private val onFailure: (Throwable) -> Unit = {},
    private val onCleanupExhausted: () -> Unit = {},
    private val cleanupRetryDelaysMillis: List<Long> = listOf(250, 1_000, 4_000),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onIdentityChanging: suspend () -> Unit,
) : HostAccountSessions, HostTerminalPreferences, HostTerminalRelay {
    private val mutableState = MutableStateFlow<AccountState>(AccountState.SignedOut)
    override val state = mutableState.asStateFlow()
    @Volatile private var job: Job? = null
    private var cleanupPending = false
    private var disabled = false

    fun start(scope: CoroutineScope) {
        if (job != null) return
        // Publish the current identity before any terminal UI or account service is created.
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            auth?.currentUser?.collect { user ->
                if (disabled) return@collect
                val next = user?.takeIf { database != null }?.let { AccountState.SignedIn(it.email, it.id) }
                    ?: AccountState.SignedOut
                val previous = mutableState.value as? AccountState.SignedIn
                if (cleanupPending || previous?.userId != (next as? AccountState.SignedIn)?.userId) {
                    mutableState.value = AccountState.SignedOut
                    cleanupPending = cleanupPending || previous != null
                    var retry = 0
                    while (cleanupPending) {
                        try {
                            onIdentityChanging()
                            cleanupPending = false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            onFailure(t)
                            // A transient failure must not depend on another StateFlow emission.
                            // Exhausted retries disable this bridge until the plugin is reloaded.
                            if (retry >= cleanupRetryDelaysMillis.size) {
                                disabled = true
                                try { onCleanupExhausted() } catch (e: CancellationException) { throw e }
                                catch (t: Throwable) { onFailure(t) }
                                return@collect
                            }
                            delay(cleanupRetryDelaysMillis[retry++])
                        }
                    }
                }
                // A newer login may have arrived while cleanup was suspended.
                if (auth.currentUser.value?.id == user?.id) mutableState.value = next
            }
        }
    }

    fun close() {
        job?.cancel()
        job = null
        mutableState.value = AccountState.SignedOut
    }

    private fun owns(userId: String): Boolean =
        (state.value as? AccountState.SignedIn)?.userId == userId && auth?.currentUser?.value?.id == userId

    /** The expected-owner guard applies to every RPC before dispatch and before returning data. */
    private suspend fun ownedRpc(
        userId: String,
        function: String,
        parameters: JsonObject = buildJsonObject {},
    ): String {
        check(owns(userId)) { "Terminal account changed" }
        val ownedParameters = buildJsonObject {
            parameters.forEach { (key, value) -> put(key, value) }
            // Set last so caller parameters cannot override the verified owner.
            put("p_expected_user_id", userId)
        }.toString()
        val result = checkNotNull(database) { "Terminal account unavailable" }
            .rpc(function, ownedParameters).getOrThrow()
        check(owns(userId)) { "Terminal account changed" }
        return result
    }

    private suspend fun rpc(userId: String, function: String, key: String, value: JsonElement): String =
        ownedRpc(userId, function, buildJsonObject { put(key, value) })

    override suspend fun relayTicket(userId: String, roomId: String, role: String): String {
        require(role == "host" || role == "account") { "Unsupported terminal relay role" }
        val room = try { UUID.fromString(roomId).toString() }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid terminal relay room") }
        require(room.equals(roomId, ignoreCase = true)) { "Invalid terminal relay room" }
        return ownedRpc(userId, "mint_terminal_relay_ticket", buildJsonObject {
            put("p_room_id", room)
            put("p_role", role)
        })
    }

    // BossTerm polls the session directory every 15 s and the viewing preferences every 60 s on every pod, signed in
    // or watched or not: about 300 Supabase calls an hour each. Answers are reused briefly per user ([LIST_TTL_MS],
    // [PREFERENCES_TTL_MS]); this host's own publish/remove drops the directory answer at once, and preferences are
    // read fresh for [EDITING_WINDOW_MS] after a settings handoff, while the owner may be editing them in the browser.
    // Failures are never reused. The owner check still runs before any answer is returned.
    private class Memo(val userId: String, val at: Long, val body: String)
    @Volatile private var listMemo: Memo? = null
    @Volatile private var preferencesMemo: Memo? = null
    @Volatile private var editingUntil = 0L

    private suspend fun remembered(
        userId: String, ttlMs: Long, slot: () -> Memo?, store: (Memo?) -> Unit, fetch: suspend () -> String,
    ): String {
        val now = clock()
        slot()?.takeIf { it.userId == userId && now - it.at in 0 until ttlMs }?.let {
            check(owns(userId)) { "Terminal account changed" }
            return it.body
        }
        return fetch().also { store(Memo(userId, now, it)) }
    }

    override suspend fun preferences(userId: String): String {
        if (clock() < editingUntil) return ownedRpc(userId, "get_user_terminal_preferences").also { preferencesMemo = null }
        return remembered(userId, PREFERENCES_TTL_MS, { preferencesMemo }, { preferencesMemo = it }) {
            ownedRpc(userId, "get_user_terminal_preferences")
        }
    }

    override suspend fun settingsHandoff(userId: String): String {
        editingUntil = clock() + EDITING_WINDOW_MS
        preferencesMemo = null
        return ownedRpc(userId, "mint_user_settings_handoff")
    }

    // Never log RPC payloads/results: share URLs contain bearer credentials and E2E keys;
    // settings handoffs and relay tickets are also bearer secrets. Log only operation/type.
    private suspend fun mutation(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false // The publisher retries at its next heartbeat.
    }

    override suspend fun upsert(userId: String, rowJson: String): Boolean = mutation {
        rpc(userId, "upsert_terminal_session", "p_session", Json.parseToJsonElement(rowJson))
    }.also { if (it) listMemo = null }

    override suspend fun delete(userId: String, shareId: String): Boolean = mutation {
        rpc(userId, "delete_terminal_session", "p_share_id", JsonPrimitive(shareId))
    }.also { if (it) listMemo = null }

    override suspend fun list(userId: String, since: String): String =
        remembered(userId, LIST_TTL_MS, { listMemo }, { listMemo = it }) {
            rpc(userId, "list_terminal_sessions", "p_since", JsonPrimitive(since))
        }

    internal companion object {
        /** Other devices' sessions show up within about a minute instead of 15 s; the server's own liveness cut is 90 s. */
        const val LIST_TTL_MS = 60_000L
        const val PREFERENCES_TTL_MS = 5 * 60_000L
        const val EDITING_WINDOW_MS = 10 * 60_000L
    }
}
