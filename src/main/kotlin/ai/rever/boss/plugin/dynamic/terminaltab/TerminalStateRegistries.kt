package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.PendingSidebarCommand
import ai.rever.boss.plugin.api.SIDEBAR_TERMINAL_ID
import ai.rever.boss.plugin.api.TerminalTabActivity
import ai.rever.bossterm.compose.blocks.BlockState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.bossterm.compose.EmbeddableTerminalState
import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val logger = BossLogger.forComponent("TerminalStateRegistries")

/**
 * Registry to store TabbedTerminal states by window and terminal ID, allowing them to persist across
 * composition tree changes (e.g., when switching tabs).
 *
 * Uses composite keys of format "$windowId:$terminalId" to ensure per-window isolation.
 */
object TabbedTerminalStateRegistry {
    private val states = mutableMapOf<String, TabbedTerminalState>()

    private val _resetGeneration = MutableStateFlow(0)
    val resetGeneration: StateFlow<Int> = _resetGeneration.asStateFlow()

    private fun key(windowId: String, terminalId: String) = "$windowId:$terminalId"

    fun getOrCreate(windowId: String, terminalId: String): TabbedTerminalState {
        // Register newly-created states with BossTerm's process-wide MCP registry
        // (inside the factory lambda so it runs once per state, not on cache hits).
        // This is what makes the terminal visible to the bossconsole MCP server
        // started by TerminalTabDynamicPlugin. register() is idempotent.
        return states.getOrPut(key(windowId, terminalId)) {
            TabbedTerminalState().also { McpTerminalRegistry.register(it) }
        }
    }

    fun get(windowId: String, terminalId: String): TabbedTerminalState? = states[key(windowId, terminalId)]

    fun remove(windowId: String, terminalId: String) {
        // Unregister from the MCP registry BEFORE dispose so MCP request threads
        // never resolve a tab to a disposed state.
        states.remove(key(windowId, terminalId))?.let { state ->
            McpTerminalRegistry.unregister(state)
            state.dispose()
        }
    }

    fun contains(windowId: String, terminalId: String): Boolean = key(windowId, terminalId) in states

    fun removeAllForWindow(windowId: String): Int {
        val prefix = "$windowId:"
        val keysToRemove = states.keys.filter { it.startsWith(prefix) }
        keysToRemove.forEach { key ->
            states.remove(key)?.let { state ->
                McpTerminalRegistry.unregister(state)
                state.dispose()
            }
        }
        clearSidebarConfigTrackingForWindow(windowId)
        logger.debug(LogCategory.TERMINAL, "Removed terminals for window", mapOf("count" to keysToRemove.size, "windowId" to windowId))
        return keysToRemove.size
    }

    fun sendInput(windowId: String, terminalId: String, bytes: ByteArray): Boolean {
        val state = states[key(windowId, terminalId)] ?: return false
        state.sendInput(bytes)
        return true
    }

    fun sendCtrlC(windowId: String, terminalId: String): Boolean {
        return sendInput(windowId, terminalId, byteArrayOf(0x03))
    }

    fun closeActiveTab(windowId: String, terminalId: String): Boolean {
        val state = states[key(windowId, terminalId)] ?: return false
        state.closeActiveTab()
        return true
    }

    fun runCommand(windowId: String, terminalId: String, command: String): Boolean {
        val state = states[key(windowId, terminalId)] ?: return false
        val commandWithEnter = "$command\n"
        state.sendInput(commandWithEnter.toByteArray(Charsets.UTF_8))
        return true
    }

    /**
     * Write a command to ONE tab, rather than to whichever is active.
     *
     * BossTerm's `sendInput(bytes, tabId)` returns whether the tab was found, so unlike the
     * active-tab form above this can report a miss instead of writing somewhere unintended. That
     * is the point of it: a consumer targeting a tab it owns had to `switchToTab` first, and a
     * failed switch put the write in whatever tab the user was looking at.
     */
    fun runCommandInTab(windowId: String, terminalId: String, command: String, tabId: String): Boolean {
        val state = states[key(windowId, terminalId)] ?: return false
        return state.sendInput("$command\n".toByteArray(Charsets.UTF_8), tabId)
    }

    /** Interrupt ONE tab. See [runCommandInTab]; a stray Ctrl-C is the sharper version of the same risk. */
    fun sendCtrlCToTab(windowId: String, terminalId: String, tabId: String): Boolean {
        val state = states[key(windowId, terminalId)] ?: return false
        return state.sendInput(byteArrayOf(0x03), tabId)
    }

