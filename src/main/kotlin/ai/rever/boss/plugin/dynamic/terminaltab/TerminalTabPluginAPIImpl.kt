package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.PendingSidebarCommand
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TerminalSessionEvent
import ai.rever.boss.plugin.api.TerminalSessionEventType
import ai.rever.boss.plugin.api.TerminalTabInfo
import ai.rever.boss.plugin.api.TerminalTabPluginAPI
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.bossterm.compose.onboarding.OnboardingWizard
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.SettingsPanel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

private val logger = BossLogger.forComponent("TerminalTabPluginAPIImpl")

/**
 * Implementation of TerminalTabPluginAPI.
 *
 * Delegates to TabbedTerminalStateRegistry, TerminalStateRegistry, and
 * the composable functions for rendering.
 *
 * Callbacks for runner terminal cleanup are set by the host via TerminalAPIAccess
 * after it obtains this API instance.
 */
class TerminalTabPluginAPIImpl(
    private val pluginContext: PluginContext? = null
) : TerminalTabPluginAPI {

    /**
     * Callback for when a runner terminal is removed (sidebar closed).
     * Set by host's TerminalAPIAccess so RunnerTerminalService can be notified.
     */
    var onRunnerTerminalRemoved: ((windowId: String, terminalId: String) -> Unit)? = null

    /**
     * Callback for when a runner config tab is closed in sidebar.
     * Set by host's TerminalAPIAccess so RunnerTerminalService can be notified.
     */
    var onRunnerConfigRemoved: ((windowId: String, configId: String) -> Unit)? = null

    /**
     * Publish a terminal session event to the application event bus.
     */
    internal fun publishSessionEvent(
        sessionId: String,
        eventType: TerminalSessionEventType,
        terminalId: String? = null,
        windowId: String? = null,
        title: String? = null
    ) {
        try {
            pluginContext?.applicationEventBus?.publish(
                TerminalSessionEvent(
                    sessionId = sessionId,
                    eventType = eventType,
                    terminalId = terminalId,
                    windowId = windowId,
                    title = title
                )
            )
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to publish terminal session event", mapOf(
                "eventType" to eventType.name,
                "sessionId" to sessionId
            ), error = e)
        }
    }

    // ============================================================
    // COMPOSABLE RENDERING
    // ============================================================

    @Composable
    override fun PersistentTabbedTerminalContent(
        terminalId: String,
        initialCommand: String?,
        workingDirectory: String?,
        onExit: () -> Unit,
        onShowSettings: () -> Unit,
        onTitleChange: ((String) -> Unit)?,
        onLinkClick: ((url: String, linkType: String) -> Boolean)?
    ) {
        PersistentTabbedTerminalContentImpl(
            terminalId = terminalId,
            initialCommand = initialCommand,
            workingDirectory = workingDirectory,
            onExit = onExit,
            onShowSettings = onShowSettings,
            onTitleChange = onTitleChange,
            onLinkClick = onLinkClick,
            sessionEventPublisher = { sessionId, eventType, tid, wid ->
                publishSessionEvent(sessionId, eventType, tid, wid)
            }
        )
    }

    @Composable
    override fun TabbedTerminalContent(
        workingDirectory: String?,
        onExit: () -> Unit,
        onShowSettings: () -> Unit
    ) {
        TabbedTerminalContentImpl(
            workingDirectory = workingDirectory,
            onExit = onExit,
            onShowSettings = onShowSettings,
            onRunnerTerminalRemoved = onRunnerTerminalRemoved,
            onRunnerConfigRemoved = onRunnerConfigRemoved,
            sessionEventPublisher = { sessionId, eventType, terminalId, windowId ->
                publishSessionEvent(sessionId, eventType, terminalId, windowId)
            }
        )
    }

    @Composable
    override fun TerminalContent(
        terminalId: String?,
        initialCommand: String?,
        workingDirectory: String?,
        onExit: () -> Unit
    ) {
        TerminalContentImpl(
            terminalId = terminalId,
            initialCommand = initialCommand,
            workingDirectory = workingDirectory,
            onExit = onExit
        )
    }

    // ============================================================
    // STATE MANAGEMENT
    // ============================================================

    override fun hasTerminalState(windowId: String, terminalId: String): Boolean {
        return TabbedTerminalStateRegistry.contains(windowId, terminalId)
    }

    override fun removeTerminalState(windowId: String, terminalId: String) {
        TabbedTerminalStateRegistry.remove(windowId, terminalId)
    }

    override fun removeAllForWindow(windowId: String): Int {
        return TabbedTerminalStateRegistry.removeAllForWindow(windowId)
    }

    override fun resetAllTerminals(): Int {
        val tabbedCount = TabbedTerminalStateRegistry.resetAllTerminals()
        val embeddableCount = TerminalStateRegistry.resetAll()
        val total = tabbedCount + embeddableCount
        logger.info(LogCategory.TERMINAL, "Total terminal reset complete", mapOf("total" to total, "tabbed" to tabbedCount, "embeddable" to embeddableCount))
        return total
    }

    // ============================================================
    // TERMINAL CONTROL
    // ============================================================

    override fun sendCommand(windowId: String, terminalId: String, command: String): Boolean {
        return TabbedTerminalStateRegistry.runCommand(windowId, terminalId, command)
    }

    override fun sendInterrupt(windowId: String, terminalId: String): Boolean {
        return TabbedTerminalStateRegistry.sendCtrlC(windowId, terminalId)
    }

    override fun sendInput(windowId: String, terminalId: String, bytes: ByteArray): Boolean {
        return TabbedTerminalStateRegistry.sendInput(windowId, terminalId, bytes)
    }

    override fun closeActiveTab(windowId: String, terminalId: String): Boolean {
        return TabbedTerminalStateRegistry.closeActiveTab(windowId, terminalId)
    }

    // ============================================================
    // SIDEBAR TERMINAL OPERATIONS
    // ============================================================

    override fun newSidebarTab(
        windowId: String,
        command: String,
        workingDirectory: String?,
        configId: String?,
        isRerun: Boolean
    ): Boolean {
        return TabbedTerminalStateRegistry.newSidebarTab(windowId, command, workingDirectory, configId, isRerun)
    }

    override fun registerSidebarTabId(windowId: String, configId: String, tabId: String) {
        TabbedTerminalStateRegistry.registerSidebarTabId(windowId, configId, tabId)
    }

    override fun removeSidebarConfigTracking(windowId: String, configId: String) {
        TabbedTerminalStateRegistry.removeSidebarConfigTracking(windowId, configId)
    }

    override fun clearSidebarConfigTrackingForWindow(windowId: String) {
        TabbedTerminalStateRegistry.clearSidebarConfigTrackingForWindow(windowId)
    }

    override fun getConfigIdForSidebarTab(windowId: String, tabId: String): String? {
        return TabbedTerminalStateRegistry.getConfigIdForSidebarTab(windowId, tabId)
    }

    // ============================================================
    // PENDING SIDEBAR COMMANDS
    // ============================================================

    override fun setPendingSidebarCommand(windowId: String, command: String, workingDirectory: String?, configId: String?) {
        setPendingSidebarCommand(windowId, command, workingDirectory, configId)
    }

    override fun consumePendingSidebarCommand(windowId: String): PendingSidebarCommand? {
        return ai.rever.boss.plugin.dynamic.terminaltab.consumePendingSidebarCommand(windowId)
    }

    // ============================================================
    // OBSERVABLE STATE
    // ============================================================

    override val resetGeneration: StateFlow<Int>
        get() = TabbedTerminalStateRegistry.resetGeneration

    // ============================================================
    // SETTINGS & ONBOARDING
    // ============================================================

    @Composable
    override fun TerminalSettingsPanel(modifier: Modifier) {
        val settingsManager = remember { SettingsManager.instance }
        val currentSettings by settingsManager.settings.collectAsState()

        SettingsPanel(
            settings = currentSettings,
            onSettingsChange = { newSettings ->
                settingsManager.updateSettings(newSettings)
            },
            onResetToDefaults = {
                settingsManager.resetToDefaults()
            },
            onRestartApp = null,
            modifier = modifier
        )
    }

    @Composable
    override fun TerminalOnboardingWizard(onDismiss: () -> Unit, onComplete: () -> Unit) {
        OnboardingWizard(
            onDismiss = onDismiss,
            onComplete = onComplete,
            settingsManager = SettingsManager.instance
        )
    }

    // ============================================================
    // TERMINAL SETTINGS MANAGEMENT
    // ============================================================

    override fun getTerminalFontSize(): Float {
        return try {
            SettingsManager.instance.settings.value.fontSize
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to get terminal font size", error = e)
            -1f
        }
    }

    override fun setTerminalFontSize(size: Float): Boolean {
        return try {
            val current = SettingsManager.instance.settings.value
            SettingsManager.instance.updateSettings(current.copy(fontSize = size))
            true
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to set terminal font size", error = e)
            false
        }
    }

    override fun getTerminalFontFamily(): String {
        return try {
            SettingsManager.instance.settings.value.fontName ?: ""
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to get terminal font family", error = e)
            ""
        }
    }

    override fun setTerminalFontFamily(family: String): Boolean {
        return try {
            val current = SettingsManager.instance.settings.value
            SettingsManager.instance.updateSettings(current.copy(fontName = family))
            true
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to set terminal font family", error = e)
            false
        }
    }

    override fun isOnboardingCompleted(): Boolean {
        return try {
            SettingsManager.instance.settings.value.onboardingCompleted
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to check onboarding status", error = e)
            true
        }
    }

    // ============================================================
    // TERMINAL TAB MANAGEMENT
    // ============================================================

    override fun listTabs(windowId: String, terminalId: String): List<TerminalTabInfo> {
        return try {
            val state = TabbedTerminalStateRegistry.get(windowId, terminalId) ?: return emptyList()
            val tabs = state.tabs
            val activeIndex = state.activeTabIndex
            tabs.mapIndexed { index, tab ->
                TerminalTabInfo(
                    id = tab.id,
                    title = tab.title.value,
                    isActive = index == activeIndex,
                    index = index
                )
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to list terminal tabs", error = e)
            emptyList()
        }
    }

    override fun switchToTab(windowId: String, terminalId: String, tabId: String): Boolean {
        return try {
            val state = TabbedTerminalStateRegistry.get(windowId, terminalId) ?: return false
            state.switchToTab(tabId)
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to switch terminal tab", error = e)
            false
        }
    }

    override fun getActiveTabIndex(windowId: String, terminalId: String): Int {
        return try {
            val state = TabbedTerminalStateRegistry.get(windowId, terminalId) ?: return -1
            state.activeTabIndex
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to get active tab index", error = e)
            -1
        }
    }

    override fun getTabCount(windowId: String, terminalId: String): Int {
        return try {
            val state = TabbedTerminalStateRegistry.get(windowId, terminalId) ?: return 0
            state.tabCount
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to get tab count", error = e)
            0
        }
    }

    override fun createTab(
        windowId: String,
        terminalId: String,
        workingDirectory: String?,
        initialCommand: String?
    ): String? {
        return try {
            val state = TabbedTerminalStateRegistry.get(windowId, terminalId) ?: return null
            val normalizedCommand = if (initialCommand != null) normalizeCommandForWindows(initialCommand) else null
            state.createTab(workingDir = workingDirectory, initialCommand = normalizedCommand)
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to create terminal tab", error = e)
            null
        }
    }

    override fun getActiveWorkingDirectory(windowId: String, terminalId: String): String? {
        return try {
            val state = TabbedTerminalStateRegistry.get(windowId, terminalId) ?: return null
            state.getActiveWorkingDirectory()
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to get active working directory", error = e)
            null
        }
    }

    // ============================================================
    // SHELL UTILITIES
    // ============================================================

    override fun getDefaultShell(): String {
        return try {
            if (isWindows) {
                System.getenv("COMSPEC") ?: "cmd.exe"
            } else {
                System.getenv("SHELL") ?: "/bin/sh"
            }
        } catch (e: Exception) {
            ""
        }
    }

    override fun isWindows(): Boolean = isWindows

    // ============================================================
    // SPLIT PANE MANAGEMENT (T6)
    // ============================================================

    override fun splitVertical(windowId: String, terminalId: String, tabId: String?): String? {
        return try {
            val state = TabbedTerminalStateRegistry.get(windowId, terminalId) ?: return null
            state.splitVertical(tabId)
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to split vertical", error = e)
            null
        }
    }

    override fun splitHorizontal(windowId: String, terminalId: String, tabId: String?): String? {
        return try {
            val state = TabbedTerminalStateRegistry.get(windowId, terminalId) ?: return null
            state.splitHorizontal(tabId)
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to split horizontal", error = e)
            null
        }
    }

    override fun closeFocusedPane(windowId: String, terminalId: String, tabId: String?): Boolean {
        return try {
            val state = TabbedTerminalStateRegistry.get(windowId, terminalId) ?: return false
            state.closeFocusedPane(tabId)
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to close focused pane", error = e)
            false
        }
    }

    override fun getPaneCount(windowId: String, terminalId: String, tabId: String?): Int {
        return try {
            val state = TabbedTerminalStateRegistry.get(windowId, terminalId) ?: return 0
            state.getPaneCount(tabId)
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to get pane count", error = e)
            0
        }
    }

    override fun hasSplitPanes(windowId: String, terminalId: String, tabId: String?): Boolean {
        return try {
            val state = TabbedTerminalStateRegistry.get(windowId, terminalId) ?: return false
            state.hasSplitPanes(tabId)
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to check split panes", error = e)
            false
        }
    }

    override fun writeToFocusedPane(windowId: String, terminalId: String, text: String, tabId: String?): Boolean {
        return try {
            val state = TabbedTerminalStateRegistry.get(windowId, terminalId) ?: return false
            state.writeToFocusedPane(text, tabId)
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to write to focused pane", error = e)
            false
        }
    }

    // ============================================================
    // REACTIVE STATE (T7)
    // ============================================================

    override fun getTabsFlow(windowId: String, terminalId: String): kotlinx.coroutines.flow.Flow<List<ai.rever.boss.plugin.api.TerminalTabInfo>>? {
        return try {
            val state = TabbedTerminalStateRegistry.get(windowId, terminalId) ?: return null
            kotlinx.coroutines.flow.flow {
                state.tabsFlow.collect { tabs ->
                    emit(tabs.map { tab ->
                        ai.rever.boss.plugin.api.TerminalTabInfo(
                            id = tab.id,
                            title = tab.title,
                            isActive = false,
                            index = 0
                        )
                    })
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to get tabs flow", error = e)
            null
        }
    }

    override fun getActiveTabIndexFlow(windowId: String, terminalId: String): kotlinx.coroutines.flow.Flow<Int>? {
        return try {
            val state = TabbedTerminalStateRegistry.get(windowId, terminalId) ?: return null
            state.activeTabIndexFlow
        } catch (e: Exception) {
            logger.warn(LogCategory.TERMINAL, "Failed to get active tab index flow", error = e)
            null
        }
    }
}
