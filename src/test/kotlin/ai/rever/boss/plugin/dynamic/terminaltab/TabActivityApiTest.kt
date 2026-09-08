package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.PendingSidebarCommand
import ai.rever.boss.plugin.api.TerminalTabActivity
import ai.rever.boss.plugin.api.TerminalTabPluginAPI
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `tabActivity`, and the tab-targeted `sendCommand` / `sendInterrupt` beside it.
 *
 * A plugin delivers a command by writing to a tab's pty, so with a foreground process running the
 * text becomes that process's stdin - not queued, never executed - and the write still reports
 * success. With nothing to ask, consumers interrupt and then sleep, and no delay is long enough
 * for a process that traps SIGINT.
 *
 * Almost every test here is about one property: **UNKNOWN is not IDLE**. It is the answer whenever
 * the shell is not reporting command boundaries, and a caller that reads it as "free" has
 * reintroduced the bug on every machine without shell integration - which is the majority case
 * this has to be safe for.
 */
class TabActivityApiTest {
    @Test
    fun `an unknown terminal is UNKNOWN, never IDLE`() {
        val api = TerminalTabPluginAPIImpl()

        val activity = api.tabActivity("no-such-window", "no-such-terminal", null)

        assertEquals(TerminalTabActivity.UNKNOWN, activity)
        assertNotEquals(
            TerminalTabActivity.IDLE,
            activity,
            "reporting IDLE for a terminal we could not even find is how a command ends up in a running process",
        )
    }

    @Test
    fun `the interface default is UNKNOWN, so an unadopted implementation cannot claim a tab is free`() {
        val api: TerminalTabPluginAPI = OldImplementor()

        assertEquals(
            TerminalTabActivity.UNKNOWN,
            api.tabActivity("w", "t", null),
            "the default must be the answer that keeps a caller on its fallback",
        )
    }

    @Test
    fun `every state is distinct, and UNKNOWN is its own answer`() {
        // Guards a later "simplification" to a Boolean, which would have to fold UNKNOWN into one
        // of the other two - IDLE reintroduces the bug, BUSY defeats the tab reuse this exists to
        // enable. Three states is the point, not an accident.
        assertEquals(3, TerminalTabActivity.entries.size)
        assertEquals(
            setOf(TerminalTabActivity.IDLE, TerminalTabActivity.BUSY, TerminalTabActivity.UNKNOWN),
            TerminalTabActivity.entries.toSet(),
        )
    }

    @Test
    fun `tab-targeted send reports a miss instead of writing somewhere else`() {
        // The active-tab forms cannot tell "wrote it" from "wrote it to the wrong tab", which is
        // why a consumer had to switchToTab first. These answer for the tab they were given.
        val api = TerminalTabPluginAPIImpl()

        assertFalse(api.sendCommand("no-such-window", "no-such-terminal", "echo hi", "no-such-tab"))
        assertFalse(api.sendInterrupt("no-such-window", "no-such-terminal", "no-such-tab"))
    }

    @Test
    fun `a null tabId means the active tab, so the overload is a superset`() {
        // Not a second mechanism beside the three-argument form: null routes to it, so the two
        // cannot drift.
        val api = TerminalTabPluginAPIImpl()

        assertFalse(api.sendCommand("no-such-window", "no-such-terminal", "echo hi", null))
        assertFalse(api.sendInterrupt("no-such-window", "no-such-terminal", null))
    }

    @Test
    fun `the reported activity reaches an implementor that answers it`() {
        val api: TerminalTabPluginAPI = BusyImplementor()

        assertEquals(TerminalTabActivity.BUSY, api.tabActivity("w", "t", "tab-1"))
    }

    @Test
    fun `a plugin written before any of this still satisfies the interface`() {
        val api: TerminalTabPluginAPI = OldImplementor()

        assertFalse(api.sendCommand("w", "t", "echo hi", "tab-1"))
        assertFalse(api.sendInterrupt("w", "t", "tab-1"))
        assertTrue(api.tabActivity("w", "t", "tab-1") == TerminalTabActivity.UNKNOWN)
    }

    @Test
    fun `the activity flow reports UNKNOWN once rather than hanging a collector`() = runBlocking {
        // The failure to avoid is a caller writing `first { it == IDLE }` against a tab that will
        // never report anything, and waiting forever. An unobservable tab emits UNKNOWN and
        // completes, so that collector terminates and the caller can fall back.
        val api = TerminalTabPluginAPIImpl()

        val emitted = api.tabActivityFlow("no-such-window", "no-such-terminal", null).toList()

        assertEquals(listOf(TerminalTabActivity.UNKNOWN), emitted)
    }

    @Test
    fun `the interface default flow also completes with UNKNOWN`() = runBlocking {
        val api: TerminalTabPluginAPI = OldImplementor()

        assertEquals(
            listOf(TerminalTabActivity.UNKNOWN),
            api.tabActivityFlow("w", "t", null).toList(),
            "an unadopted implementation must complete, not leave a collector waiting",
        )
    }

    /** A plugin written against the interface before these were added. */
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

    /** A plugin that answers the predicate. */
    private class BusyImplementor : TerminalTabPluginAPI {
        override fun tabActivity(windowId: String, terminalId: String, tabId: String?) =
            TerminalTabActivity.BUSY

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
