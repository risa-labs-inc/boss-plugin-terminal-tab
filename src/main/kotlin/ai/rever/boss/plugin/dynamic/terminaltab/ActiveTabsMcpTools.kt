package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MCP tools exposing BossConsole's [ActiveTabsProvider] — every tab (terminal,
 * browser, editor, ...) across every split of the main window, tagged with
 * which panel/split it lives in. This complements the BossTerm-built-in
 * `list_tabs`/`get_active_tab` tools, which only ever see terminal tabs and
 * carry no panel/split information.
 *
 * Registered in [TerminalTabDynamicPlugin.register] via
 * `context.registerMcpToolProvider(...)`, so the tools appear on the `boss`
 * MCP server while this plugin is active and are removed automatically when
 * it is disabled/unloaded.
 *
 * [activeTabsProviderSupplier] is a supplier rather than a resolved value:
 * `PluginContext.activeTabsProvider` is `by lazy` and may not be warm the
 * instant `register()` runs, so each call re-resolves it fresh (cheap once
 * warm, and self-healing if it wasn't ready earlier).
 *
 * Scope limitation: `activeTabsProvider` is bound to BossConsole's first/main
 * window only — tabs in any additional top-level BossConsole window are not
 * visible here. Called out explicitly in `list_active_tabs`'s description so
 * an agent doesn't assume false completeness.
 */
internal class ActiveTabsMcpToolProvider(
    override val providerId: String,
    private val activeTabsProviderSupplier: () -> ActiveTabsProvider?,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "list_active_tabs",
            description = "List every active tab (terminal, browser, editor, etc.) across all " +
                "split panels of BossConsole's main window, including which panel/split each " +
                "lives in — unlike list_tabs/get_active_tab (terminal-only, no panel/split info). " +
                "Use this to find a non-terminal tab (e.g. a browser tab) before calling " +
                "focus_active_tab. Only covers BossConsole's main window: tabs in an additional " +
                "top-level BossConsole window, if any is open, are not visible here.",
            handler = McpToolHandler { listActiveTabs() },
        ),
        McpToolDefinition(
            name = "focus_active_tab",
            description = "Focus/select a tab found via list_active_tabs, switching to it in its " +
                "panel. panel_id is optional — if omitted, it's looked up from the current active " +
                "tab list.",
            inputSchema = FOCUS_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args -> focusActiveTab(args) },
        ),
    )

    private suspend fun listActiveTabs(): McpToolResult {
        val provider = activeTabsProviderSupplier() ?: return unavailable()
        provider.refreshTabs()
        val tabs = provider.activeTabs.value
        if (tabs.isEmpty()) return McpToolResult("No active tabs.")
        val body = tabs.joinToString("\n") { tab ->
            buildString {
                append("tabId=${tab.tabId} typeId=${tab.typeId} title=\"${tab.title}\" ")
                append("panelId=${tab.panelId} windowId=${tab.windowId}")
                tab.splitPosition?.let { append(" splitPosition=$it") }
                tab.url?.let { append(" url=$it") }
            }
        }
        return McpToolResult(body)
    }

    private suspend fun focusActiveTab(args: McpToolArgs): McpToolResult {
        val provider = activeTabsProviderSupplier() ?: return unavailable()
        val tabId = args.string("tab_id")
            ?: return McpToolResult("Missing required argument: tab_id", isError = true)

        val panelId = args.string("panel_id") ?: run {
            provider.refreshTabs()
            provider.activeTabs.value.find { it.tabId == tabId }?.panelId
        } ?: return McpToolResult(
            "Could not resolve panel_id for tab $tabId; pass panel_id explicitly or re-run " +
                "list_active_tabs to confirm the tab still exists.",
            isError = true,
        )

        withContext(Dispatchers.Main) {
            provider.selectTab(tabId, panelId)
        }
        return McpToolResult("Focused tab $tabId in panel $panelId.")
    }

    private fun unavailable(): McpToolResult =
        McpToolResult("Active tabs provider unavailable in this context.", isError = true)

    private companion object {
        const val FOCUS_SCHEMA = """{"type":"object","properties":{"tab_id":{"type":"string","description":"Tab id from list_active_tabs."},"panel_id":{"type":"string","description":"Panel id from list_active_tabs. Optional — looked up automatically if omitted."}},"required":["tab_id"]}"""
    }
}
