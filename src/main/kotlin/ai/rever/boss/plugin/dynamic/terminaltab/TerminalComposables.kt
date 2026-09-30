package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.SIDEBAR_TERMINAL_ID
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.bossterm.compose.EmbeddableTerminal
import ai.rever.bossterm.compose.TabbedTerminal
import ai.rever.bossterm.compose.mcp.LocalBossTermMcpConfig
import ai.rever.bossterm.compose.hyperlinks.HyperlinkInfo
import ai.rever.bossterm.compose.hyperlinks.HyperlinkType
import ai.rever.bossterm.compose.rememberEmbeddableTerminalState
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import ai.rever.bossterm.compose.share.SessionShareManager
import ai.rever.boss.plugin.api.LocalIsPanelActive
import ai.rever.boss.plugin.api.LocalWindowIdProvider
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo
import ai.rever.boss.plugin.ui.TerminalTitleBarBridge
import ai.rever.bossterm.compose.window.LocalCallBarHosted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val logger = BossLogger.forComponent("TerminalComposables")

/**
 * What Boss Calling's in-app button is called inside BossConsole.
 *
 * BossTerm defaults to "Call BossTerm" for the standalone app; here the agent is
 * Boss. Renames the button, the shortcut hint and the tooltip — not the voice
 * agent's own name, which it takes from its instructions.
 */
internal const val CALL_LABEL = "Call Boss"

/**
 * Tabbed terminal content for the sidebar panel.
 * Uses persistent state so runner commands can create tabs.
 */
