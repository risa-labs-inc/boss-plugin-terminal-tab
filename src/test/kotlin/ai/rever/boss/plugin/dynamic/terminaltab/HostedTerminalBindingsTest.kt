package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.DaemonServiceConnection
import ai.rever.boss.plugin.api.DaemonServiceProvider
import ai.rever.boss.plugin.api.PluginUnloadDeferredException
import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.daemon.DaemonAttachProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HostedTerminalBindingsTest {
    private class Worker : DaemonServiceConnection {
        @Volatile var protocol = DaemonAttachProtocol.PROTOCOL_VERSION.toString()
        override val endpoints get() = mapOf("attachProtocol" to protocol)
        val attachments = AtomicInteger()
        val closes = AtomicInteger()
        var close: suspend () -> Unit = {}
        override suspend fun request(method: String, payload: String): String = when (method) {
            "attach" -> {
                attachments.incrementAndGet()
                """{"port":1,"token":"test-only","protocol":${DaemonAttachProtocol.PROTOCOL_VERSION}}"""
            }
            "close" -> { closes.incrementAndGet(); close(); "" }
            else -> error("Unexpected request")
        }
        override suspend fun stop() = error("UI must not stop the shared worker")
    }

    private class Provider(@Volatile var worker: Worker) : DaemonServiceProvider {
        override suspend fun connect(serviceId: String, entryPoint: String, configuration: Map<String, String>): DaemonServiceConnection {
            assertTrue(configuration.getValue("settingsDirectory").isNotBlank())
            return worker
        }
    }

    @BeforeTest
    fun setup() {
        TerminalStateLifetime.resetForTest()
        HostWindows.resetForTest()
    }

    @AfterTest
    fun cleanup() {
        disposeRetainedTerminalStates(null, lastWindow = true)
        HostedTerminalBindings.drainForUnload(null, lastWindow = true)
        TerminalStateLifetime.resetForTest()
        HostWindows.resetForTest()
    }

    private fun state(id: String) = assertNotNull(TabbedTerminalStateRegistry.getOrCreate("window", id))
    private fun bind(state: TabbedTerminalState, id: String, provider: DaemonServiceProvider?) =
        HostedTerminalBindings.bind(state, "window", id, null, null, provider, daemonEnabled = true)

    private suspend fun ready(status: MutableStateFlow<HostedTerminalBindings.Status>) {
        withTimeout(5_000) { while (status.value != HostedTerminalBindings.Status.DAEMON) delay(10) }
    }

    @Test
    fun `ordinary removal returns on EDT while remote close is blocked`() = runBlocking<Unit> {
        val worker = Worker()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        worker.close = { entered.complete(Unit); release.await() }
        ready(bind(state("terminal"), "terminal", Provider(worker)))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val removal = executor.submit { SwingUtilities.invokeAndWait { TabbedTerminalStateRegistry.remove("window", "terminal") } }
            removal.get(2, TimeUnit.SECONDS)
            withTimeout(5_000) { entered.await() }
            assertFalse(TabbedTerminalStateRegistry.contains("window", "terminal"))
        } finally {
            release.complete(Unit)
            executor.shutdownNow()
        }
        HostedTerminalBindings.drainForUnload(null, lastWindow = true)
        assertEquals(1, worker.closes.get())
    }

    @Test
    fun `reset clears every state and notifies even when one daemon close throws`() = runBlocking<Unit> {
        val worker = Worker()
        val failOnce = AtomicBoolean(true)
        worker.close = { if (failOnce.getAndSet(false)) error("Injected close failure") }
        val provider = Provider(worker)
        ready(bind(state("one"), "one", provider))
        ready(bind(state("two"), "two", provider))
        val generation = TabbedTerminalStateRegistry.resetGeneration.value
        SwingUtilities.invokeAndWait { assertEquals(2, TabbedTerminalStateRegistry.resetAllTerminals()) }
        assertFalse(TabbedTerminalStateRegistry.contains("window", "one"))
        assertFalse(TabbedTerminalStateRegistry.contains("window", "two"))
        assertEquals(generation + 1, TabbedTerminalStateRegistry.resetGeneration.value)
        HostedTerminalBindings.drainForUnload(null, lastWindow = true)
        assertEquals(3, worker.closes.get())
    }

    @Test
    fun `replacement waits for preceding close before attaching`() = runBlocking<Unit> {
        val worker = Worker()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        worker.close = { entered.complete(Unit); release.await() }
        val provider = Provider(worker)
        ready(bind(state("terminal"), "terminal", provider))
        TabbedTerminalStateRegistry.remove("window", "terminal")
        val replacement = bind(state("terminal"), "terminal", provider)
        try {
            withTimeout(5_000) { entered.await() }
            assertEquals(HostedTerminalBindings.Status.CONNECTING, replacement.value)
            assertEquals(1, worker.attachments.get())
        } finally { release.complete(Unit) }
        ready(replacement)
        assertEquals(2, worker.attachments.get())
        assertEquals(1, worker.closes.get())
    }

    @Test
    fun `failed close is retried before a replacement can attach`() = runBlocking<Unit> {
        val worker = Worker()
        val failOnce = AtomicBoolean(true)
        worker.close = { if (failOnce.getAndSet(false)) error("Injected close failure") }
        val provider = Provider(worker)
        ready(bind(state("retry-close"), "retry-close", provider))
        TabbedTerminalStateRegistry.remove("window", "retry-close")
        ready(bind(state("retry-close"), "retry-close", provider))
        assertEquals(2, worker.closes.get())
        assertEquals(2, worker.attachments.get())
    }

    @Test
    fun `failed close reconnects to a fresh worker before replacement attach`() = runBlocking<Unit> {
        val failed = CompletableDeferred<Unit>()
        val oldWorker = Worker().apply { close = { failed.complete(Unit); error("Daemon unavailable") } }
        val provider = Provider(oldWorker)
        ready(bind(state("restart"), "restart", provider))
        TabbedTerminalStateRegistry.remove("window", "restart")
        withTimeout(5_000) { failed.await() }
        val freshWorker = Worker()
        provider.worker = freshWorker
        ready(bind(state("restart"), "restart", provider))
        assertEquals(1, freshWorker.closes.get())
        assertEquals(1, freshWorker.attachments.get())
    }

    @Test
    fun `anonymous close retries without a replacement bind`() = runBlocking<Unit> {
        val worker = Worker()
        val failOnce = AtomicBoolean(true)
        worker.close = { if (failOnce.getAndSet(false)) error("Transient transport failure") }
        ready(bind(state("anonymous:unique"), "anonymous:unique", Provider(worker)))
        TabbedTerminalStateRegistry.remove("window", "anonymous:unique")
        withTimeout(5_000) { while (worker.closes.get() < 2) delay(10) }
        HostedTerminalBindings.drainForUnload(null, lastWindow = true)
        assertEquals(2, worker.closes.get())
    }

    @Test
    fun `exhausted close retries defer unload and can recover on a later attempt`() = runBlocking<Unit> {
        val worker = Worker().apply { close = { error("Persistent transport failure") } }
        ready(bind(state("pending-close"), "pending-close", Provider(worker)))
        TabbedTerminalStateRegistry.remove("window", "pending-close")
        try {
            assertFailsWith<PluginUnloadDeferredException> {
                HostedTerminalBindings.drainForUnload(null, lastWindow = true)
            }
            assertTrue(worker.closes.get() >= 3)
        } finally { worker.close = {} }
        HostedTerminalBindings.drainForUnload(null, lastWindow = true)
    }

    @Test
    fun `removal during connect still closes a previously existing daemon surface`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val worker = Worker()
        val calls = AtomicInteger()
        val provider = object : DaemonServiceProvider {
            override suspend fun connect(serviceId: String, entryPoint: String, configuration: Map<String, String>): DaemonServiceConnection {
                if (calls.incrementAndGet() == 1) { entered.complete(Unit); never.await() }
                return worker
            }
        }
        val status = bind(state("existing"), "existing", provider)
        withTimeout(5_000) { entered.await() }
        assertEquals(HostedTerminalBindings.Status.CONNECTING, status.value)
        TabbedTerminalStateRegistry.remove("window", "existing")
        HostedTerminalBindings.drainForUnload(null, lastWindow = true)
        assertEquals(1, worker.closes.get())
        assertEquals(0, worker.attachments.get())
    }

    @Test
    fun `unload timeout processes UI events and refuses success until IO drains`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val eventProcessed = AtomicBoolean()
        val worker = Worker()
        val provider = object : DaemonServiceProvider {
            override suspend fun connect(serviceId: String, entryPoint: String, configuration: Map<String, String>): DaemonServiceConnection {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                return worker
            }
        }
        bind(state("blocked"), "blocked", provider)
        withTimeout(5_000) { entered.await() }
        try {
            SwingUtilities.invokeAndWait {
                HostedTerminalBindings.detach(state("blocked"), forUnload = true)
                SwingUtilities.invokeLater { eventProcessed.set(true) }
                assertFailsWith<PluginUnloadDeferredException> {
                    HostedTerminalBindings.drainForUnload(null, lastWindow = true, timeoutMillis = 100)
                }
                assertTrue(eventProcessed.get(), "The EDT must keep processing events during unload")
            }
        } finally { release.complete(Unit) }
        HostedTerminalBindings.drainForUnload(null, lastWindow = true)
    }

    @Test
    fun `opted out terminals never connect or issue a daemon close`() {
        val worker = Worker()
        val state = state("opt-out")
        val status = HostedTerminalBindings.bind(state, "window", "opt-out", null, null, Provider(worker), daemonEnabled = false)
        assertEquals(HostedTerminalBindings.Status.LOCAL, status.value)
        TabbedTerminalStateRegistry.remove("window", "opt-out")
        HostedTerminalBindings.drainForUnload(null, lastWindow = true)
        assertEquals(0, worker.attachments.get())
        assertEquals(0, worker.closes.get())
    }

    @Test
    fun `missing provider stays local and detach never sends close`() = runBlocking<Unit> {
        val local = bind(state("local"), "local", null)
        assertEquals(HostedTerminalBindings.Status.LOCAL, local.value)
        val worker = Worker()
        val mirror = state("mirror")
        ready(bind(mirror, "mirror", Provider(worker)))
        HostedTerminalBindings.detach(mirror, forUnload = true)
        assertEquals(0, worker.closes.get())
    }

    @Test
    fun `protocol mismatch fails without attach and retry negotiates again`() = runBlocking<Unit> {
        val worker = Worker().apply { protocol = "incompatible" }
        val mirror = state("protocol")
        val status = bind(mirror, "protocol", Provider(worker))
        withTimeout(5_000) { while (status.value != HostedTerminalBindings.Status.FAILED) delay(10) }
        assertEquals(0, worker.attachments.get())
        worker.protocol = DaemonAttachProtocol.PROTOCOL_VERSION.toString()
        HostedTerminalBindings.retry(mirror)
        ready(status)
        assertEquals(1, worker.attachments.get())
    }

    @Test
    fun `blank settings override resolves to the host profile directory`() {
        val key = "bossterm.settings.dir"
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, " ")
            assertEquals(bossDataDir("bossterm").absolutePath, resolvedTerminalSettingsDirectory())
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }
}
