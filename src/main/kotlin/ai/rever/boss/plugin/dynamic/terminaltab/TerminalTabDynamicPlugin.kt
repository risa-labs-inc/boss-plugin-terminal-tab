package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import java.io.File

/**
 * Terminal Tab dynamic plugin - Loaded from external JAR.
 *
 * Provides terminal tabs in the main panel area using BossTerm library.
 * Each tab has its own persistent terminal session.
 *
 * This plugin is self-contained: it owns all terminal rendering and state
 * management logic, and exposes TerminalTabPluginAPI via registerPluginAPI()
 * so the host (TerminalAPIAccess) and other plugins (terminal panel) can
 * consume terminal functionality through the plugin system.
 *
 * NOTE: This is a main panel TAB plugin, not a sidebar panel.
 * It registers as a TabType via tabRegistry.registerTabType().
 */
class TerminalTabDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.terminaltab"
    override val displayName: String = "Terminal Tab"
    override val version: String = "1.0.11"
    override val description: String = "Terminal tab using BossTerm library for terminal emulation"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-terminal-tab"

    private var pluginContext: PluginContext? = null
    private var terminalApi: TerminalTabPluginAPIImpl? = null

    override fun register(context: PluginContext) {
        // Must run before any terminal tab (and thus any pty4j spawn) is created.
        neutralizeStalePty4jNativeFolder()

        pluginContext = context

        // Create and register the terminal API implementation
        terminalApi = TerminalTabPluginAPIImpl(context)
        context.registerPluginAPI(terminalApi!!)

        // Register as a main panel TAB TYPE (not a sidebar panel!)
        context.tabRegistry.registerTabType(TerminalTabType) { tabInfo, ctx ->
            TerminalTabComponent(ctx, tabInfo, context)
        }
    }

    /**
     * BossConsole hosts set the JVM-wide `pty4j.preferred.native.folder` system
     * property and try to pre-extract `libpty` into it from the host classpath.
     * Since the terminal — and pty4j — now live inside this plugin (not the
     * host), that extraction finds nothing and the folder stays empty. pty4j
     * then loads its native *only* from that pinned folder and ignores the
     * `libpty` bundled in THIS plugin's JAR, so every shell spawn fails with
     * "Failed to spawn process".
     *
     * If the pinned folder has no usable `libpty`, clear the property so pty4j
     * falls back to extracting the native from this plugin's JAR (its default
     * behaviour). The guard leaves correctly-populated folders (e.g. a signed
     * `.app` bundle) untouched. Runs at plugin load, before any PTY is spawned.
     */
    private fun neutralizeStalePty4jNativeFolder() {
        try {
            val pref = System.getProperty("pty4j.preferred.native.folder") ?: return
            val hasLib = File(pref).walkTopDown().any { it.name.startsWith("libpty.") }
            if (!hasLib) {
                System.clearProperty("pty4j.preferred.native.folder")
            }
        } catch (_: Throwable) {
            // Best-effort: never let native-path housekeeping block plugin load.
        }
    }

    override fun dispose() {
        // Unregister tab type when plugin is unloaded
        pluginContext?.tabRegistry?.unregisterTabType(TerminalTabType.typeId)
        terminalApi = null
        pluginContext = null
    }
}