    /**
     * Whether the shell in a tab is free to receive a command.
     *
     * Read from BossTerm's command blocks, which are driven by OSC 133 shell integration:
     * `onCommandStarted` opens a block in [BlockState.RUNNING] and `onCommandFinished` closes it.
     * So the last block being RUNNING is a foreground process, precisely rather than by timing.
     *
     * `hasSeenPrompt` is the gate and the reason this is three-valued. Until the shell has emitted
     * a prompt marker there are no blocks to read, and "no blocks" cannot be told apart from "no
     * shell integration on this machine at all". Answering IDLE there would send commands into
     * running processes on every shell without integration, which is the bug this is meant to fix.
     */
    /**
     * [tabActivity] as a stream, mapped off BossTerm's own command-block StateFlow.
     *
     * `hasSeenPrompt` is re-read on every emission rather than observed, because it is a plain
     * flag with no flow behind it. In practice the two move together - the prompt marker that sets
     * it is also what opens and closes blocks - but it means a tab that gains shell integration
     * without any block changing would keep reporting UNKNOWN until the next command. Stated here
     * rather than papered over with a poll.
     */
    fun tabActivityFlow(windowId: String, terminalId: String, tabId: String?): Flow<TerminalTabActivity> {
        val state = states[key(windowId, terminalId)] ?: return flowOf(TerminalTabActivity.UNKNOWN)
        val tab = (if (tabId != null) state.getTabById(tabId) else state.activeTab)
            ?: return flowOf(TerminalTabActivity.UNKNOWN)
        val tracker = tab.commandBlockTracker ?: return flowOf(TerminalTabActivity.UNKNOWN)

        return tracker.blocks.map { blocks ->
            when {
                !tracker.hasSeenPrompt -> TerminalTabActivity.UNKNOWN
                blocks.lastOrNull()?.state == BlockState.RUNNING -> TerminalTabActivity.BUSY
                else -> TerminalTabActivity.IDLE
            }
        }
    }

    fun tabActivity(windowId: String, terminalId: String, tabId: String?): TerminalTabActivity {
        val state = states[key(windowId, terminalId)] ?: return TerminalTabActivity.UNKNOWN
        val tab = (if (tabId != null) state.getTabById(tabId) else state.activeTab) ?: return TerminalTabActivity.UNKNOWN
        val tracker = tab.commandBlockTracker ?: return TerminalTabActivity.UNKNOWN
        if (!tracker.hasSeenPrompt) return TerminalTabActivity.UNKNOWN

        // A prompt has been seen and nothing has run yet: genuinely idle, not unknown.
        val last = tracker.blocks.value.lastOrNull() ?: return TerminalTabActivity.IDLE
        return if (last.state == BlockState.RUNNING) TerminalTabActivity.BUSY else TerminalTabActivity.IDLE
    }

    // Track (windowId, configId) → stable tabId for sidebar terminal tabs
    private val sidebarConfigToTabId = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun sidebarConfigKey(windowId: String, configId: String) = "$windowId:$configId"

