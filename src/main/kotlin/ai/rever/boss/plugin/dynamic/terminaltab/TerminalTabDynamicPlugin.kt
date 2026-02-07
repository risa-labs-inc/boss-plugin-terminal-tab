package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Terminal Tab dynamic plugin - Loaded from external JAR.
 *
 * Provides terminal tabs in the main panel area using BossTerm library.
 * Each tab has its own persistent terminal session.
 *
 * NOTE: This is a main panel TAB plugin, not a sidebar panel.
 * It registers as a TabType via tabRegistry.registerTabType().
 */
class TerminalTabDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.terminaltab"
    override val displayName: String = "Terminal Tab"
    override val version: String = "1.0.2"
    override val description: String = "Terminal tab using BossTerm library for terminal emulation"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-terminal-tab"

    private var pluginContext: PluginContext? = null

    override fun register(context: PluginContext) {
        pluginContext = context

        // Register as a main panel TAB TYPE (not a sidebar panel!)
        context.tabRegistry.registerTabType(TerminalTabType) { tabInfo, ctx ->
            TerminalTabComponent(ctx, tabInfo, context)
        }
    }

    override fun dispose() {
        // Unregister tab type when plugin is unloaded
        pluginContext?.tabRegistry?.unregisterTabType(TerminalTabType.typeId)
        pluginContext = null
    }
}
