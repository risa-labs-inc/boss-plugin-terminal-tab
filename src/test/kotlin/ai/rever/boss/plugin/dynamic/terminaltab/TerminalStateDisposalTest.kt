package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalStateDisposalTest {
    @AfterTest
    fun cleanup() {
        disposeRetainedTerminalStates(null, lastWindow = true)
    }

    @Test
    fun `disposing one window removes both terminal types and preserves the other window`() {
        val originalMcpCount = McpTerminalRegistry.stateCount()
        listOf("closing", "surviving").forEach { window ->
            TabbedTerminalStateRegistry.getOrCreate(window, "tab")
            TerminalStateRegistry.getOrCreate(window, "embed")
        }
        disposeRetainedTerminalStates("closing", lastWindow = false)
        assertFalse(TabbedTerminalStateRegistry.contains("closing", "tab"))
        assertFalse(TerminalStateRegistry.contains("closing", "embed"))
        assertTrue(TabbedTerminalStateRegistry.contains("surviving", "tab"))
        assertTrue(TerminalStateRegistry.contains("surviving", "embed"))
        assertEquals(originalMcpCount + 1, McpTerminalRegistry.stateCount())
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
}