@Composable
internal fun TabbedTerminalContentImpl(
    workingDirectory: String?,
    onExit: () -> Unit,
    onShowSettings: () -> Unit,
    onRunnerTerminalRemoved: ((windowId: String, terminalId: String) -> Unit)? = null,
    onRunnerConfigRemoved: ((windowId: String, configId: String) -> Unit)? = null,
    sessionEventPublisher: ((sessionId: String, eventType: ai.rever.boss.plugin.api.TerminalSessionEventType, terminalId: String?, windowId: String?) -> Unit)? = null
) {
    val settings by SettingsManager.instance.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val windowId = LocalWindowIdProvider.current?.getWindowId() ?: return

    val resetGeneration by TabbedTerminalStateRegistry.resetGeneration.collectAsState()
    val isNew = !TabbedTerminalStateRegistry.contains(windowId, SIDEBAR_TERMINAL_ID)
    val state = remember(resetGeneration) { TabbedTerminalStateRegistry.getOrCreate(windowId, SIDEBAR_TERMINAL_ID) }
    val pendingCommand = remember { if (isNew) consumePendingSidebarCommand(windowId) else null }
    val sidebarSettings = remember { TerminalSettingsOverride(alwaysShowTabBar = true) }
    val effectiveWorkingDir = pendingCommand?.workingDirectory ?: workingDirectory

    LaunchedEffect(settings.onboardingCompleted) {
        if (!settings.onboardingCompleted) TerminalPluginContextHolder.setupSupervisor?.requestSetup(windowId)
    }

    // Register the first tab's ID using session listener
    val capturedWindowId = windowId
    DisposableEffect(pendingCommand?.configId) {
        if (pendingCommand?.configId != null) {
            val configId = pendingCommand.configId!!
            val listener = object : ai.rever.bossterm.compose.tabs.TerminalSessionListener {
                override fun onSessionCreated(session: ai.rever.bossterm.compose.TerminalSession) {
                    TabbedTerminalStateRegistry.registerSidebarTabId(capturedWindowId, configId, session.id)
                    state.removeSessionListener(this)
                }
            }
            state.addSessionListener(listener)
            onDispose { state.removeSessionListener(listener) }
        } else {
            onDispose { }
        }
    }

    // Publish terminal session lifecycle events
    DisposableEffect(state, sessionEventPublisher) {
        if (sessionEventPublisher != null) {
            val listener = object : ai.rever.bossterm.compose.tabs.TerminalSessionListener {
                override fun onSessionCreated(session: ai.rever.bossterm.compose.TerminalSession) {
                    sessionEventPublisher.invoke(
                        session.id,
                        ai.rever.boss.plugin.api.TerminalSessionEventType.CREATED,
                        SIDEBAR_TERMINAL_ID,
                        capturedWindowId
                    )
                }
            }
            state.addSessionListener(listener)
            onDispose { state.removeSessionListener(listener) }
        } else {
            onDispose { }
        }
    }

    key(resetGeneration) {
        HostTerminalSurface(
            modifier = Modifier.fillMaxSize()
                .onFocusChanged { if (it.hasFocus) TabbedTerminalStateRegistry.markTitleBarTerminal(windowId, SIDEBAR_TERMINAL_ID) }
                .focusGroup(),
            color = settings.defaultBackgroundColor
        ) {
            val normalizedPendingCommand = pendingCommand?.command?.let { command ->
                normalizeCommandForWindows(command)
            }

            KeyboardShortcutInterceptorWrapper(windowId = windowId) {
              CompositionLocalProvider(
                  LocalBossTermMcpConfig provides TerminalMcpConfigHolder.config,
                  LocalCallBarHosted provides TerminalTitleBarBridge.isHosted(windowId),
              ) {
                TabbedTerminal(
                    state = state,
                    headerContent = terminalTitleBarHeader(windowId),
                    initialCommand = normalizedPendingCommand,
                    workingDirectory = effectiveWorkingDir,
                    settingsOverride = sidebarSettings,
                    onExit = {
                        TabbedTerminalStateRegistry.remove(windowId, SIDEBAR_TERMINAL_ID)
                        onRunnerTerminalRemoved?.invoke(windowId, SIDEBAR_TERMINAL_ID)
                        onExit()
                    },
                    onTabClose = { tabId ->
                        // Stop any TAB-scoped session share for this tab (the share
                        // manager only learns about closes from the embedder).
                        SessionShareManager.onTabClosed(tabId)
                        val configId = TabbedTerminalStateRegistry.getConfigIdForSidebarTab(windowId, tabId)
                        if (configId != null) {
                            onRunnerConfigRemoved?.invoke(windowId, configId)
                            TabbedTerminalStateRegistry.removeSidebarConfigTracking(windowId, configId)
                        }
                    },
                    onShowSettings = onShowSettings,
                    onShowWelcomeWizard = {
                        TerminalPluginContextHolder.setupSupervisor?.requestSetup(windowId)
                    },
                    onLinkClick = { info -> handleTerminalLinkClick(info, scope, SIDEBAR_TERMINAL_ID, windowId) },
                    // Inside BossConsole the in-app voice agent is "Call Boss", and it
                    // gets the `boss` MCP surface on top of BossTerm's own thirteen
                    // terminal tools. See BossVoiceToolSource.
                    callLabel = CALL_LABEL,
                    voiceToolSource = BossVoiceTools.source,
                    modifier = Modifier.fillMaxSize()
                )
              }
            }
        }
    }

}

/**
 * Persistent tabbed terminal content for individual terminal tabs.
 */
