package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.NotificationDuration
import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.bossterm.compose.mcp.BossTermMcpConfig
import ai.rever.bossterm.compose.mcp.BossTermMcpManager
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.share.SessionShareManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

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

    // MCP server control surface (Plugin Manager MCP tab). Held so dispose()
    // can cancel its state collectors — mcpScope itself deliberately outlives
    // dispose for the async engine shutdown.
    private var mcpServerController: McpServerControllerImpl? = null

    // Host toasts for session-sharing approval requests: requestId → toastId,
    // so resolved/expired requests dismiss their toast.
    private val approvalToastIds = ConcurrentHashMap<String, String>()

    companion object {
        /**
         * Session-sharing server port for the BossConsole profile. BossTerm's
         * own default is 7677, which this plugin's MCP server already binds in
         * BossConsole (sharing would auto-fall back to 7678, but a distinct,
         * deterministic default keeps the advertised URL stable).
         */
        private const val SHARE_PORT_BOSSCONSOLE = 7700

        /**
         * How long `dispose()` will block waiting for the BossTerm MCP engine to
         * finish stopping.
         *
         * Sized off the engine's own hard ceiling rather than picked round:
         * `BossTermMcpManager` stops with `engine.stop(grace = 500ms,
         * timeout = 1500ms)`, so Ktor itself gives up after 1.5s. 3s is 2x that
         * ceiling — enough headroom for the mutex acquisition, the streamable
         * session close and the port-marker delete that follow it, while staying
         * short enough that a wedged shutdown can't turn a plugin reload (or app
         * quit) into a visibly hung app. Overshooting the budget is logged at
         * ERROR, never swallowed.
         */
        private const val MCP_TEARDOWN_TIMEOUT_MS = 3_000L
    }

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

        // MCP server control surface (on/off + CLI attach) for management UIs
        // like the Plugin Manager's MCP tab. Guarded: a failure here must never
        // prevent terminal tabs from working.
        try {
            mcpServerController = McpServerControllerImpl(mcpScope).also { context.registerPluginAPI(it) }
        } catch (t: Throwable) {
            mcpLogger.warn(LogCategory.TERMINAL, "Failed to register McpServerController", error = t)
        }

        // Register as a main panel TAB TYPE (not a sidebar panel!)
        context.tabRegistry.registerTabType(TerminalTabType) { tabInfo, ctx ->
            TerminalTabComponent(ctx, tabInfo, context)
        }

        startMcpServer()

        startSessionSharing(context)
    }

    /**
     * Bring up BossTerm 1.2.104's session sharing (self-hosted web viewer with
     * device approval). The share UI (tab right-click "Share Tab…/Share
     * Window…", dialog, status pill) is built into TabbedTerminal; this just
     * arms the lifecycle:
     *  - first-launch defaults for the BossConsole profile (fresh settings file
     *    only — upgrades never clobber user choices): port 7700 instead of
     *    BossTerm's 7677 (taken by this plugin's MCP server), and remote mode
     *    "off" instead of BossTerm's "cloudflare" (no public tunnel unless the
     *    user opts in — sharing stays LAN-only by default).
     *  - [SessionShareManager.start] (idempotent; bossterm-app does the same in
     *    its main()).
     *  - approval requests surfaced as host toasts (BossTerm also fires an OS
     *    notification + in-terminal banner; the toast adds an in-app one-tap
     *    Approve. Deny remains available in the in-terminal banner).
     *
     * Wrapped in a catch-all: sharing failing to start must never prevent
     * terminal tabs from working.
     */
    private fun startSessionSharing(context: PluginContext) {
        try {
            applySessionSharingFirstLaunchDefaults()
            SessionShareManager.start()
            wireApprovalNotifications(context)
            mcpLogger.info(LogCategory.TERMINAL, "Session sharing armed", mapOf(
                "port" to SettingsManager.instance.settings.value.sessionSharingPort
            ))
        } catch (t: Throwable) {
            mcpLogger.warn(LogCategory.TERMINAL, "Failed to start session sharing; terminals still work", error = t)
        }
    }

    /**
     * One-shot defaults for a brand-new BossConsole settings file. Gated on
     * [SettingsManager.wasFreshInstall] (latched when no settings.json existed
     * at load) so an existing user's hand-picked port/exposure is never
     * overwritten on plugin upgrade.
     */
    private fun applySessionSharingFirstLaunchDefaults() {
        val sm = SettingsManager.instance
        if (!sm.wasFreshInstall) return
        sm.updateSetting {
            copy(
                sessionSharingPort = SHARE_PORT_BOSSCONSOLE,
                shareTailscaleMode = "off"
                // sessionSharingEnabled stays false (opt-in master toggle) and
                // sessionSharingApprovalScope stays "funnel" (approval required
                // for any public reach) — BossTerm's defaults match our policy.
            )
        }
    }

    /**
     * Mirror [SessionShareManager.pendingRequests] into host toasts: one
     * INDEFINITE toast per pending device with a one-tap Approve action;
     * dismissed automatically when the request resolves (approved/denied in
     * the in-terminal banner, or expired after BossTerm's 2-minute timeout).
     * Collected on [PluginContext.pluginScope], so plugin dispose cancels it.
     */
    private fun wireApprovalNotifications(context: PluginContext) {
        val notifications = context.notificationProvider ?: return
        context.pluginScope.launch {
            SessionShareManager.pendingRequests.collect { requests ->
                val live = requests.map { it.id }.toSet()
                approvalToastIds.keys.filter { it !in live }.forEach { requestId ->
                    approvalToastIds.remove(requestId)?.let { notifications.dismiss(it) }
                }
                requests.filter { !approvalToastIds.containsKey(it.id) }.forEach { request ->
                    val verb = if (request.wantsControl) "control of" else "to view"
                    val toastId = notifications.showToast(
                        message = "${request.deviceName} requests $verb your shared terminal",
                        type = NotificationType.WARNING,
                        duration = NotificationDuration.INDEFINITE,
                        title = "Terminal session sharing",
                        actionLabel = "Approve",
                        onAction = { SessionShareManager.approveRequest(request.id) }
                    )
                    approvalToastIds[request.id] = toastId
                }
            }
        }
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
            // Host registry of MCP tools contributed by other active plugins.
            // Bridged onto the live MCP server so each plugin's tools appear while
            // it is active and vanish when it is disabled/unloaded. See
            // McpDynamicTools.kt / McpToolRegistryImpl in the host.
            val toolRegistry = pluginContext?.mcpToolRegistry
            // Re-arm the bridge in case this is a re-registration after dispose().
            resumeDynamicPluginTools()
            val config = BossTermMcpConfig(
                serverName = "boss",
                displayName = "Boss",
                serverVersion = version,
                defaultEnabled = true,
                defaultPort = 7677,
                // Host-facing tools (run_in_sidebar, cli) that drive BossConsole's
                // sidebar/Runner and boss:// deep-link verbs over the same MCP
                // endpoint as the built-in terminal tools (see McpHostTools.kt),
                // plus the dynamic bridge for plugin-contributed tools.
                additionalTools = { server ->
                    bossHostMcpTools(server)
                    if (toolRegistry != null) {
                        installDynamicPluginTools(server, toolRegistry, mcpScope)
                    } else {
                        mcpLogger.warn(
                            LogCategory.TERMINAL,
                            "mcpToolRegistry unavailable; plugin-contributed MCP tools disabled"
                        )
                    }
                }
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

    /**
     * Every Ktor server this plugin owns must be fully stopped by the time this
     * method RETURNS — not "eventually".
     *
     * The host runs `instance.dispose()` and then, with nothing in between,
     * marks this plugin's classloader UNLOADING and closes it
     * (`DynamicPluginLoader.unloadPlugin`). A closed `URLClassLoader` can't
     * `findClass` any more, and `PluginClassLoader.loadClassChildFirst` answers
     * that failure by *silently* delegating to the host classloader — which
     * carries its own, different copy of Ktor (3.4.3, pulled in transitively by
     * supabase's `auth-kt`, while this plugin bundles bossterm's 3.2.3 server).
     * So a teardown that runs one millisecond too late doesn't just log; it
     * splices two Ktor object graphs together and dies on a
     * `LinkageError: loader constraint violation`, forfeiting whatever cleanup
     * came after — including the `engine.stop()` that releases the bound port.
     *
     * Both servers are handled, and they need different treatment:
     *  - [SessionShareManager.shutdown] is already synchronous on the caller's
     *    thread (BossTerm calls `engine.stop(200, 800)` inline). Nothing to fix,
     *    but see [verifyShareServerStopped] for the one gap it leaves.
     *  - [BossTermMcpManager.stop] is fire-and-forget **by contract** — it
     *    launches the engine shutdown on `parentScope` and returns immediately,
     *    so onDispose on the UI thread never blocks. `parentScope` is our
     *    [mcpScope], so we can (and must) wait for it here.
     *
     * Safe to call more than once: every step is null-guarded or idempotent,
     * and the second call finds an already-idle [mcpScope] and returns without
     * blocking.
     */
    override fun dispose() {
        // Stop session sharing (idempotent; tears down the share server and
        // tunnels) and clear any approval toasts still on screen. The
        // pendingRequests collector dies with pluginScope cancellation.
        stopSessionSharing()
        approvalToastIds.values.forEach { toastId ->
            runCatching { pluginContext?.notificationProvider?.dismiss(toastId) }
        }
        approvalToastIds.clear()

        // Cancel the two never-completing mcpScope children BEFORE the wait
        // below, not after: the plugin-tool sync collector and the server
        // controller's Eagerly-shared stateIn collectors run until cancelled, so
        // leaving them alive would burn the entire teardown budget waiting on
        // coroutines that were never going to finish.
        stopDynamicPluginTools()
        mcpServerController?.close()
        mcpServerController = null

        // Stop the MCP server, then WAIT for the Ktor engine to actually be down.
        try {
            mcpManager?.stop()
        } catch (t: Throwable) {
            mcpLogger.warn(LogCategory.TERMINAL, "Error stopping BossTerm MCP manager", error = t)
        }
        awaitMcpTeardown()

        mcpManager = null
        TerminalMcpConfigHolder.config = null

        // Now that real time has passed (the MCP engine stop above), re-check the
        // share server: BossTerm's shutdown() can be raced back into life.
        verifyShareServerStopped()

        // Unregister tab type when plugin is unloaded
        pluginContext?.tabRegistry?.unregisterTabType(TerminalTabType.typeId)
        terminalApi = null
        pluginContext = null
    }

    /** [SessionShareManager.shutdown] is synchronous and idempotent; never let it throw. */
    private fun stopSessionSharing() {
        try {
            SessionShareManager.shutdown()
        } catch (t: Throwable) {
            mcpLogger.warn(LogCategory.TERMINAL, "Error stopping session sharing", error = t)
        }
    }

    /**
     * Block until the MCP engine shutdown launched by [BossTermMcpManager.stop]
     * has finished, or [MCP_TEARDOWN_TIMEOUT_MS] elapses.
     *
     * On success [mcpScope] is cancelled: by definition nothing is in flight, so
     * cancelling can't abort a teardown — it only guarantees no late coroutine
     * can start on a scope whose classloader is about to close. On timeout we
     * deliberately do NOT cancel. The stop may still complete a moment later and
     * free the port; cancelling would make the leak certain, and cancellation
     * unwinding is itself a class-loading path we'd be starting too late.
     */
    private fun awaitMcpTeardown() {
        val finished = awaitScopeIdle(mcpScope, MCP_TEARDOWN_TIMEOUT_MS) { pendingJobs ->
            mcpLogger.error(
                LogCategory.TERMINAL,
                "BossTerm MCP engine did not finish stopping before dispose() returned; the host " +
                    "closes this plugin's classloader next, so the shutdown may fail mid-way and " +
                    "leak its bound port until the app restarts",
                mapOf(
                    "timeoutMs" to MCP_TEARDOWN_TIMEOUT_MS,
                    "pendingJobs" to pendingJobs,
                ),
            )
        }
        if (finished) mcpScope.cancel()
    }

    /**
     * BossTerm's [SessionShareManager.shutdown] stops the engine synchronously,
     * but it cancels only its settings watcher — not the manager's own private
     * CoroutineScope. An in-flight `prewarmRemote()` (which waits up to five
     * seconds for the MCP port to appear before binding) can therefore re-bind
     * the share server *after* shutdown returned, leaving a live Ktor engine and
     * a bound port attached to a classloader that is about to be closed.
     *
     * We can't cancel BossTerm's scope from out here, so this does the next best
     * thing: after the MCP wait above has burned real wall-clock time — exactly
     * the window a racing pre-warm would land in — re-read the manager's bound
     * port and, if something re-bound, stop it again. Narrowing, not closure: a
     * pre-warm that lands after `dispose()` returns is still unreachable from
     * here, which is why the real fix belongs in BossTerm's `shutdown()`.
     *
     * The bound port is read reflectively because BossTerm exposes no accessor
     * for it; this is diagnostics only, so a failed read degrades to today's
     * behaviour (one shutdown, no verification) with a warning rather than
     * changing what we tear down.
     */
    private fun verifyShareServerStopped() {
        val reBoundPort = shareServerBoundPort() ?: return
        mcpLogger.warn(
            LogCategory.TERMINAL,
            "Session-sharing server was re-bound after shutdown() returned; stopping it again",
            mapOf("port" to reBoundPort),
        )
        stopSessionSharing()
        shareServerBoundPort()?.let { port ->
            mcpLogger.error(
                LogCategory.TERMINAL,
                "Session-sharing server is STILL bound after a second shutdown(); this port and " +
                    "this plugin's classloader will leak until the app restarts",
                mapOf("port" to port),
            )
        }
    }

    /**
     * The port BossTerm's share server is currently bound to, or null when it is
     * stopped. `SessionShareManager.boundPort` is a private `@Volatile Int?`
     * field with no public accessor; same best-effort reflection style as
     * [McpServerControllerImpl]'s `markAttached`.
     */
    private fun shareServerBoundPort(): Int? =
        runCatching {
            SessionShareManager::class.java
                .getDeclaredField("boundPort")
                .apply { isAccessible = true }
                .get(SessionShareManager) as Int?
        }.onFailure {
            mcpLogger.warn(
                LogCategory.TERMINAL,
                "Could not read the session-sharing bound port; skipping post-shutdown verification",
                error = it,
            )
        }.getOrNull()
}
