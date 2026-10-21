package ai.rever.boss.plugin.dynamic.terminaltab

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class HostTabIdAliasTest {
    private val hostId = "terminal-1792501900063-94ef38779f807f67"

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
    fun `a host id or its bare form finds that host terminal's state`() {
        val state = assertNotNull(TabbedTerminalStateRegistry.getOrCreate("window-1", hostId))
        TabbedTerminalStateRegistry.getOrCreate("window-1", "terminal-other")
        assertSame(state, TabbedTerminalStateRegistry.stateForHost(hostId))
        assertSame(state, TabbedTerminalStateRegistry.stateForHost(hostId.removePrefix("terminal-")))
    }

    @Test
    fun `an unknown or partial host id finds nothing`() {
        TabbedTerminalStateRegistry.getOrCreate("window-1", hostId)
        assertNull(TabbedTerminalStateRegistry.stateForHost("terminal-unknown"))
        assertNull(TabbedTerminalStateRegistry.stateForHost("94ef38779f807f67"))
        assertNull(TabbedTerminalStateRegistry.activeTabIdForHost("terminal-unknown"))
    }
}