@Composable
internal fun PersistentTabbedTerminalContentImpl(
    terminalId: String,
    initialCommand: String?,
    workingDirectory: String?,
    onExit: () -> Unit,
    onShowSettings: () -> Unit,
    onTitleChange: ((String) -> Unit)?,
    onLinkClick: ((url: String, linkType: String) -> Boolean)?,
    sessionEventPublisher: ((sessionId: String, eventType: ai.rever.boss.plugin.api.TerminalSessionEventType, terminalId: String?, windowId: String?) -> Unit)? = null
) {
    val resetGeneration by TabbedTerminalStateRegistry.resetGeneration.collectAsState()
    val settings by SettingsManager.instance.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val windowId = LocalWindowIdProvider.current?.getWindowId() ?: return
    // Active-panel signal from the host (BossMainWindowPanel). When the
    // user clicks to a different panel and back, this flips false→true,
    // which causes BossTerm's internal LaunchedEffect(tab.id, isActiveTab)
    // to re-issue the focus requester for the focused pane — without
    // this signal the embedded terminal stays visually present but
    // unable to receive keystrokes until the user manually nudges a
    // split or switches tabs. Defaults to `true` outside main panels
    // (sidebar / dialog / embedded), preserving existing behavior.
    val isPanelActive = LocalIsPanelActive.current

    val isNew = !TabbedTerminalStateRegistry.contains(windowId, terminalId)
    val state = remember(terminalId, resetGeneration) { TabbedTerminalStateRegistry.getOrCreate(windowId, terminalId) }
    val effectiveWorkingDir = if (isNew) workingDirectory else null

    LaunchedEffect(settings.onboardingCompleted) {
        if (!settings.onboardingCompleted) TerminalPluginContextHolder.setupSupervisor?.requestSetup(windowId)
    }

    DisposableEffect(terminalId) {
        onDispose { }
    }

    // Publish terminal session lifecycle events
    val capturedTerminalId = terminalId
    DisposableEffect(state, sessionEventPublisher) {
        if (sessionEventPublisher != null) {
            val listener = object : ai.rever.bossterm.compose.tabs.TerminalSessionListener {
                override fun onSessionCreated(session: ai.rever.bossterm.compose.TerminalSession) {
                    sessionEventPublisher.invoke(
                        session.id,
                        ai.rever.boss.plugin.api.TerminalSessionEventType.CREATED,
                        capturedTerminalId,
                        windowId
                    )
                }

                // Explicitly override the remaining TerminalSessionListener default
                // methods. BossTerm is built with -Xjvm-default=disable, so any
                // unimplemented default generates a synthetic bridge to
                // TerminalSessionListener$DefaultImpls — a class resolved lazily on the
                // first call (session close), which happens during window-close teardown
                // AFTER the plugin classloader is closed -> NoClassDefFoundError.
                // Overriding both removes the bridge entirely. See BossConsole#764.
                override fun onSessionClosed(session: ai.rever.bossterm.compose.TerminalSession) {
                    sessionEventPublisher.invoke(
                        session.id,
                        ai.rever.boss.plugin.api.TerminalSessionEventType.DESTROYED,
                        capturedTerminalId,
                        windowId
                    )
                }

                override fun onAllSessionsClosed() {}
            }
            state.addSessionListener(listener)
            onDispose { state.removeSessionListener(listener) }
        } else {
            onDispose { }
        }
    }

    key(resetGeneration) {
        HostTerminalSurface(
            modifier = Modifier.fillMaxSize()
                .onFocusChanged { if (it.hasFocus) TabbedTerminalStateRegistry.markTitleBarTerminal(windowId, terminalId) }
                .focusGroup(),
            color = settings.defaultBackgroundColor
        ) {
            val normalizedInitialCommand = if (isNew) {
                if (isWindows && !initialCommand.isNullOrEmpty()) {
                    if (initialCommand.endsWith("\n") || initialCommand.endsWith("\r\n")) initialCommand
                    else "$initialCommand\n"
                } else {
                    initialCommand
                }
            } else {
                null
            }

            KeyboardShortcutInterceptorWrapper(windowId = windowId) {
              CompositionLocalProvider(
                  LocalBossTermMcpConfig provides TerminalMcpConfigHolder.config,
                  LocalCallBarHosted provides TerminalTitleBarBridge.isHosted(windowId),
              ) {
                TabbedTerminal(
                    state = state,
                    headerContent = terminalTitleBarHeader(windowId),
                    initialCommand = normalizedInitialCommand,
                    workingDirectory = effectiveWorkingDir,
                    isActive = isPanelActive,
                    onExit = {
                        TabbedTerminalStateRegistry.remove(windowId, terminalId)
                        onExit()
                    },
                    // Stop any TAB-scoped session share for a closed tab (the share
                    // manager only learns about closes from the embedder).
                    onTabClose = { tabId -> SessionShareManager.onTabClosed(tabId) },
                    onShowSettings = onShowSettings,
                    onShowWelcomeWizard = {
                        TerminalPluginContextHolder.setupSupervisor?.requestSetup(windowId)
                    },
                    onWindowTitleChange = { title -> onTitleChange?.invoke(title) },
                    onLinkClick = { info ->
                        if (onLinkClick != null) {
                            onLinkClick(info.url, info.type.name)
                        } else {
                            handleTerminalLinkClick(info, scope, terminalId, windowId)
                        }
                    },
                    // See the sidebar terminal above. EmbeddableTerminal (further down)
                    // renders no call affordance, so it takes neither parameter.
                    callLabel = CALL_LABEL,
                    voiceToolSource = BossVoiceTools.source,
                    modifier = Modifier.fillMaxSize()
                )
              }
            }
        }
    }

}

