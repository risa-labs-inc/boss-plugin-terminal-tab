package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.SIDEBAR_TERMINAL_ID
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.bossterm.compose.EmbeddableTerminal
import ai.rever.bossterm.compose.TabbedTerminal
import ai.rever.bossterm.compose.hyperlinks.HyperlinkInfo
import ai.rever.bossterm.compose.hyperlinks.HyperlinkType
import ai.rever.bossterm.compose.rememberEmbeddableTerminalState
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import ai.rever.bossterm.compose.onboarding.OnboardingWizard
import ai.rever.boss.plugin.api.LocalWindowIdProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val logger = BossLogger.forComponent("TerminalComposables")

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

    var showWelcomeWizard by remember { mutableStateOf(false) }
    LaunchedEffect(settings.onboardingCompleted) {
        if (!settings.onboardingCompleted) showWelcomeWizard = true
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
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = settings.defaultBackgroundColor
        ) {
            val normalizedPendingCommand = pendingCommand?.command?.let { command ->
                normalizeCommandForWindows(command)
            }

            KeyboardShortcutInterceptorWrapper(windowId = windowId) {
                TabbedTerminal(
                    state = state,
                    initialCommand = normalizedPendingCommand,
                    workingDirectory = effectiveWorkingDir,
                    settingsOverride = sidebarSettings,
                    onExit = {
                        TabbedTerminalStateRegistry.remove(windowId, SIDEBAR_TERMINAL_ID)
                        onRunnerTerminalRemoved?.invoke(windowId, SIDEBAR_TERMINAL_ID)
                        onExit()
                    },
                    onTabClose = { tabId ->
                        val configId = TabbedTerminalStateRegistry.getConfigIdForSidebarTab(windowId, tabId)
                        if (configId != null) {
                            onRunnerConfigRemoved?.invoke(windowId, configId)
                            TabbedTerminalStateRegistry.removeSidebarConfigTracking(windowId, configId)
                        }
                    },
                    onShowSettings = onShowSettings,
                    onShowWelcomeWizard = { showWelcomeWizard = true },
                    onLinkClick = { info -> handleTerminalLinkClick(info, scope, SIDEBAR_TERMINAL_ID, windowId) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (showWelcomeWizard) {
        OnboardingWizard(
            onDismiss = { showWelcomeWizard = false },
            onComplete = { showWelcomeWizard = false },
            settingsManager = SettingsManager.instance
        )
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

    val isNew = !TabbedTerminalStateRegistry.contains(windowId, terminalId)
    val state = remember(terminalId, resetGeneration) { TabbedTerminalStateRegistry.getOrCreate(windowId, terminalId) }
    val effectiveWorkingDir = if (isNew) workingDirectory else null

    var showWelcomeWizard by remember { mutableStateOf(false) }
    LaunchedEffect(settings.onboardingCompleted) {
        if (!settings.onboardingCompleted) showWelcomeWizard = true
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
            }
            state.addSessionListener(listener)
            onDispose { state.removeSessionListener(listener) }
        } else {
            onDispose { }
        }
    }

    key(resetGeneration) {
        Surface(
            modifier = Modifier.fillMaxSize(),
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
                TabbedTerminal(
                    state = state,
                    initialCommand = normalizedInitialCommand,
                    workingDirectory = effectiveWorkingDir,
                    onExit = {
                        TabbedTerminalStateRegistry.remove(windowId, terminalId)
                        onExit()
                    },
                    onShowSettings = onShowSettings,
                    onShowWelcomeWizard = { showWelcomeWizard = true },
                    onWindowTitleChange = { title -> onTitleChange?.invoke(title) },
                    onLinkClick = { info ->
                        if (onLinkClick != null) {
                            onLinkClick(info.url, info.type.name)
                        } else {
                            handleTerminalLinkClick(info, scope, terminalId, windowId)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (showWelcomeWizard) {
        OnboardingWizard(
            onDismiss = { showWelcomeWizard = false },
            onComplete = { showWelcomeWizard = false },
            settingsManager = SettingsManager.instance
        )
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
        Surface(
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
    content()
}

/**
 * Handles terminal link clicks by routing to the host's TerminalLinkEventBus.
 */
internal fun handleTerminalLinkClick(info: HyperlinkInfo, scope: CoroutineScope, terminalId: String? = null, windowId: String? = null): Boolean {
    return when (info.type) {
        HyperlinkType.HTTP -> {
            scope.launch {
                emitTerminalLinkClick(info.url, terminalId, windowId)
            }
            true
        }
        HyperlinkType.FILE -> {
            scope.launch(Dispatchers.IO) {
                val rawPath = stripFilePrefix(info.url)
                val parsed = parseFileReference(rawPath)
                val canonicalPath = validateAndGetCanonicalPath(parsed.path)

                if (canonicalPath != null) {
                    val urlWithLocation = buildString {
                        append("file:")
                        append(canonicalPath)
                        if (parsed.line > 0) {
                            append(":${parsed.line}")
                            if (parsed.column > 0) {
                                append(":${parsed.column}")
                            }
                        }
                    }
                    emitTerminalLinkClick(urlWithLocation, terminalId, windowId)
                } else {
                    logger.warn(LogCategory.TERMINAL, "Cannot open file from terminal link", mapOf("url" to info.url))
                }
            }
            true
        }
        else -> false
    }
}

// ============================================================
// LINK HANDLING UTILITIES (delegating to host via reflection)
// ============================================================

private data class FileReference(val path: String, val line: Int, val column: Int)

private fun stripFilePrefix(url: String): String {
    return try {
        val clazz = Class.forName("ai.rever.boss.components.events.TerminalLinkEventBusKt")
        val method = clazz.getMethod("stripFilePrefix", String::class.java)
        method.invoke(null, url) as String
    } catch (e: Exception) {
        url.removePrefix("file://").removePrefix("file:")
    }
}

private fun parseFileReference(rawPath: String): FileReference {
    return try {
        val clazz = Class.forName("ai.rever.boss.components.events.TerminalLinkEventBusKt")
        val method = clazz.getMethod("parseFileReference", String::class.java)
        val result = method.invoke(null, rawPath)
        val resultClass = result!!::class.java
        FileReference(
            path = resultClass.getMethod("getPath").invoke(result) as String,
            line = resultClass.getMethod("getLine").invoke(result) as Int,
            column = resultClass.getMethod("getColumn").invoke(result) as Int
        )
    } catch (e: Exception) {
        // Simple fallback: just use the path as-is
        FileReference(rawPath, 0, 0)
    }
}

private fun validateAndGetCanonicalPath(path: String): String? {
    return try {
        val file = java.io.File(path)
        if (file.exists()) file.canonicalPath else null
    } catch (e: Exception) {
        null
    }
}

private fun emitTerminalLinkClick(url: String, terminalId: String?, windowId: String?) {
    try {
        val busClass = Class.forName("ai.rever.boss.components.events.TerminalLinkEventBus")
        val instance = busClass.kotlin.objectInstance ?: busClass.getDeclaredField("INSTANCE").get(null)
        // Use tryEmitLinkClick (non-suspend) instead of emitLinkClick (suspend)
        // Suspend functions can't be called via Java reflection due to hidden Continuation parameter
        val method = busClass.getMethod("tryEmitLinkClick", String::class.java, String::class.java, String::class.java)
        method.invoke(instance, url, terminalId, windowId)
    } catch (e: Exception) {
        logger.warn(LogCategory.TERMINAL, "Failed to emit terminal link click", mapOf("url" to url))
    }
}