    /**
     * Open or reuse a sidebar terminal tab.
     * Uses ShellUtils and RunnerSettingsManager from host (available via shared classloader).
     */
    fun newSidebarTab(
        windowId: String,
        command: String,
        workingDirectory: String? = null,
        configId: String? = null,
        isRerun: Boolean = false
    ): Boolean {
        val terminalExists = contains(windowId, SIDEBAR_TERMINAL_ID)
        logger.debug(LogCategory.TERMINAL, "newSidebarTab", mapOf("windowId" to windowId, "isRerun" to isRerun, "terminalExists" to terminalExists, "configId" to (configId ?: "none"), "command" to command))

        if (isRerun && configId != null) {
            val state = get(windowId, SIDEBAR_TERMINAL_ID) ?: return false
            val configKey = sidebarConfigKey(windowId, configId)
            val tabId = sidebarConfigToTabId[configKey]

            if (tabId != null) {
                logger.debug(LogCategory.TERMINAL, "Re-run: switching to tab, sending Ctrl+C, then command after delay", mapOf("tabId" to tabId))
                state.switchToTab(tabId)
                state.sendCtrlC(tabId)

                val delayMs = getRerunDelayMs()
                val fullCommand = buildCommandWithWorkingDirectory(command, workingDirectory)
                val capturedTabId = tabId
                val capturedWindowId = windowId
                CoroutineScope(Dispatchers.Default).launch {
                    delay(delayMs)
                    if (contains(capturedWindowId, SIDEBAR_TERMINAL_ID)) {
                        val clearCommand = chainCommands("clear", fullCommand)
                        get(capturedWindowId, SIDEBAR_TERMINAL_ID)?.sendInput("$clearCommand\n".toByteArray(Charsets.UTF_8), capturedTabId)
                    }
                }
            } else {
                logger.debug(LogCategory.TERMINAL, "Re-run: no tabId for config, sending to active tab")
                state.sendInput(byteArrayOf(0x03))
                val delayMs = getRerunDelayMs()
                val fullCommand = buildCommandWithWorkingDirectory(command, workingDirectory)
                val capturedWindowId = windowId
                CoroutineScope(Dispatchers.Default).launch {
                    delay(delayMs)
                    if (contains(capturedWindowId, SIDEBAR_TERMINAL_ID)) {
                        val clearCommand = chainCommands("clear", fullCommand)
                        get(capturedWindowId, SIDEBAR_TERMINAL_ID)?.sendInput("$clearCommand\n".toByteArray(Charsets.UTF_8))
                    }
                }
            }
        } else if (!terminalExists) {
            logger.debug(LogCategory.TERMINAL, "First run (panel opening): setting pending command", mapOf("configId" to (configId ?: "none"), "windowId" to windowId))
            setPendingSidebarCommand(windowId, command, workingDirectory, configId)
        } else {
            val state = get(windowId, SIDEBAR_TERMINAL_ID) ?: return false
            logger.debug(LogCategory.TERMINAL, "New config (panel open): creating new tab", mapOf("tabId" to (configId ?: "none")))

            val normalizedCommand = normalizeCommandForWindows(command)
            state.createTab(workingDir = workingDirectory, initialCommand = normalizedCommand, tabId = configId)
            if (configId != null) {
                val configKey = sidebarConfigKey(windowId, configId)
                sidebarConfigToTabId[configKey] = configId
                logger.debug(LogCategory.TERMINAL, "Recorded tabId for config", mapOf("tabId" to configId, "windowId" to windowId))
            }
        }
        return true
    }

    fun registerSidebarTabId(windowId: String, configId: String, tabId: String) {
        val configKey = sidebarConfigKey(windowId, configId)
        sidebarConfigToTabId[configKey] = tabId
        logger.debug(LogCategory.TERMINAL, "Registered tabId for config", mapOf("tabId" to tabId, "configId" to configId, "windowId" to windowId))
    }

    fun removeSidebarConfigTracking(windowId: String, configId: String) {
        val configKey = sidebarConfigKey(windowId, configId)
        sidebarConfigToTabId.remove(configKey)
    }

    fun getConfigIdForSidebarTab(windowId: String, tabId: String): String? {
        val prefix = "$windowId:"
        return sidebarConfigToTabId.entries
            .filter { it.key.startsWith(prefix) && it.value == tabId }
            .map { it.key.removePrefix(prefix) }
            .firstOrNull()
    }

    fun clearSidebarConfigTrackingForWindow(windowId: String) {
        val prefix = "$windowId:"
        val keysToRemove = sidebarConfigToTabId.keys.filter { it.startsWith(prefix) }
        keysToRemove.forEach { sidebarConfigToTabId.remove(it) }
    }

    fun resetAllTerminals(): Int {
        val count = states.size
        states.values.forEach { state ->
            try {
                McpTerminalRegistry.unregister(state)
                state.dispose()
            } catch (e: Exception) {
                logger.warn(LogCategory.TERMINAL, "Error disposing terminal state", error = e)
            }
        }
        states.clear()
        sidebarConfigToTabId.clear()
        _resetGeneration.value++
        logger.info(LogCategory.TERMINAL, "Reset complete: disposed terminal states", mapOf("count" to count, "generation" to _resetGeneration.value))
        return count
    }
}

/**
 * Registry for single EmbeddableTerminal states (used by TerminalContent).
 */
internal object TerminalStateRegistry {
    private val states = mutableMapOf<String, EmbeddableTerminalState>()

    private val _resetGeneration = MutableStateFlow(0)
    val resetGeneration: StateFlow<Int> = _resetGeneration.asStateFlow()

