package ai.rever.boss.plugin.dynamic.terminaltab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TitleBarTerminalSelectionTest {
    @Test
    fun `share retains explicit focus across browser and toolbar use`() {
        val selection = TitleBarTerminalSelection()
        assertNull(selection.selected("window"))
        selection.focus("window", "main")
        selection.focus("window", "sidebar")
        assertEquals("sidebar", selection.selected("window"))
        selection.remove("window", "main")
        assertEquals("sidebar", selection.selected("window"))
        selection.remove("window", "sidebar")
        assertNull(selection.selected("window"))
    }

    @Test
    fun `window cleanup never changes another windows share target`() {
        val selection = TitleBarTerminalSelection()
        selection.focus("one", "terminal")
        selection.focus("two", "terminal")
        selection.removeWindow("one")
        assertNull(selection.selected("one"))
        assertEquals("terminal", selection.selected("two"))
        selection.clear()
        assertNull(selection.selected("two"))
    }
}
