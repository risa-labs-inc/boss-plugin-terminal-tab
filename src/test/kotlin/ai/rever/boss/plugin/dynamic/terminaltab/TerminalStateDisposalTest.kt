package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.dynamic.terminaltab.onboarding.BossTermSetupController
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import kotlinx.coroutines.Job
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TerminalStateDisposalTest {
    @BeforeTest
    fun setup() {
        TerminalStateLifetime.resetForTest()
    }

    @AfterTest
    fun cleanup() {
        disposeRetainedTerminalStates(null, lastWindow = true)
        TerminalStateLifetime.resetForTest()
    }

    @Test
    fun `disposing one window removes both terminal types and preserves the other window`() {
        val originalMcpCount = McpTerminalRegistry.stateCount()
        listOf("closing", "surviving").forEach { window ->
            TabbedTerminalStateRegistry.getOrCreate(window, "tab")
            TerminalStateRegistry.getOrCreate(window, "embed")
        }
        val closingScope = assertNotNull(TabbedTerminalStateRegistry.get("closing", "tab")?.parentScope)
        val survivingScope = assertNotNull(TabbedTerminalStateRegistry.get("surviving", "tab")?.parentScope)
        assertTrue(closingScope.coroutineContext[Job]!!.isActive)
        disposeRetainedTerminalStates("closing", lastWindow = false)
        assertFalse(TabbedTerminalStateRegistry.contains("closing", "tab"))
        assertFalse(TerminalStateRegistry.contains("closing", "embed"))
        assertTrue(TabbedTerminalStateRegistry.contains("surviving", "tab"))
        assertTrue(TerminalStateRegistry.contains("surviving", "embed"))
        assertEquals(originalMcpCount + 1, McpTerminalRegistry.stateCount())
        assertNull(TabbedTerminalStateRegistry.getOrCreate("closing", "late-tab"))
        assertNull(TerminalStateRegistry.getOrCreate("closing", "late-embed"))
        assertNotNull(TabbedTerminalStateRegistry.getOrCreate("surviving", "another-tab"))
        assertTrue(closingScope.coroutineContext[Job]!!.isCancelled)
        assertTrue(survivingScope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun `last plugin instance clears all retained states including unnamed windows`() {
        listOf("a", "b", "").forEach { window ->
            TabbedTerminalStateRegistry.getOrCreate(window, "tab")
            TerminalStateRegistry.getOrCreate(window, "embed")
        }
        disposeRetainedTerminalStates(null, lastWindow = true)
        disposeRetainedTerminalStates(null, lastWindow = true)
        listOf("a", "b", "").forEach { window ->
            assertFalse(TabbedTerminalStateRegistry.contains(window, "tab"))
            assertFalse(TerminalStateRegistry.contains(window, "embed"))
        }
        assertEquals(0, McpTerminalRegistry.stateCount())
    }

    @Test
    fun `unload leaves reset notifications unchanged and rejects late state creation`() {
        TabbedTerminalStateRegistry.getOrCreate("window", "tab")
        TerminalStateRegistry.getOrCreate("window", "embed")
        val tabbedGeneration = TabbedTerminalStateRegistry.resetGeneration.value
        val embeddedGeneration = TerminalStateRegistry.resetGeneration.value

        disposeRetainedTerminalStates("window", lastWindow = true)

        assertEquals(tabbedGeneration, TabbedTerminalStateRegistry.resetGeneration.value)
        assertEquals(embeddedGeneration, TerminalStateRegistry.resetGeneration.value)
        assertFalse(TerminalStateLifetime.state.value.accepts("window"))
        listOf("window", "late-window", "").forEach { window ->
            assertNull(TabbedTerminalStateRegistry.getOrCreate(window, "late-tab"))
            assertNull(TerminalStateRegistry.getOrCreate(window, "late-embed"))
        }
        assertEquals(0, McpTerminalRegistry.stateCount())
    }

    @Test
    fun `re-registering restores terminal creation with fresh state`() {
        val previous = assertNotNull(TabbedTerminalStateRegistry.getOrCreate("window", "tab"))
        val previousEmbedded = assertNotNull(TerminalStateRegistry.getOrCreate("window", "embed"))
        assertSame(previous.parentScope, previousEmbedded.parentScope)
        val previousScope = assertNotNull(previous.parentScope)
        val previousLifetime = TerminalStateLifetime.state.value
        val generation = previousLifetime.generation
        disposeRetainedTerminalStates("window", lastWindow = true)
        assertFalse(TerminalStateLifetime.canRender("window", previousLifetime))

        TerminalStateLifetime.register("window")

        assertTrue(TerminalStateLifetime.state.value.accepts("window"))
        assertFalse(TerminalStateLifetime.canRender("window", previousLifetime))
        assertTrue(TerminalStateLifetime.canRender("window", TerminalStateLifetime.state.value))
        assertEquals(generation + 1, TerminalStateLifetime.state.value.generation)
        val replacement = assertNotNull(TabbedTerminalStateRegistry.getOrCreate("window", "tab"))
        assertNotSame(previous, replacement)
        assertNotSame(previousScope, replacement.parentScope)
        assertTrue(previousScope.coroutineContext[Job]!!.isCancelled)
        assertTrue(replacement.parentScope!!.coroutineContext[Job]!!.isActive)
        assertNotSame(previousEmbedded, assertNotNull(TerminalStateRegistry.getOrCreate("window", "embed")))
        assertEquals(1, McpTerminalRegistry.stateCount())
    }

    @Test
    fun `explicit reset still notifies consumers and permits replacement terminals`() {
        val previous = assertNotNull(TabbedTerminalStateRegistry.getOrCreate("window", "tab"))
        val previousEmbedded = assertNotNull(TerminalStateRegistry.getOrCreate("window", "embed"))
        val tabbedGeneration = TabbedTerminalStateRegistry.resetGeneration.value
        val embeddedGeneration = TerminalStateRegistry.resetGeneration.value

        TabbedTerminalStateRegistry.resetAllTerminals()
        TerminalStateRegistry.resetAll()

        assertEquals(tabbedGeneration + 1, TabbedTerminalStateRegistry.resetGeneration.value)
        assertEquals(embeddedGeneration + 1, TerminalStateRegistry.resetGeneration.value)
        assertNotSame(previous, assertNotNull(TabbedTerminalStateRegistry.getOrCreate("window", "tab")))
        assertNotSame(previousEmbedded, assertNotNull(TerminalStateRegistry.getOrCreate("window", "embed")))
    }

    @Test
    fun `setup request after unload is rejected without starting a worker`() {
        BossTermSetupController.resetForTest()
        disposeRetainedTerminalStates(null, lastWindow = true)
        try {
            assertFalse(BossTermSetupController.startTerminalTaskForTest("echo should-not-run"))
            assertNull(BossTermSetupController.state.value.sessionId)
            assertEquals(0, McpTerminalRegistry.stateCount())
        } finally {
            BossTermSetupController.resetForTest()
        }
    }

    @Test
    fun `unload attempts every terminal before reporting disposal failures`() {
        val disposed = mutableListOf<Int>()
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        val failure = assertFailsWith<IllegalStateException> {
            disposeTerminalStates(listOf(1, 2, 3), forUnload = true) { terminal ->
                disposed += terminal
                if (terminal == 1) throw first
                if (terminal == 2) throw second
            }
        }
        assertEquals(listOf(1, 2, 3), disposed)
        assertEquals(first, failure)
        assertEquals(listOf(second), failure.suppressed.toList())
    }

    @Test
    fun `cleanup waits for preceding UI work and also works when already on EDT`() {
        val uiWorkStarted = CountDownLatch(1)
        val releaseUiWork = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val cleanupFinished = AtomicBoolean(false)
        val interruptRestored = AtomicBoolean(false)
        javax.swing.SwingUtilities.invokeLater {
            uiWorkStarted.countDown()
            assertTrue(releaseUiWork.await(5, TimeUnit.SECONDS))
            TabbedTerminalStateRegistry.getOrCreate("window", "from-composition")
        }
        assertTrue(uiWorkStarted.await(5, TimeUnit.SECONDS))
        val cleanup = Thread {
            cleanupStarted.countDown()
            runTerminalDisposalOnUiThread {
                assertTrue(javax.swing.SwingUtilities.isEventDispatchThread())
                runTerminalDisposalOnUiThread {
                    disposeRetainedTerminalStates("window", lastWindow = true)
                }
            }
            interruptRestored.set(Thread.currentThread().isInterrupted)
            cleanupFinished.set(true)
        }
        cleanup.start()
        try {
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS))
            cleanup.interrupt()
            assertFalse(cleanupFinished.get())
        } finally {
            releaseUiWork.countDown()
        }
        cleanup.join(5_000)
        assertFalse(cleanup.isAlive)
        assertTrue(cleanupFinished.get())
        assertTrue(interruptRestored.get())
        assertFalse(TabbedTerminalStateRegistry.contains("window", "from-composition"))
        assertNull(TabbedTerminalStateRegistry.getOrCreate("window", "late-tab"))
    }
}