    private fun key(windowId: String, terminalId: String) = "$windowId:$terminalId"

    fun getOrCreate(windowId: String, terminalId: String): EmbeddableTerminalState {
        return states.getOrPut(key(windowId, terminalId)) { EmbeddableTerminalState() }
    }

    fun remove(windowId: String, terminalId: String) {
        states.remove(key(windowId, terminalId))?.dispose()
    }

    fun contains(windowId: String, terminalId: String): Boolean = key(windowId, terminalId) in states

    fun resetAll(): Int {
        val count = states.size
        states.values.forEach { state ->
            try {
                state.dispose()
            } catch (e: Exception) {
                logger.warn(LogCategory.TERMINAL, "Error disposing embeddable terminal state", error = e)
            }
        }
        states.clear()
        _resetGeneration.value++
        logger.info(LogCategory.TERMINAL, "TerminalStateRegistry reset complete", mapOf("count" to count, "generation" to _resetGeneration.value))
        return count
    }
}

/** Pending commands per window to run when sidebar terminal first renders (thread-safe) */
private val pendingRunnerCommands = java.util.concurrent.ConcurrentHashMap<String, PendingSidebarCommand>()

fun setPendingSidebarCommand(windowId: String, command: String, workingDirectory: String?, configId: String? = null) {
    pendingRunnerCommands[windowId] = PendingSidebarCommand(command, workingDirectory, configId)
}

fun consumePendingSidebarCommand(windowId: String): PendingSidebarCommand? {
    return pendingRunnerCommands.remove(windowId)
}

// ============================================================
// SHELL UTILITIES (delegating to host classes via shared classloader)
// ============================================================

/** Check if running on Windows */
internal val isWindows: Boolean by lazy {
    System.getProperty("os.name").lowercase().contains("windows")
}

/**
 * Normalize command for Windows execution (append newline if needed).
 */
internal fun normalizeCommandForWindows(command: String): String {
    return if (isWindows && command.isNotEmpty()) {
        if (command.endsWith("\n") || command.endsWith("\r\n")) command
        else "$command\n"
    } else {
        command
    }
}

/**
 * Build a command with working directory prefix.
 * Uses host's ShellUtils via reflection if available, otherwise falls back to simple cd.
 */
internal fun buildCommandWithWorkingDirectory(command: String, workingDirectory: String?): String {
    if (workingDirectory.isNullOrBlank()) return command
    return try {
        val shellUtilsClass = Class.forName("ai.rever.boss.run.ShellUtils")
        val method = shellUtilsClass.getMethod("buildCommandWithWorkingDirectory", String::class.java, String::class.java)
        val instance = shellUtilsClass.kotlin.objectInstance ?: shellUtilsClass.getDeclaredField("INSTANCE").get(null)
        method.invoke(instance, command, workingDirectory) as String
    } catch (e: Exception) {
        // Fallback: simple cd && command
        "cd ${quoteForShell(workingDirectory)} && $command"
    }
}

/**
 * Chain two commands.
 */
internal fun chainCommands(first: String, second: String): String {
    return try {
        val shellUtilsClass = Class.forName("ai.rever.boss.run.ShellUtils")
        val method = shellUtilsClass.getMethod("chainCommands", String::class.java, String::class.java)
        val instance = shellUtilsClass.kotlin.objectInstance ?: shellUtilsClass.getDeclaredField("INSTANCE").get(null)
        method.invoke(instance, first, second) as String
    } catch (e: Exception) {
        "$first && $second"
    }
}

/**
 * Get rerun delay from RunnerSettingsManager.
 */
internal fun getRerunDelayMs(): Long {
    return try {
        val settingsClass = Class.forName("ai.rever.boss.run.RunnerSettingsManager")
        val instance = settingsClass.kotlin.objectInstance ?: settingsClass.getDeclaredField("INSTANCE").get(null)
        val currentSettingsField = settingsClass.getMethod("getCurrentSettings")
        val stateFlow = currentSettingsField.invoke(instance)
        val valueMethod = stateFlow::class.java.getMethod("getValue")
        val settings = valueMethod.invoke(stateFlow)
        val delayField = settings::class.java.getMethod("getRerunDelayMs")
        delayField.invoke(settings) as Long
    } catch (e: Exception) {
        500L // Default delay
    }
}

private fun quoteForShell(path: String): String {
    return "\"${path.replace("\"", "\\\"")}\""
}
