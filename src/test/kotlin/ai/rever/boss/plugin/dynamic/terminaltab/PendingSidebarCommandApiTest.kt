package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.TerminalTabPluginAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `setPendingSidebarCommand` shipped for four releases as a call to itself.
 *
 * The override delegates to a top-level function of the same name in this same
 * package, and the delegating call was written unqualified. Kotlin resolves that
 * to the member, not the top-level function, so every call recursed until the
 * stack was gone — a `StackOverflowError` on whichever thread clicked. Its only
 * caller is the DeepSeek Harness panel's Install button, which is why nothing
 * caught it: the host's own `TerminalAPIAccess` wrapper for this method has no
 * callers at all.
 *
 * Nothing about the signature says "this must be qualified", and the identical
 * shape one method below (`consumePendingSidebarCommand`) is only correct
 * because someone happened to write the package out. So the guard is a test
 * that goes through the interface and back out again — the round trip fails
 * loudly on recursion rather than on a code-review reading of one line.
 */
class PendingSidebarCommandApiTest {
    private fun api(): TerminalTabPluginAPI = TerminalTabPluginAPIImpl()

    @Test
    fun `set then consume round-trips through the plugin API`() {
        val api = api()
        val windowId = "window-round-trip"

        api.setPendingSidebarCommand(windowId, "npm install -g dsh@latest", null, null)

        val pending = api.consumePendingSidebarCommand(windowId)
        assertNotNull(pending, "the command set through the API was not readable back")
        assertEquals("npm install -g dsh@latest", pending.command)
        assertNull(pending.workingDirectory)
        assertNull(pending.configId)
    }

    @Test
    fun `working directory and config id survive the delegation`() {
        val api = api()
        val windowId = "window-with-config"

        api.setPendingSidebarCommand(windowId, "pnpm build", "/tmp/project", "config-7")

        val pending = api.consumePendingSidebarCommand(windowId)
        assertNotNull(pending)
        assertEquals("pnpm build", pending.command)
        assertEquals("/tmp/project", pending.workingDirectory)
        assertEquals("config-7", pending.configId)
    }

    /**
     * The recursion was unbounded, so it blew the stack on the first call. A
     * shallow stack makes that failure immediate and unambiguous: if the
     * delegation ever rebinds to itself, this dies with StackOverflowError
     * instead of quietly passing on a machine with a generous default stack.
     */
    @Test
    fun `does not recurse into itself`() {
        val error =
            runCatching {
                val thread =
                    Thread(null, {
                        api().setPendingSidebarCommand("window-shallow-stack", "echo hi", null, null)
                    }, "pending-sidebar-shallow", 256 * 1024)
                var caught: Throwable? = null
                thread.setUncaughtExceptionHandler { _, t -> caught = t }
                thread.start()
                thread.join()
                caught
            }.getOrThrow()

        assertNull(error, "setPendingSidebarCommand blew a 256 KiB stack — it is calling itself")
        assertNotNull(consumePendingSidebarCommand("window-shallow-stack"))
    }

    /** Consuming twice must not resurrect the command, and must not throw. */
    @Test
    fun `consume clears the pending command`() {
        val api = api()
        val windowId = "window-consume-twice"

        api.setPendingSidebarCommand(windowId, "ls", null, null)

        assertNotNull(api.consumePendingSidebarCommand(windowId))
        assertNull(api.consumePendingSidebarCommand(windowId))
    }
}
