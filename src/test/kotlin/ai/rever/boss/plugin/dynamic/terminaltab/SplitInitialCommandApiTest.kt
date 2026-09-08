package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.PendingSidebarCommand
import ai.rever.boss.plugin.api.TerminalTabPluginAPI
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `initialCommand` overloads on `splitVertical` / `splitHorizontal`, and the compatibility
 * shape they were given.
 *
 * Splitting and then writing races the PTY spawn: the session is still connecting when the write
 * lands, `writeToFocusedPane` reports true anyway, and the command is silently lost. BossTerm's
 * own `splitVertical(tabId, ratio, initialCommand, preserveFocus)` already holds a command until
 * the shell signals readiness; the plugin API called `splitVertical(tabId)` and dropped the rest.
 *
 * The API grew an OVERLOAD rather than a fourth parameter on the existing form, and these tests
 * are mostly about that decision holding. A defaulted parameter compiles into a synthetic
 * `$default` bridge whose signature carries every parameter, so widening the three-argument form
 * would change `splitVertical$default` and every already-compiled plugin calling it would meet a
 * `NoSuchMethodError` - which the host's `BinaryCompatibilityValidator` turns into the whole
 * plugin being disabled, not the one call failing.
 */
class SplitInitialCommandApiTest {
    /** A plugin written against the API as it was before the overloads existed. */
    private class OldImplementor : TerminalTabPluginAPI {
        var threeArgCalls = 0

        override fun splitVertical(
            windowId: String,
            terminalId: String,
            tabId: String?,
        ): String? {
            threeArgCalls++
            return "old-vertical"
        }

        override fun splitHorizontal(
            windowId: String,
            terminalId: String,
            tabId: String?,
        ): String? {
            threeArgCalls++
            return "old-horizontal"
        }
        // The interface's three @Composable members are abstract, so a fake has to carry them.
        // They are never composed here; only the split methods are under test.
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

        // The rest of the interface, stubbed. None of it is exercised here; the split
        // methods above are what these fakes exist for.
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
    }

    /** A plugin written against the API as it is now. */
    private class NewImplementor : TerminalTabPluginAPI {
        val commands = mutableListOf<String?>()

        override fun splitVertical(
            windowId: String,
            terminalId: String,
            tabId: String?,
            initialCommand: String?,
        ): String? {
            commands += initialCommand
            return "new-vertical"
        }
        // The interface's three @Composable members are abstract, so a fake has to carry them.
        // They are never composed here; only the split methods are under test.
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

        // The rest of the interface, stubbed. None of it is exercised here; the split
        // methods above are what these fakes exist for.
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
    }

    @Test
    fun `a plugin written before the overloads still satisfies the interface`() {
        // The whole point of the overload: this class compiles unchanged, and its existing calls
        // keep resolving to the method it actually implements.
        val api: TerminalTabPluginAPI = OldImplementor()

        assertEquals("old-vertical", api.splitVertical("w", "t", null))
        assertEquals("old-horizontal", api.splitHorizontal("w", "t", null))
    }

    @Test
    fun `the four-argument form reports unsupported rather than splitting without the command`() {
        // An implementor that has not adopted this yet must NOT silently split and drop the
        // command - that is the failure being fixed. Null lets the caller decide; delegating to
        // the three-argument form would hand back a pane that looks right and runs nothing.
        val api: TerminalTabPluginAPI = OldImplementor()

        assertNull(api.splitVertical("w", "t", null, "echo hi"))
        assertNull(api.splitHorizontal("w", "t", null, "echo hi"))
        assertEquals(0, (api as OldImplementor).threeArgCalls, "the four-argument form must not fall through")
    }

    @Test
    fun `a three-argument call still resolves to the three-argument method`() {
        // Guards the overload against a later "tidy-up" that gives `initialCommand` a default:
        // that would make every three-argument call ambiguous, and this stops compiling rather
        // than silently changing which method a caller reaches.
        val old = OldImplementor()

        old.splitVertical("w", "t", null)
        old.splitVertical("w", "t")

        assertEquals(2, old.threeArgCalls, "a three-argument call should not reach the overload")
    }

    @Test
    fun `the command reaches an implementor that takes it`() {
        val api: TerminalTabPluginAPI = NewImplementor()

        assertEquals("new-vertical", api.splitVertical("w", "t", null, "npm run dev"))
        assertEquals(listOf<String?>("npm run dev"), (api as NewImplementor).commands)
    }

    @Test
    fun `the real implementation answers both arities without a registered terminal`() {
        // No terminal is registered in a test JVM, so both must report failure the same way. What
        // this actually guards is that the three-argument form still has a body: it now delegates
        // to the four-argument one, and a mistake there would recurse rather than return.
        val api = TerminalTabPluginAPIImpl()

        assertNull(api.splitVertical("no-such-window", "no-such-terminal", null))
        assertNull(api.splitVertical("no-such-window", "no-such-terminal", null, "echo hi"))
        assertNull(api.splitHorizontal("no-such-window", "no-such-terminal", null))
        assertNull(api.splitHorizontal("no-such-window", "no-such-terminal", null, "echo hi"))
    }
}
