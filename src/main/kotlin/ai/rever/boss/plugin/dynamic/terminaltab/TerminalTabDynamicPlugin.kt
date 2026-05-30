package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

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
     * BossConsole hosts pin the JVM-wide `pty4j.preferred.native.folder` and
     * pre-extract `libpty` into it from the *host* classpath. Now that the
     * terminal — and pty4j — live inside this plugin (not the host), that
     * folder is empty on hosts that no longer carry pty4j, so pty4j loads its
     * native *only* from the pinned (empty) folder and ignores the `libpty`
     * bundled in THIS plugin's JAR → every shell spawn fails with "Failed to
     * spawn process".
     *
     * This plugin always carries its own pty4j native, so clearing the pin is
     * always correct: pty4j then self-extracts the native from this plugin's
     * JAR (its default behaviour), which works on every host — including the
     * release `.app`, where the host-provided folder is merely redundant.
     *
     * We clear unconditionally rather than probing the folder: pty4j's pinned
     * lookup uses a `<folder>/<platform>` layout that's easy to mis-check
     * (e.g. a sibling `pty4j-darwin/` left by a previous self-extraction can
     * make the folder look populated when the platform subdir is empty).
     * Runs at plugin load, before any terminal tab — and thus any PTY — exists.
     */
    private fun neutralizeStalePty4jNativeFolder() {
        try {
            if (System.getProperty("pty4j.preferred.native.folder") != null) {
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
