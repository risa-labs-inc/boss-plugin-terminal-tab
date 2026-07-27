package ai.rever.boss.plugin.dynamic.terminaltab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Block the calling thread until every coroutine currently running on [scope]
 * has finished, or [timeoutMs] elapses — whichever comes first.
 *
 * This exists because a BOSS plugin's `dispose()` is the LAST moment its
 * classloader is usable: `DynamicPluginLoader.unloadPlugin` calls
 * `instance.dispose()` and then, with nothing in between, marks the loader
 * UNLOADING and closes it. Any coroutine still running past that point is
 * executing against a closed `URLClassLoader`, so its next first-time class
 * load fails — and `PluginClassLoader` silently falls back to the host
 * classloader, splicing a *different* copy of the same library into a live
 * object graph (`LinkageError: loader constraint violation`). So a teardown
 * that must finish has to finish HERE, on this thread.
 *
 * Deliberate properties:
 *  - **Bounded.** `dispose()` hanging is worse than the leak it prevents, so
 *    the wait is capped. Callers get `false` and can log it.
 *  - **Non-destructive on timeout.** [withTimeoutOrNull] cancels the coroutine
 *    doing the *joining*, never the jobs being joined. When the budget runs out
 *    the teardown keeps running: it may still finish microseconds later and
 *    release its port, whereas cancelling it would guarantee the leak.
 *  - **Idempotent / safe to call twice.** A scope with no children returns
 *    `true` immediately without blocking, so a second `dispose()` is free.
 *
 * The snapshot of children is taken once, up front. Callers are expected to
 * have already stopped anything that would launch more work on [scope]
 * (watchers, `Eagerly`-shared `stateIn` collectors); a child that never
 * completes on its own would otherwise burn the whole budget.
 *
 * @param onTimeout invoked with the number of jobs still active, only when the
 *   budget expires. Never invoked on the happy path.
 * @return true if everything finished within [timeoutMs].
 */
internal fun awaitScopeIdle(
    scope: CoroutineScope,
    timeoutMs: Long,
    onTimeout: (pendingJobs: Int) -> Unit = {},
): Boolean {
    val pending: List<Job> = scope.coroutineContext.job.children.toList()
    if (pending.isEmpty()) return true

    val finished = runBlocking {
        withTimeoutOrNull(timeoutMs) { pending.joinAll() } != null
    }

    if (!finished) onTimeout(pending.count { it.isActive })
    return finished
}
