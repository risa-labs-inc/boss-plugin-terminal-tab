package ai.rever.boss.plugin.dynamic.terminaltab

/** Explicit focus history: losing the selected terminal never selects another session implicitly. */
internal class TitleBarTerminalSelection {
    private val selected = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun focus(windowId: String, terminalId: String) { selected[windowId] = terminalId }
    fun selected(windowId: String): String? = selected[windowId]
    fun remove(windowId: String, terminalId: String) { selected.remove(windowId, terminalId) }
    fun removeWindow(windowId: String) { selected.remove(windowId) }
    fun clear() { selected.clear() }
}
