package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.SupabaseDataProvider
import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import ai.rever.bossterm.compose.share.HostAccountSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The host owns login and token refresh; only identity and authenticated RPC results reach BossTerm. */
internal class HostAccountSessionBridge(
    private val auth: AuthDataProvider?,
    private val database: SupabaseDataProvider?,
    private val onFailure: (Throwable) -> Unit = {},
    private val onIdentityChanging: suspend () -> Unit,
) : HostAccountSessions {
    private val mutableState = MutableStateFlow<AccountState>(AccountState.SignedOut)
    override val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var cleanupPending = false

    fun start(scope: CoroutineScope) {
        if (job != null) return
        // Publish the current identity before any terminal UI or account service is created.
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            auth?.currentUser?.collect { user ->
                val next = user?.takeIf { database != null }?.let { AccountState.SignedIn(it.email, it.id) }
                    ?: AccountState.SignedOut
                val previous = mutableState.value as? AccountState.SignedIn
                if (cleanupPending || previous?.userId != (next as? AccountState.SignedIn)?.userId) {
                    mutableState.value = AccountState.SignedOut
                    cleanupPending = cleanupPending || previous != null
                    if (cleanupPending) {
                        try {
                            onIdentityChanging()
                            cleanupPending = false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            onFailure(t)
                            // Keep tracking login, but never publish under a new identity until
                            // all old-account links and connections have been revoked.
                            return@collect
                        }
                    }
                }
                mutableState.value = next
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

    private suspend fun rpc(userId: String, function: String, key: String, value: JsonElement): String {
        check(owns(userId)) { "Terminal account changed" }
        val parameters = buildJsonObject {
            put("p_expected_user_id", userId)
            put(key, value)
        }.toString()
        val result = checkNotNull(database).rpc(function, parameters).getOrThrow()
        check(owns(userId)) { "Terminal account changed" }
        return result
    }

    // Errors never log row payloads: share URLs contain bearer credentials and E2E keys.
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
    }

    override suspend fun delete(userId: String, shareId: String): Boolean = mutation {
        rpc(userId, "delete_terminal_session", "p_share_id", JsonPrimitive(shareId))
    }

    override suspend fun list(userId: String, since: String): String =
        rpc(userId, "list_terminal_sessions", "p_since", JsonPrimitive(since))
}
