package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.*
import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.*

class HostAccountSessionBridgeTest {
    private class Auth : AuthDataProvider {
        override val currentUser = MutableStateFlow<UserData?>(null)
        override val isAdmin = MutableStateFlow(false)
        override val userPermissions = MutableStateFlow(emptySet<String>())
        override fun hasPermission(permission: String) = false
        override fun hasAnyPermission(vararg permissions: String) = false
    }
    private class Database : SupabaseDataProvider {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var response: suspend () -> Result<String> = { Result.success("[]") }
        override suspend fun rpc(function: String, parameters: String): Result<String> {
            calls += function to Json.parseToJsonElement(parameters).jsonObject
            return response()
        }
        override suspend fun select(table: String, columns: String, filters: List<QueryFilter>, range: QueryRange?) =
            error("Account bridge must use identity-checked RPCs")
    }
    private fun user(id: String) = UserData(id, "$id@example.test", null, null, emptyList(), 0)

    @Test fun `restores host login and uses authenticated RPCs without tokens`() = runBlocking {
        val auth = Auth().apply { currentUser.value = user("one") }
        val db = Database()
        val bridge = HostAccountSessionBridge(auth, db) {}
        bridge.start(this)
        try {
            assertEquals(AccountState.SignedIn("one@example.test", "one"), bridge.state.value)
            assertTrue(bridge.upsert("one", "{\"share_id\":\"0123456789abcdef\"}"))
            assertTrue(bridge.delete("one", "0123456789abcdef"))
            assertEquals("[]", bridge.list("one", "2026-09-25T00:00:00Z"))
            assertEquals(listOf("upsert_terminal_session", "delete_terminal_session", "list_terminal_sessions"), db.calls.map { it.first })
            assertTrue(db.calls.all { it.second["p_expected_user_id"] == JsonPrimitive("one") })
        } finally { bridge.close() }
        assertEquals(AccountState.SignedOut, bridge.state.value)
    }

    @Test fun `account switch waits for old link revocation and rejects in flight results`() = runBlocking {
        val auth = Auth().apply { currentUser.value = user("one") }
        val db = Database()
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupDone = CompletableDeferred<Unit>()
        val responseStarted = CompletableDeferred<Unit>()
        val responseDone = CompletableDeferred<Unit>()
        db.response = { responseStarted.complete(Unit); responseDone.await(); Result.success("[]") }
        val bridge = HostAccountSessionBridge(auth, db) { cleanupStarted.complete(Unit); cleanupDone.await() }
        bridge.start(this)
        try {
            val request = async { runCatching { bridge.list("one", "now") } }
            responseStarted.await()
            auth.currentUser.value = user("two")
            cleanupStarted.await()
            assertEquals(AccountState.SignedOut, bridge.state.value)
            assertFalse(bridge.upsert("one", "{}"))
            responseDone.complete(Unit)
            assertTrue(request.await().isFailure)
            cleanupDone.complete(Unit)
            yield()
            assertEquals(AccountState.SignedIn("two@example.test", "two"), bridge.state.value)
            auth.currentUser.value = null
            yield()
            assertEquals(AccountState.SignedOut, bridge.state.value)
        } finally { bridge.close() }
    }

    @Test fun `missing providers remain signed out and do not use standalone credentials`() = runBlocking {
        for ((auth, database) in listOf(null to Database(), Auth().apply { currentUser.value = user("one") } to null)) {
            val bridge = HostAccountSessionBridge(auth, database) {}
            bridge.start(this)
            try {
                assertEquals(AccountState.SignedOut, bridge.state.value)
                assertFalse(bridge.upsert("one", "{}"))
            } finally { bridge.close() }
        }
    }
    @Test fun `exhausted revocation stays signed out across later login`() = runBlocking {
        val auth = Auth().apply { currentUser.value = user("one") }
        var attempts = 0
        val failures = mutableListOf<Throwable>()
        val bridge = HostAccountSessionBridge(auth, Database(), onFailure = { failures += it }, cleanupRetryDelaysMillis = emptyList()) {
            attempts++
            if (attempts == 1) error("revocation failed")
        }
        bridge.start(this)
        try {
            auth.currentUser.value = user("two")
            yield()
            assertEquals(AccountState.SignedOut, bridge.state.value)
            assertFalse(bridge.upsert("two", "{}"))
            assertEquals(1, failures.size)
            auth.currentUser.value = user("three")
            yield()
            assertEquals(1, attempts)
            assertEquals(AccountState.SignedOut, bridge.state.value)
        } finally {
            bridge.close()
        }
    }

    @Test fun `email change does not revoke the same user sessions`() = runBlocking {
        val auth = Auth().apply { currentUser.value = user("one") }
        var revocations = 0
        val bridge = HostAccountSessionBridge(auth, Database()) { revocations++ }
        bridge.start(this)
        try {
            auth.currentUser.value = user("one").copy(email = "updated@example.test")
            yield()
            assertEquals(AccountState.SignedIn("updated@example.test", "one"), bridge.state.value)
            assertEquals(0, revocations)
        } finally {
            bridge.close()
        }
    }

    @Test fun `RPC failures return false for mutations and propagate for discovery`() = runBlocking {
        val auth = Auth().apply { currentUser.value = user("one") }
        val db = Database().apply { response = { Result.failure(IllegalStateException("unavailable")) } }
        val bridge = HostAccountSessionBridge(auth, db) {}
        bridge.start(this)
        try {
            assertFalse(bridge.upsert("one", "{}"))
            assertFalse(bridge.delete("one", "share"))
            assertFailsWith<IllegalStateException> { bridge.list("one", "now") }
        } finally {
            bridge.close()
        }
    }

    @Test fun `transient cleanup failure recovers without another login emission`(): Unit = runBlocking {
        val auth = Auth().apply { currentUser.value = user("one") }
        var attempts = 0
        val bridge = HostAccountSessionBridge(auth, Database(), cleanupRetryDelaysMillis = listOf(1)) {
            if (++attempts == 1) error("temporary cleanup failure")
        }
        bridge.start(this)
        try {
            auth.currentUser.value = user("two")
            withTimeout(2_000) { bridge.state.first { it == AccountState.SignedIn("two@example.test", "two") } }
            assertEquals(2, attempts)
        } finally { bridge.close() }
    }

    @Test fun `close cancels suspended cleanup and remains signed out`(): Unit = runBlocking {
        val auth = Auth().apply { currentUser.value = user("one") }
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val bridge = HostAccountSessionBridge(auth, Database()) {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        bridge.start(this)
        auth.currentUser.value = user("two")
        try { withTimeout(2_000) { entered.await() } } finally { bridge.close() }
        withTimeout(2_000) { cancelled.await() }
        assertEquals(AccountState.SignedOut, bridge.state.value)
    }

    @Test fun `exhausted cleanup invokes shutdown fallback while signed out`(): Unit = runBlocking {
        val auth = Auth().apply { currentUser.value = user("one") }
        var attempts = 0
        val stopped = CompletableDeferred<Unit>()
        val bridge = HostAccountSessionBridge(auth, Database(),
            onCleanupExhausted = { stopped.complete(Unit) }, cleanupRetryDelaysMillis = listOf(1, 1)) {
            attempts++
            error("persistent cleanup failure")
        }
        bridge.start(this)
        try {
            auth.currentUser.value = user("two")
            withTimeout(2_000) { stopped.await() }
            assertEquals(3, attempts)
            assertEquals(AccountState.SignedOut, bridge.state.value)
            assertFalse(bridge.upsert("two", "{}"))
        } finally { bridge.close() }
    }

}
