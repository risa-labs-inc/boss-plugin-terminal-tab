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
import ai.rever.boss.plugin.dynamic.terminaltab.onboarding.BossTermSetupController
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.share.AccountAutoRemote
import ai.rever.bossterm.compose.share.AccountAutoShare
import ai.rever.bossterm.compose.share.AccountSessionDirectory
import ai.rever.bossterm.compose.share.AccountSessionPublisher
import ai.rever.bossterm.compose.share.AccountSessionSource
import ai.rever.bossterm.compose.share.AccountTerminalPreferences
import ai.rever.bossterm.compose.share.SessionShareManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private var accountBridge: HostAccountSessionBridge? = null
    private var accountPublisher: AccountSessionPublisher? = null

    /** Whether [HostMcpToolProvider] is registered; decides how [startMcpServer] wires the two host tools. */
    private var hostToolsViaRegistry: Boolean = false
    private var terminalApi: TerminalTabPluginAPIImpl? = null
    private var setupStatusJob: Job? = null

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

        // Keep the tab strip visible when the vertical sidebar is collapsed.
        // Only against the relocated store: without it SettingsManager would be
        // standalone BossTerm's ~/.bossterm, which is not ours to change.
        try {
            System.getProperty("bossterm.settings.dir")?.takeIf { it.isNotBlank() }?.let {
                applyCollapsedTabStripDefault(java.io.File(it), SettingsManager.instance)
            }
        } catch (t: Throwable) {
            mcpLogger.warn(LogCategory.TERMINAL, "Failed to apply collapsed tab strip default", error = t)
        }

        // Must run before any terminal tab (and thus any pty4j spawn) is created.
        neutralizeStalePty4jNativeFolder()

        // run_in_sidebar env files hold values in plaintext until a shell loads them. None of this
        // process's can still be pending now: every terminal that could load one belonged to an
        // earlier instance of this plugin and died with it. Dead processes' files go too; a live
        // other BOSS process's are left alone (see SidebarEnvInjection.sweepForLifecycle).
        sweepSidebarEnvFiles("start")

        pluginContext = context
        // Install before any BossTerm UI/default singleton can restore a standalone login.
        try {
            val bridge = HostAccountSessionBridge(
                context.authDataProvider,
                context.supabaseDataProvider,
                onFailure = { logAccountFailure("Account transition", it) },
                onCleanupExhausted = {
                    // The bridge is already disabled. Tear down on IO so bounded publisher
                    // cleanup never blocks its collector/Main, and stop serving links first.
                    context.pluginScope.launch(Dispatchers.IO) {
                        accountCleanup("Shut down sharing after cleanup failure") { SessionShareManager.shutdown() }
                        stopAccountServices()
                    }
                },
            ) {
                var failure: Throwable? = null
                suspend fun reset(name: String, action: suspend () -> Unit) {
                    try {
                        action()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        logAccountFailure(name, t)
                        if (failure == null) failure = t
                    }
                }
                reset("Refresh account identity") { AccountSessionSource.refreshHostIdentity() }
                reset("Reset account preferences") { AccountTerminalPreferences.Default.resetAccount() }
                reset("Reset auto sharing") { AccountAutoShare.Default.resetAccount() }
                reset("Revoke account shares") { SessionShareManager.revokeAccountShares() }
                reset("Reset account directory") { AccountSessionDirectory.Default.resetAccount() }
                reset("Disconnect account viewers") { AccountAutoRemote.Default.resetAccount() }
                failure?.let { throw it }
            }
            accountBridge = bridge
            AccountSessionSource.install(bridge, context.pluginScope)
            bridge.start(context.pluginScope)
        } catch (t: Throwable) {
            logAccountFailure("Initialize account sharing", t)
            accountCleanup("Close account bridge") { accountBridge?.close() }
            accountBridge = null
            accountCleanup("Disconnect account source") { AccountSessionSource.disconnect() }
        }
        val setupSupervisor = BossTermFluckSupervisor(context)
        TerminalPluginContextHolder.setupSupervisor = setupSupervisor
        val setupStatusItem = BossTermSetupStatusItem()
        setupStatusJob = context.pluginScope.launch {
            var registered = false
            BossTermSetupController.state.collect { state ->
                runCatching {
                    updateSetupStatusRegistration(
                        hasSession = state.sessionId != null,
                        isRegistered = registered,
                        register = { context.registerStatusBarItem(setupStatusItem) },
                        unregister = { context.unregisterStatusBarItem(BossTermSetupStatusItem.ITEM_ID) },
                    )
                }.onSuccess { registered = it }
                    .onFailure { error ->
                        mcpLogger.warn(LogCategory.TERMINAL, "Failed to update setup status item", error = error)
                    }
            }
        }

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

        // This plugin's own two tools go through the host registry when there is one, so an
        // agent reaches them through the same governance path as every other plugin's tools
        // (kill-switch, policy, approval, ledger, secret references - BossConsole#495). Only a
        // host without a registry gets the older direct-on-server registration, in
        // startMcpServer, so the tools never silently disappear on an old host.
        hostToolsViaRegistry = registerHostToolsWithRegistry(context)

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
            startAccountServices(context)
            mcpLogger.info(LogCategory.TERMINAL, "Session sharing armed", mapOf(
                "port" to SettingsManager.instance.settings.value.sessionSharingPort
            ))
        } catch (t: Throwable) {
            mcpLogger.warn(LogCategory.TERMINAL, "Failed to start session sharing; terminals still work", error = t)
        }
    }

    private fun startAccountServices(context: PluginContext) {
        if (accountBridge == null) return
        try {
            accountPublisher = AccountSessionPublisher(
                sharedTabIds = SessionShareManager.allSharedTabIds,
                remoteUrl = SessionShareManager.remoteUrlFlow,
                accountState = AccountSessionSource.state,
                // The collector belongs to the plugin, so an unload releases its classloader.
                enabled = SettingsManager.instance.settings.map { it.publishSessionsToAccount }
                    .stateIn(context.pluginScope, SharingStarted.Eagerly, SettingsManager.instance.settings.value.publishSessionsToAccount),
                infoFor = SessionShareManager::infoFor,
                sessionNameFor = SessionShareManager::sessionNameFor,
                deviceName = SessionShareManager::defaultSessionName,
                // Host transport takes precedence; standalone REST arguments are unused.
                accessToken = { null },
                restBaseUrl = "host RPC",
                anonKey = "",
                appVersion = ai.rever.bossterm.compose.update.Version.CURRENT.toString(),
                host = checkNotNull(AccountSessionSource.host),
            ).also { it.start() }
            AccountAutoShare.Default.start()
            AccountTerminalPreferences.Default.start()
            AccountSessionDirectory.Default.start()
            AccountAutoRemote.Default.start()
        } catch (t: Throwable) {
            logAccountFailure("Start account services", t)
            stopAccountServices()
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
            // The same registry backs Boss Calling's tool surface (the in-app voice
            // agent reaches it through BossTerm's VoiceToolSource seam rather than
            // over the MCP endpoint). Bound here because this is where the registry
            // arrives; read per enumeration, never cached. See BossVoiceToolSource.
            BossVoiceTools.bind(toolRegistry)
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
                    installBossServerTools(server, toolRegistry, hostToolsViaRegistry, mcpScope)
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
    private fun bossTermSettingsDir(): java.io.File = bossDataDir("bossterm")

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

    private fun logAccountFailure(operation: String, failure: Throwable) {
        // Exception messages can contain bearer URLs or E2E secrets.
        mcpLogger.warn(LogCategory.TERMINAL, "$operation failed (${failure.javaClass.simpleName})")
    }

    private inline fun accountCleanup(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            logAccountFailure(operation, t)
        }
    }

    @Synchronized
    private fun stopAccountServices() {
        accountCleanup("Stop account viewers") { AccountAutoRemote.Default.stop() }
        accountCleanup("Stop account directory") { AccountSessionDirectory.Default.stop() }
        accountCleanup("Stop account preferences") { AccountTerminalPreferences.Default.stop() }
        accountCleanup("Stop auto sharing") { AccountAutoShare.Default.stop() }
        // stop() synchronously waits at most 3 seconds for row deletion in BossTerm.
        // Keep the host bridge open until it returns.
        accountCleanup("Stop account publisher") { accountPublisher?.stop() }
        accountPublisher = null
        accountCleanup("Close account bridge") { accountBridge?.close() }
        accountBridge = null
        accountCleanup("Disconnect account source") { AccountSessionSource.disconnect() }
    }

    override fun dispose() {
        stopAccountServices()
        // disconnect() detaches the transport but retains the signed-out host facade.
        // A still-composing old terminal never falls back to standalone credentials.
        // Stop session sharing (idempotent; tears down the share server and
        // tunnels) and clear any approval toasts still on screen. The
        // pendingRequests collector dies with pluginScope cancellation.
        try {
            SessionShareManager.shutdown()
        } catch (t: Throwable) {
            mcpLogger.warn(LogCategory.TERMINAL, "Error stopping session sharing", error = t)
        }
        approvalToastIds.values.forEach { toastId ->
            runCatching { pluginContext?.notificationProvider?.dismiss(toastId) }
        }
        approvalToastIds.clear()

        // Stop the MCP server. stop() is non-blocking and delegates the Ktor
        // engine shutdown to a coroutine on mcpScope, so we deliberately do NOT
        // cancel mcpScope here — cancelling it would abort the in-flight shutdown
        // and leak the bound port.
        try {
            mcpManager?.stop()
        } catch (t: Throwable) {
            mcpLogger.warn(LogCategory.TERMINAL, "Error stopping BossTerm MCP manager", error = t)
        }
        // Cancel the plugin-tool sync collector (a child job of mcpScope, which we
        // otherwise leave running for the in-flight engine shutdown above) and
        // refuse late bridge installs from an in-flight engine start.
        stopDynamicPluginTools()
        // Cancel the server-controller state collectors so they can't pin this
        // plugin's classloader across disable/update cycles.
        mcpServerController?.close()
        mcpServerController = null
        mcpManager = null
        TerminalMcpConfigHolder.config = null
        setupStatusJob?.cancel()
        setupStatusJob = null
        BossTermSetupController.abortForPluginDispose()
        TerminalPluginContextHolder.setupSupervisor?.dispose()
        runCatching { pluginContext?.unregisterStatusBarItem(BossTermSetupStatusItem.ITEM_ID) }
            .onFailure { error ->
                mcpLogger.warn(LogCategory.TERMINAL, "Failed to unregister setup status item", error = error)
            }
        TerminalPluginContextHolder.setupSupervisor = null
        // Drop the registry reference so a disposed host registry isn't held by the
        // process-wide voice source across a disable/update cycle. The source itself
        // survives (it is stateless) and simply offers the two host tools until a
        // re-registration binds a live registry again.
        BossVoiceTools.unbind()

        // Unregister tab type when plugin is unloaded
        pluginContext?.tabRegistry?.unregisterTabType(TerminalTabType.typeId)
        // Same reasoning as at start: this instance's terminals go with it.
        sweepSidebarEnvFiles("stop")
        if (hostToolsViaRegistry) {
            runCatching { pluginContext?.unregisterMcpToolProvider(HostMcpToolProvider.PROVIDER_ID) }
            hostToolsViaRegistry = false
        }
        terminalApi = null
        pluginContext = null
    }

    private fun sweepSidebarEnvFiles(phase: String) {
        try {
            val removed = SidebarEnvInjection.sweepForLifecycle()
            if (removed > 0) {
                mcpLogger.info(LogCategory.TERMINAL, "Removed unconsumed run_in_sidebar env files", mapOf("phase" to phase, "count" to removed))
            }
        } catch (t: Throwable) {
            mcpLogger.warn(LogCategory.TERMINAL, "Could not sweep run_in_sidebar env files", error = t)
        }
    }

    /**
     * Register [HostMcpToolProvider] and report whether it took. A host whose `PluginContext`
     * predates the registry throws or hands back null here; the caller then falls back to the
     * direct-on-server registration, which is what shipped before.
     */
    private fun registerHostToolsWithRegistry(context: PluginContext): Boolean =
        try {
            if (context.mcpToolRegistry == null) {
                false
            } else {
                context.registerMcpToolProvider(HostMcpToolProvider())
                mcpLogger.info(
                    LogCategory.TERMINAL,
                    "Host tools registered through the MCP tool registry",
                    mapOf("providerId" to HostMcpToolProvider.PROVIDER_ID, "tools" to bossHostMcpToolDefs.size)
                )
                true
            }
        } catch (t: Throwable) {
            mcpLogger.warn(LogCategory.TERMINAL, "Could not register host tools with the registry; using the server", error = t)
            false
        }
}

internal inline fun updateSetupStatusRegistration(
    hasSession: Boolean,
    isRegistered: Boolean,
    register: () -> Unit,
    unregister: () -> Unit,
): Boolean {
    if (hasSession == isRegistered) return isRegistered
    if (hasSession) register() else unregister()
    return hasSession
}
