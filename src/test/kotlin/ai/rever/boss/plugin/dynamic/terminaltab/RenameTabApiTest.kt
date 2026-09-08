package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.PendingSidebarCommand
import ai.rever.boss.plugin.api.TerminalTabPluginAPI
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `renameTab`, and what it promises a caller when it cannot do the job.
 *
 * A plugin that reuses one terminal tab delivers commands by typing into its pty, which is safe
 * only while that tab holds nothing else. Nothing marked the tab as plugin-owned, so nothing
 * stopped a user starting something in it. This is the label that warns them off, and the
 * per-command labelling that `TerminalCommand.title` lost when the reuse path landed.
 *
 * The interesting half is the failure path. A rename that quietly reports success when the tab
 * was never found would leave a plugin believing the tab is labelled and the user looking at one
 * that is not, which is the same "reported success, nothing happened" shape as the split race in
 * boss-plugins#13.
 */
class RenameTabApiTest {
    @Test
    fun `renaming a tab that does not exist reports failure rather than throwing`() {
        val api = TerminalTabPluginAPIImpl()

        assertFalse(
            api.renameTab("no-such-window", "no-such-terminal", "no-such-tab", "docker (plugin)"),
            "a rename that found no terminal must report failure, not success",
        )
    }

    @Test
    fun `a blank title is accepted, since blank is how a caller clears one`() {
        // Not rejected as invalid input: empty is the value BossTerm's cwd-title wiring already
        // reads as "no custom title", so passing it through is what hands the tab back to naming
        // itself. Still false here because there is no terminal to find.
        val api = TerminalTabPluginAPIImpl()

        assertFalse(api.renameTab("no-such-window", "no-such-terminal", "no-such-tab", ""))
    }

    @Test
    fun `a plugin written before renameTab existed still satisfies the interface`() {
        // The method is new rather than a title parameter on createTab, so nothing that already
        // implements this interface has to change. The default answers false: not supported.
        val api: TerminalTabPluginAPI = OldImplementor()

        assertFalse(
            api.renameTab("w", "t", "tab", "docker (plugin)"),
            "an implementation without renameTab must report unsupported, not claim success",
        )
    }

    @Test
    fun `the title reaches an implementor that takes it`() {
        val api: TerminalTabPluginAPI = RecordingImplementor()

        assertTrue(api.renameTab("w", "t", "tab-1", "kubernetes (plugin)"))
        assertEquals(listOf("tab-1" to "kubernetes (plugin)"), (api as RecordingImplementor).renames)
    }

    /** A plugin written against the interface before `renameTab` existed. */
    private class OldImplementor : TerminalTabPluginAPI {
        override val resetGeneration: StateFlow<Int> = MutableStateFlow(0)

        override fun hasTerminalState(windowId: String, terminalId: String): Boolean = false
        override fun removeTerminalState(windowId: String, terminalId: String): Unit = Unit
        override fun removeAllForWindow(windowId: String): Int = 0
        override fun resetAllTerminals(): Int = 0
        override fun sendCommand(windowId: String, terminalId: String, command: String): Boolean = false
        override fun sendInterrupt(windowId: String, terminalId: String): Boolean = false
        override fun sendInput(windowId: String, terminalId: String, bytes: ByteArray): Boolean = false
        override fun closeActiveTab(windowId: String, terminalId: String): Boolean = false
        override fun newSidebarTab(windowId: String, command: String, workingDirectory: String?, configId: String?, isRerun: Boolean): Boolean = false
        override fun registerSidebarTabId(windowId: String, configId: String, tabId: String): Unit = Unit
        override fun removeSidebarConfigTracking(windowId: String, configId: String): Unit = Unit
        override fun clearSidebarConfigTrackingForWindow(windowId: String): Unit = Unit
        override fun getConfigIdForSidebarTab(windowId: String, tabId: String): String? = null
        override fun setPendingSidebarCommand(windowId: String, command: String, workingDirectory: String?, configId: String?): Unit = Unit
        override fun consumePendingSidebarCommand(windowId: String): PendingSidebarCommand? = null

        // The interface's three @Composable members are abstract, so a fake has to carry them.
        @Composable
        override fun TerminalContent(
            terminalId: String?,
            initialCommand: String?,
            workingDirectory: String?,
            onExit: () -> Unit,
        ) = Unit

        @Composable
        override fun TabbedTerminalContent(
            workingDirectory: String?,
            onExit: () -> Unit,
            onShowSettings: () -> Unit,
        ) = Unit

        @Composable
        override fun PersistentTabbedTerminalContent(
            terminalId: String,
            initialCommand: String?,
            workingDirectory: String?,
            onExit: () -> Unit,
            onShowSettings: () -> Unit,
            onTitleChange: ((String) -> Unit)?,
            onLinkClick: ((String, String) -> Boolean)?,
        ) = Unit

    }

    /** A plugin that implements it. */
    private class RecordingImplementor : TerminalTabPluginAPI {
        val renames = mutableListOf<Pair<String, String>>()

        override fun renameTab(windowId: String, terminalId: String, tabId: String, title: String): Boolean {
            renames += tabId to title
            return true
        }

        override val resetGeneration: StateFlow<Int> = MutableStateFlow(0)

        override fun hasTerminalState(windowId: String, terminalId: String): Boolean = false
        override fun removeTerminalState(windowId: String, terminalId: String): Unit = Unit
        override fun removeAllForWindow(windowId: String): Int = 0
        override fun resetAllTerminals(): Int = 0
        override fun sendCommand(windowId: String, terminalId: String, command: String): Boolean = false
        override fun sendInterrupt(windowId: String, terminalId: String): Boolean = false
        override fun sendInput(windowId: String, terminalId: String, bytes: ByteArray): Boolean = false
        override fun closeActiveTab(windowId: String, terminalId: String): Boolean = false
        override fun newSidebarTab(windowId: String, command: String, workingDirectory: String?, configId: String?, isRerun: Boolean): Boolean = false
        override fun registerSidebarTabId(windowId: String, configId: String, tabId: String): Unit = Unit
        override fun removeSidebarConfigTracking(windowId: String, configId: String): Unit = Unit
        override fun clearSidebarConfigTrackingForWindow(windowId: String): Unit = Unit
        override fun getConfigIdForSidebarTab(windowId: String, tabId: String): String? = null
        override fun setPendingSidebarCommand(windowId: String, command: String, workingDirectory: String?, configId: String?): Unit = Unit
        override fun consumePendingSidebarCommand(windowId: String): PendingSidebarCommand? = null

        // The interface's three @Composable members are abstract, so a fake has to carry them.
        @Composable
        override fun TerminalContent(
            terminalId: String?,
            initialCommand: String?,
            workingDirectory: String?,
            onExit: () -> Unit,
        ) = Unit

        @Composable
        override fun TabbedTerminalContent(
            workingDirectory: String?,
            onExit: () -> Unit,
            onShowSettings: () -> Unit,
        ) = Unit

        @Composable
        override fun PersistentTabbedTerminalContent(
            terminalId: String,
            initialCommand: String?,
            workingDirectory: String?,
            onExit: () -> Unit,
            onShowSettings: () -> Unit,
            onTitleChange: ((String) -> Unit)?,
            onLinkClick: ((String, String) -> Boolean)?,
        ) = Unit

    }
}