/**
 * Single embedded terminal content.
 */
@Composable
internal fun TerminalContentImpl(
    terminalId: String?,
    initialCommand: String?,
    workingDirectory: String?,
    onExit: () -> Unit
) {
    val resetGeneration by TabbedTerminalStateRegistry.resetGeneration.collectAsState()
    val settings by SettingsManager.instance.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val windowId = LocalWindowIdProvider.current?.getWindowId() ?: return

    val terminalState = if (terminalId != null) {
        val isNew = !TerminalStateRegistry.contains(windowId, terminalId)
        val state = remember(terminalId, resetGeneration) { TerminalStateRegistry.getOrCreate(windowId, terminalId) }

        DisposableEffect(terminalId) {
            onDispose { }
        }

        isNew to state
    } else {
        true to rememberEmbeddableTerminalState()
    }

    val (isNew, state) = terminalState

    key(resetGeneration) {
        HostTerminalSurface(
            modifier = Modifier.fillMaxSize(),
            color = settings.defaultBackgroundColor
        ) {
            KeyboardShortcutInterceptorWrapper(windowId = windowId) {
                EmbeddableTerminal(
                    state = state,
                    initialCommand = if (isNew) initialCommand else null,
                    workingDirectory = if (isNew) workingDirectory else null,
                    onExit = { _ ->
                        terminalId?.let { TerminalStateRegistry.remove(windowId, it) }
                        onExit()
                    },
                    onLinkClick = { info -> handleTerminalLinkClick(info, scope, terminalId, windowId) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Wrapper that provides keyboard shortcut interception for terminal composables.
 * Uses host's KeyboardShortcutInterceptor via reflection if available.
 */
@Composable
internal fun KeyboardShortcutInterceptorWrapper(
    windowId: String,
    content: @Composable () -> Unit
) {
    // Render content directly - the host handles keyboard shortcuts at a higher level
    // The KeyboardShortcutInterceptor from the host is a composable that can't be called
    // via reflection easily. Since the host's shortcut system works at the window level,
    // terminal composables will still receive proper keyboard handling.
    //
    // This is also where the plugin learns which window is focused: the host publishes no focus
    // event to plugins, and the MCP tools need a window to act in (see HostWindows).
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowId, windowFocused) {
        if (windowFocused) HostWindows.noteFocused(windowId)
    }
    content()
}

/**
 * Open a link clicked in a terminal: a web page in a browser tab, a file (with its line and
 * column, when the link carries them) in the editor, both in [windowId]'s active pane. See
 * [TerminalLinkTarget] for why this goes through the plugin API rather than the host's bus.
 */
internal fun handleTerminalLinkClick(info: HyperlinkInfo, scope: CoroutineScope, terminalId: String? = null, windowId: String? = null): Boolean {
    return when (info.type) {
        HyperlinkType.HTTP -> {
            val target = webLinkTarget(info.url) ?: return false
            // No window to open it in: BossTerm's own handling opens a web page in the system browser.
            val operations = HostWindows.splitViewFor(windowId) ?: return false
            scope.launch(hostCallContext) { openLink(operations, target) }
            true
        }
        HyperlinkType.FILE -> {
            // Handled even with no window: BossTerm's fallback would hand a file to the OS opener,
            // which can run it. A file link that cannot open in BOSS does nothing.
            val operations = HostWindows.splitViewFor(windowId)
            if (operations == null) {
                logger.warn(LogCategory.TERMINAL, "No window to open the terminal link in", mapOf("terminalId" to (terminalId ?: "")))
                return true
            }
            scope.launch(Dispatchers.IO) {
                val target = resolveFileLink(info.url)
                if (target == null) {
                    logger.warn(LogCategory.TERMINAL, "Cannot open file from terminal link", mapOf("url" to info.url))
                    return@launch
                }
                withContext(hostCallContext) { openLink(operations, target) }
            }
            true
        }
        else -> false
    }
}

private fun openLink(operations: SplitViewOperations, target: TerminalLinkTarget) {
    try {
        openTerminalLink(operations, target)
    } catch (e: Exception) {
        logger.warn(LogCategory.TERMINAL, "Failed to open terminal link", error = e)
    }
}
