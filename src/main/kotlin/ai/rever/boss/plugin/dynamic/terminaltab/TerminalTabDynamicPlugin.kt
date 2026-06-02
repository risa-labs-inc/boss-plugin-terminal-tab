package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.bossterm.compose.mcp.BossTermMcpConfig
import ai.rever.bossterm.compose.mcp.BossTermMcpManager
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val mcpLogger = BossLogger.forComponent("TerminalTabMcp")

/**
 * Process-wide holder for the single [BossTermMcpConfig] this plugin builds, so
 * the settings UI ([TerminalTabPluginAPIImpl.TerminalSettingsPanel]) can expose
 * the same instance via [ai.rever.bossterm.compose.mcp.LocalBossTermMcpConfig]
 * without threading it through the plugin API surface. Set in
 * [TerminalTabDynamicPlugin.register]; read from the Compose settings panel.
 */
internal object TerminalMcpConfigHolder {
    @Volatile
    var config: BossTermMcpConfig? = null
}

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

    // BossTerm MCP server lifecycle. Constructed once per JVM in register();
    // exposes every terminal tab (registered via TabbedTerminalStateRegistry →
    // McpTerminalRegistry) over a loopback MCP endpoint.
    private val mcpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mcpManager: BossTermMcpManager? = null

    override fun register(context: PluginContext) {
        // Relocate BossTerm's settings store off the shared ~/.bossterm BEFORE
        // anything touches SettingsManager.instance (it is a lazy singleton).
        // This gives BossConsole its own settings.json under the BOSS data root
        // (~/.boss, or ~/.boss_debug in dev mode) so its terminal settings — and
        // crucially its MCP mcpEnabled/mcpPort — are independent of a standalone
        // BossTerm app on the same machine. With a
        // fresh file the MCP config's defaultEnabled=true / defaultPort=7677
        // first-launch defaults apply, so BossConsole's MCP binds 7677 while
        // standalone keeps ~/.bossterm (7676). Honored via the relocation hook
        // in bossterm-compose's SettingsManager.
        relocateBossTermSettings()

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

        startMcpServer()
    }

    /**
     * Bring up the in-process BossTerm MCP server, branded `boss`. Per the
     * BossTerm MCP docs, `serverName` is the identifier the auto-attacher
     * registers with AI CLIs (`claude mcp add ... <serverName> <url>`), so it
     * becomes the client-side namespace — tools surface as `mcp__boss__<tool>`.
     * No `toolNamePrefix` is set, so the names stay bare (`list_tabs`,
     * `run_in_panel`, …) rather than `boss_list_tabs`. Standalone BossTerm keeps
     * its own `bossterm` identity, so the two never collide in a client config.
     *
     * Wrapped in a catch-all: a failure to start MCP (e.g. a missing transitive
     * dependency) must never prevent terminal tabs from working.
     */
    private fun startMcpServer() {
        try {
            val config = BossTermMcpConfig(
                serverName = "boss",
                displayName = "Boss",
                serverVersion = version,
                defaultEnabled = true,
                defaultPort = 7677,
                // Host-facing tools (run_in_sidebar, cli) that drive BossConsole's
                // sidebar/Runner and boss:// deep-link verbs over the same MCP
                // endpoint as the built-in terminal tools. See McpHostTools.kt.
                additionalTools = bossHostMcpTools
            )
            TerminalMcpConfigHolder.config = config
            mcpManager = BossTermMcpManager(
                registry = McpTerminalRegistry,
                settingsManager = SettingsManager.instance,
                parentScope = mcpScope,
                config = config
            ).also { it.start() }
            mcpLogger.info(LogCategory.TERMINAL, "BossTerm MCP manager started", mapOf(
                "serverName" to config.serverName,
                "defaultPort" to config.defaultPort
            ))
        } catch (t: Throwable) {
            mcpLogger.warn(LogCategory.TERMINAL, "Failed to start BossTerm MCP manager; terminals still work", error = t)
        }
    }

    /**
     * Point bossterm-compose's [SettingsManager] at BossConsole's own settings
     * directory under the BOSS data root (`~/.boss`, or `~/.boss_debug` in dev
     * mode) via the `bossterm.settings.dir` system property. Set-if-absent so an
     * explicit `-Dbossterm.settings.dir` override (or a prior set) wins. Must run
     * before the first `SettingsManager.instance` access — that singleton is
     * lazy, so register() is the right place.
     */
    private fun relocateBossTermSettings() {
        try {
            val key = "bossterm.settings.dir"
            if (System.getProperty(key).isNullOrBlank()) {
                System.setProperty(key, bossTermSettingsDir().absolutePath)
            }
        } catch (_: Throwable) {
            // Best-effort: never let settings relocation block plugin load.
        }
    }

    /**
     * `bossterm` settings directory under BossConsole's data root — `~/.boss` in
     * normal mode, `~/.boss_debug` in dev mode. Resolved via the host's
     * `BossDirectories` (the single source of truth) by reflection, since that
     * class lives in the host classloader rather than boss-plugin-api. Falls back
     * to the same dev-mode rule if the host class isn't reachable.
     */
    private fun bossTermSettingsDir(): java.io.File = try {
        val clazz = Class.forName("ai.rever.boss.plugin.pathutils.BossDirectories")
        val instance = clazz.getField("INSTANCE").get(null)
        clazz.getMethod("resolve", String::class.java).invoke(instance, "bossterm") as java.io.File
    } catch (_: Throwable) {
        val root = if (isBossDevMode()) ".boss_debug" else ".boss"
        java.io.File(java.io.File(System.getProperty("user.home"), root), "bossterm")
    }

    private fun isBossDevMode(): Boolean {
        fun truthy(v: String?) = v?.trim()?.lowercase()?.let { it == "true" || it == "1" || it == "yes" } ?: false
        return truthy(System.getProperty("boss.dev.mode")) || truthy(System.getenv("BOSS_DEV_MODE"))
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
        // Stop the MCP server. stop() is non-blocking and delegates the Ktor
        // engine shutdown to a coroutine on mcpScope, so we deliberately do NOT
        // cancel mcpScope here — cancelling it would abort the in-flight shutdown
        // and leak the bound port.
        try {
            mcpManager?.stop()
        } catch (t: Throwable) {
            mcpLogger.warn(LogCategory.TERMINAL, "Error stopping BossTerm MCP manager", error = t)
        }
        mcpManager = null
        TerminalMcpConfigHolder.config = null

        // Unregister tab type when plugin is unloaded
        pluginContext?.tabRegistry?.unregisterTabType(TerminalTabType.typeId)
        terminalApi = null
        pluginContext = null
    }
}
