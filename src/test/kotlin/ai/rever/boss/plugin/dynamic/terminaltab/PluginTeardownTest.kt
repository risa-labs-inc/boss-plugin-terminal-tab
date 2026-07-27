package ai.rever.boss.plugin.dynamic.terminaltab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the teardown wait that makes `TerminalTabDynamicPlugin.dispose()`
 * synchronous.
 *
 * These stand in for the real thing on purpose. `BossTermMcpManager.stop()`
 * launches its Ktor engine shutdown on the scope the plugin hands it and returns
 * immediately; what `dispose()` needs from [awaitScopeIdle] is exactly "do not
 * return while a coroutine on that scope is still running". A fake coroutine on
 * a real scope exercises that contract without binding a port — and, unlike a
 * test that spun up a real engine, it fails deterministically if the wait is
 * removed.
 *
 * What is NOT covered here, and is argued rather than tested: that the host
 * closes the classloader immediately after `dispose()` returns (read from
 * `DynamicPluginLoader.unloadPlugin`), and that finishing the stop before that
 * point avoids the cross-loader `LinkageError`.
 */
class PluginTeardownTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `returns immediately when nothing is in flight`() {
        var timedOut = false

        val elapsed = measureTimeMillis {
            assertTrue(awaitScopeIdle(scope, timeoutMs = 5_000) { timedOut = true })
        }

        assertFalse(timedOut, "an idle scope must not report a timeout")
        assertTrue(elapsed < 1_000, "an idle scope should not block; took ${elapsed}ms")
    }

    /**
     * The regression test for the bug: a fire-and-forget stop lets `dispose()`
     * return while the engine shutdown is still running, and the host closes the
     * classloader out from under it. `awaitScopeIdle` must not return first.
     */
    @Test
    fun `blocks until an in-flight teardown finishes`() {
        val teardownFinished = AtomicBoolean(false)
        scope.launch {
            delay(300)
            teardownFinished.set(true)
        }

        val elapsed = measureTimeMillis {
            assertTrue(awaitScopeIdle(scope, timeoutMs = 5_000))
        }

        assertTrue(
            teardownFinished.get(),
            "returned while the teardown was still running — dispose() would race the classloader close",
        )
        assertTrue(elapsed >= 250, "did not actually wait for the teardown; returned after ${elapsed}ms")
    }

    /**
     * A wedged shutdown must not wedge `dispose()`. The budget is a ceiling, the
     * overshoot is reported, and — critically — the work is left running rather
     * than cancelled, because a stop that finishes late still releases its port
     * while a cancelled one never does.
     */
    @Test
    fun `gives up after the timeout, reports it, and leaves the work running`() {
        val release = CompletableDeferred<Unit>()
        val completedNormally = AtomicBoolean(false)
        val job = scope.launch {
            release.await()
            completedNormally.set(true)
        }
        val timeoutReports = AtomicInteger(0)
        var reportedPending = -1

        val elapsed = measureTimeMillis {
            assertFalse(
                awaitScopeIdle(scope, timeoutMs = 200) { pending ->
                    timeoutReports.incrementAndGet()
                    reportedPending = pending
                },
            )
        }

        assertEquals(1, timeoutReports.get(), "an expired budget must be reported exactly once")
        assertEquals(1, reportedPending, "should report how many jobs were still active")
        assertTrue(elapsed < 3_000, "the wait must be bounded; took ${elapsed}ms")
        assertTrue(job.isActive, "timing out must not cancel the in-flight teardown")

        // ...and the abandoned job still runs to completion, which is the whole
        // reason we don't cancel it.
        release.complete(Unit)
        runBlocking { job.join() }
        assertTrue(completedNormally.get(), "the abandoned teardown should still be able to finish")
        assertFalse(job.isCancelled, "the job must not have been cancelled by the expired wait")
    }

    /**
     * `dispose()` may be called more than once (uninstall after a failed
     * reload, quit after a disable). The second pass must be free.
     */
    @Test
    fun `is safe to call twice`() {
        scope.launch { delay(100) }
        assertTrue(awaitScopeIdle(scope, timeoutMs = 5_000))

        var timedOut = false
        val elapsed = measureTimeMillis {
            assertTrue(awaitScopeIdle(scope, timeoutMs = 5_000) { timedOut = true })
        }

        assertFalse(timedOut, "a second dispose() must not report a timeout")
        assertTrue(elapsed < 1_000, "a second dispose() should not block; took ${elapsed}ms")
    }
}
