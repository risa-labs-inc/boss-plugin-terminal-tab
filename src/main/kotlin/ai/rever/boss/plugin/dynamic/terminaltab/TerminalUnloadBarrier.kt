package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.PluginUnloadDeferredException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.Toolkit
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities

/** Keep processing UI cancellation callbacks while IO drains; never permit a timed-out unload. */
internal fun awaitTerminalDrain(timeoutMillis: Long, drain: suspend () -> Unit) {
    val task = FutureTask<Unit> {
        try {
            runBlocking { withTimeout(timeoutMillis) { drain() } }
        } catch (failure: Exception) {
            throw PluginUnloadDeferredException("Terminal cleanup has not completed; retry unload", failure)
        }
    }
    if (SwingUtilities.isEventDispatchThread()) {
        val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        val waiter = Thread({
            task.run()
            SwingUtilities.invokeLater { loop.exit() }
        }, "terminal-unload-drain").apply { isDaemon = true }
        waiter.start()
        check(loop.enter()) { "Could not process terminal cleanup events" }
        // The waiter schedules exit as its final action; drain its thread before success/failure.
        waiter.join()
    } else {
        task.run()
    }
    try {
        task.get()
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    }
}
