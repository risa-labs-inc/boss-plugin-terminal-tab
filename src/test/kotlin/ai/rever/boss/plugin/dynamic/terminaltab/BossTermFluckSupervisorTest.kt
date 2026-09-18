package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.ApplicationEvent
import ai.rever.boss.plugin.api.ApplicationEventBus
import ai.rever.boss.plugin.api.CustomPluginEvent
import ai.rever.boss.plugin.api.PanelEventProvider
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.dynamic.terminaltab.onboarding.SetupDebugRequest
import ai.rever.boss.plugin.dynamic.terminaltab.onboarding.SetupTaskState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossTermFluckSupervisorTest {
    @Test
    fun `accepts only host acknowledgement correlated to request and terminal then resumes once`() = runBlocking {
        val fixture = Fixture()
        try {
            var acceptedCount = 0
            val result = async {
                fixture.supervisor.debugAndFix(fixture.request) { acceptedCount++ }
            }
            val requestEvent = fixture.nextPublished()

            assertEquals(BossTermFluckSupervisor.TERMINAL_PLUGIN_ID, requestEvent.sourcePluginId)
            assertEquals(BossTermFluckSupervisor.EVENT_DEBUG_OPEN, requestEvent.eventName)
            assertEquals(fixture.request.requestId, requestEvent.payload["requestId"])
            assertEquals(fixture.request.terminalId, requestEvent.payload["terminalId"])
            assertEquals(fixture.request.windowId, requestEvent.payload["windowId"])

            fixture.ack(source = "untrusted.plugin")
            fixture.ack(terminalId = "another-terminal")
            fixture.ack(requestId = "another-request")
            delay(25)
            assertEquals(0, acceptedCount)
            assertFalse(result.isCompleted)

            fixture.ack()
            withTimeout(TEST_TIMEOUT_MS) {
                while (acceptedCount == 0) delay(1)
            }
            assertTrue(fixture.supervisor.resumeDebug(fixture.request.requestId))
            assertFalse(fixture.supervisor.resumeDebug(fixture.request.requestId))
            assertTrue(withTimeout(TEST_TIMEOUT_MS) { result.await() }.completed)
            assertEquals(1, acceptedCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `rejected acknowledgement does not expose terminal`() = runBlocking {
        val fixture = Fixture()
        try {
            var accepted = false
            val result = async {
                fixture.supervisor.debugAndFix(fixture.request) { accepted = true }
            }
            fixture.nextPublished()
            fixture.ack(accepted = false, error = "Fluck is unavailable")

            val outcome = withTimeout(TEST_TIMEOUT_MS) { result.await() }
            assertFalse(outcome.completed)
            assertEquals("Fluck is unavailable", outcome.error)
            assertFalse(accepted)
            assertFalse(fixture.supervisor.resumeDebug(fixture.request.requestId))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `dispose unblocks accepted handoff and reports failure`() = runBlocking {
        val fixture = Fixture()
        try {
            val terminalExposed = CompletableDeferred<Unit>()
            val result = async {
                fixture.supervisor.debugAndFix(fixture.request) { terminalExposed.complete(Unit) }
            }
            fixture.nextPublished()
            fixture.ack()
            withTimeout(TEST_TIMEOUT_MS) { terminalExposed.await() }

            fixture.supervisor.dispose()

            val outcome = withTimeout(TEST_TIMEOUT_MS) { result.await() }
            assertFalse(outcome.completed)
            assertEquals("Terminal Tab was disabled during debugging", outcome.error)
            assertFalse(fixture.supervisor.resumeDebug(fixture.request.requestId))
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val bus = FakeApplicationEventBus()
        val supervisor = BossTermFluckSupervisor(pluginContext(scope, bus))
        val request = SetupDebugRequest(
            requestId = "request-1",
            sessionId = "session-1",
            windowId = "window-1",
            terminalId = "terminal-1",
            task = SetupTaskState("task-1", "Install tools", "test"),
            output = "installer failed",
        )

        suspend fun nextPublished(): CustomPluginEvent =
            withTimeout(TEST_TIMEOUT_MS) { bus.published.receive() }

        fun ack(
            source: String = BossTermFluckSupervisor.HOST_PLUGIN_ID,
            requestId: String = request.requestId,
            terminalId: String = request.terminalId,
            accepted: Boolean = true,
            error: String? = null,
        ) {
            bus.emit(
                CustomPluginEvent(
                    sourcePluginId = source,
                    eventName = BossTermFluckSupervisor.EVENT_DEBUG_OPENED,
                    payload = buildMap {
                        put("requestId", requestId)
                        put("terminalId", terminalId)
                        put("accepted", accepted)
                        error?.let { put("error", it) }
                    },
                ),
            )
        }

        fun close() {
            supervisor.dispose()
            scope.cancel()
        }
    }

    private class FakeApplicationEventBus : ApplicationEventBus {
        private val eventFlow = MutableSharedFlow<ApplicationEvent>(extraBufferCapacity = 16)
        val published = Channel<CustomPluginEvent>(Channel.UNLIMITED)

        override fun events(): Flow<ApplicationEvent> = eventFlow

        override fun <T : ApplicationEvent> eventsOfType(eventType: Class<T>): Flow<T> =
            eventFlow.filter(eventType::isInstance).map(eventType::cast)

        override fun publish(event: ApplicationEvent) {
            if (event is CustomPluginEvent) published.trySend(event)
            eventFlow.tryEmit(event)
        }

        fun emit(event: ApplicationEvent) {
            check(eventFlow.tryEmit(event))
        }
    }

    companion object {
        private const val TEST_TIMEOUT_MS = 1_000L

        private fun pluginContext(scope: CoroutineScope, bus: ApplicationEventBus): PluginContext =
            Proxy.newProxyInstance(
                PluginContext::class.java.classLoader,
                arrayOf(PluginContext::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "getPluginScope" -> scope
                    "getApplicationEventBus" -> bus
                    "getPanelEventProvider" -> interfaceProxy(PanelEventProvider::class.java)
                    "toString" -> "TestPluginContext"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            } as PluginContext

        private fun <T> interfaceProxy(type: Class<T>): T =
            type.cast(
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null },
            )
    }
}
