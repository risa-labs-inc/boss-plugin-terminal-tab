package ai.rever.boss.plugin.dynamic.terminaltab.onboarding

import ai.rever.bossterm.compose.ai.AIAssistants
import ai.rever.boss.plugin.dynamic.terminaltab.TabbedTerminalStateRegistry
import ai.rever.bossterm.compose.ConnectionState
import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

enum class SetupTaskStatus { PENDING, RUNNING, REPAIRING, COMPLETE, NEEDS_ATTENTION }

data class SetupTaskState(
    val id: String,
    val title: String,
    val detail: String,
    val status: SetupTaskStatus = SetupTaskStatus.PENDING,
)

data class BossTermSetupState(
    val sessionId: String? = null,
    val tasks: List<SetupTaskState> = emptyList(),
    val fluckAvailable: Boolean = false,
    val supervisionChecked: Boolean = false,
    val isBackgrounded: Boolean = false,
    val finished: Boolean = false,
    val failureMessage: String? = null,
    val failureOutput: String? = null,
    val outputLines: List<String> = emptyList(),
    val authenticateGitHubAfterSetup: Boolean = false,
    val awaitingGitHubAuthentication: Boolean = false,
    val setupTerminalContainerId: String? = null,
    val setupTerminalId: String? = null,
    val agentDebugRequestInFlight: Boolean = false,
    val agentDebugActive: Boolean = false,
    val agentDebugAwaitingVerification: Boolean = false,
    val agentDebugError: String? = null,
    val debugEligibilityRevision: Int = 0,
) {
    val isRunning: Boolean get() =
        sessionId != null && (
            agentDebugRequestInFlight || agentDebugActive || agentDebugAwaitingVerification ||
                (!finished && failureMessage == null && !awaitingGitHubAuthentication)
            )
    val completedTaskCount: Int get() = tasks.count { it.status == SetupTaskStatus.COMPLETE }
    val progress: Float get() = if (tasks.isEmpty()) 0f else completedTaskCount.toFloat() / tasks.size
    val activeTask: SetupTaskState? get() = tasks.firstOrNull {
        it.status == SetupTaskStatus.RUNNING || it.status == SetupTaskStatus.REPAIRING
    }
}

data class SetupFailure(
    val sessionId: String,
    val taskId: String,
    val taskTitle: String,
    val attempt: Int,
    val exitCode: Int,
    val output: String,
    val platform: TargetOs,
)

enum class SetupRepair { RETRY, REFRESH_PACKAGES_AND_RETRY, STOP }
enum class SetupTerminalActivity { BUSY, IDLE, UNKNOWN, HANDOFF }

data class SetupDebugRequest(
    val requestId: String,
    val sessionId: String,
    val windowId: String,
    val terminalId: String,
    val task: SetupTaskState,
    val output: String,
)

data class SetupDebugResult(val completed: Boolean, val error: String? = null)
data class SetupTerminalScrollback(val lines: List<String>, val totalLines: Int)

/** Optional host bridge. BossTerm itself never depends on, or assumes, Fluck Agent. */
interface BossTermSetupSupervisor {
    suspend fun start(sessionId: String, windowId: String, tasks: List<SetupTaskState>): Boolean
    suspend fun taskChanged(sessionId: String, task: SetupTaskState) = Unit
    suspend fun repair(failure: SetupFailure): SetupRepair = SetupRepair.STOP
    suspend fun finish(sessionId: String, success: Boolean, message: String?) = Unit
    suspend fun debugAndFix(request: SetupDebugRequest, onAccepted: () -> Unit): SetupDebugResult =
        SetupDebugResult(false, "Fluck debugging is unavailable")
    fun resumeDebug(requestId: String): Boolean = false
}

/**
 * Process-lifetime owner for onboarding installation.
 *
 * The wizard is a separate window. Keeping the Job here lets the user explicitly move an active
 * setup into the host's bottom bar without cancelling it, then reopen the same live progress.
 */
object BossTermSetupController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("bossterm-setup"))
    private val _state = MutableStateFlow(BossTermSetupState())
    val state: StateFlow<BossTermSetupState> = _state.asStateFlow()
    @Volatile
    private var retainedRequest: SetupRequest? = null
    @Volatile
    private var terminalWindowId: String? = null
    @Volatile
    private var terminalContainerId: String? = null
    @Volatile
    private var terminalTabId: String? = null
    @Volatile
    private var terminalReadyMarker: String? = null
    @Volatile
    private var terminalReadyCommand: String? = null
    @Volatile
    private var sessionSupervisor: BossTermSetupSupervisor? = null
    private val terminalLock = Any()
    @Volatile
    private var terminalReady = CompletableDeferred<Unit>()
    @Volatile
    private var activeSessionJob: Job? = null
    @Volatile
    private var terminalReadyJob: Job? = null
    private var pendingTerminalCommand: PendingTerminalCommand? = null
    @Volatile
    internal var terminalCommandSubmittedForTest: Boolean = false
        private set
    @Volatile
    private var lastTerminalOutputForTest: String = ""
    @Volatile
    private var handoffRequestedSessionId: String? = null
    @Volatile
    private var activeHandoffRequestId: String? = null
    @Volatile
    private var resumeDebugAndVerifyRequested = false
    @Volatile
    private var terminalBoundarySafe = true

    private fun terminalStateOrNull(): TabbedTerminalState? {
        val windowId = terminalWindowId ?: return null
        val containerId = terminalContainerId ?: return null
        return TabbedTerminalStateRegistry.get(windowId, containerId)
    }

    private fun isTerminalConnected(): Boolean {
        val tabId = terminalTabId ?: return false
        return terminalStateOrNull()?.getTabById(tabId)?.connectionState?.value is ConnectionState.Connected
    }

    private fun disposeTerminal() {
        val windowId = terminalWindowId
        val containerId = terminalContainerId
        if (windowId != null && containerId != null) TabbedTerminalStateRegistry.remove(windowId, containerId)
        terminalWindowId = null
        terminalContainerId = null
        terminalTabId = null
        terminalReadyMarker = null
        terminalReadyCommand = null
    }

    @Synchronized
    fun start(
        windowId: String,
        selections: OnboardingSelections,
        installed: InstalledTools,
        settingsManager: SettingsManager,
        supervisor: BossTermSetupSupervisor? = null,
    ): Boolean {
        if (_state.value.isRunning || _state.value.awaitingGitHubAuthentication) return false
        if (!supportsSelections(selections, TargetOs.current())) return false

        val sessionId = UUID.randomUUID().toString()
        disposeTerminal()
        terminalReady = CompletableDeferred()
        synchronized(terminalLock) { pendingTerminalCommand = null }
        terminalCommandSubmittedForTest = false
        lastTerminalOutputForTest = ""
        activeHandoffRequestId = null
        handoffRequestedSessionId = null
        resumeDebugAndVerifyRequested = false
        terminalBoundarySafe = true
        // Setup runs in the visible PTY so sudo/authentication can prompt there; credentials are
        // neither accepted by this controller nor written to disk.
        retainedRequest = SetupRequest(sessionId, windowId, selections, installed, settingsManager, supervisor)
        sessionSupervisor = supervisor
        val plans = buildTaskPlan(selections, installed)
        _state.value = BossTermSetupState(
            sessionId = sessionId,
            tasks = plans.map { it.state },
            authenticateGitHubAfterSetup = selections.authenticateGitHub,
        )
        initializeTerminal(windowId, sessionId)

        activeSessionJob = scope.launch {
            val supervised = runCatching {
                supervisor?.start(sessionId, windowId, plans.map { it.state }) == true
            }.getOrDefault(false)
            updateSession(sessionId) { it.copy(fluckAvailable = supervised, supervisionChecked = true) }

            var terminalFailure: String? = null
            var terminalOutput: String? = null
            for ((index, plan) in plans.withIndex()) {
                updateTask(sessionId, index, SetupTaskStatus.RUNNING, supervisor)
                appendOutput(sessionId, "Starting ${plan.state.title}")
                val result = executeTaskWithRepairs(plan, index, sessionId, supervisor, supervised)

                if (result.exitCode != 0) {
                    terminalFailure = "${plan.state.title} needs attention"
                    terminalOutput = result.output.takeLast(MAX_FAILURE_OUTPUT)
                    updateTask(sessionId, index, SetupTaskStatus.NEEDS_ATTENTION, supervisor)
                    break
                }
                updateTask(sessionId, index, SetupTaskStatus.COMPLETE, supervisor)
                appendOutput(sessionId, "Completed ${plan.state.title}")
            }

            val success = terminalFailure == null
            if (success && selections.authenticateGitHub) {
                updateSession(sessionId) { it.copy(awaitingGitHubAuthentication = true) }
            } else {
                updateSession(sessionId) {
                    it.copy(finished = true, failureMessage = terminalFailure, failureOutput = terminalOutput)
                }
                if (success) runCatching { settingsManager.updateSetting { copy(onboardingCompleted = true) } }
                runCatching { supervisor?.finish(sessionId, success, terminalFailure) }
            }
        }
        return true
    }

    /** Restarts the last failed setup with the exact selections and detection snapshot it used. */
    fun retry(): Boolean {
        val savedRequest = retainedRequest ?: return false
        if (_state.value.isRunning || _state.value.failureMessage == null) return false
        val request = savedRequest
        return start(
            request.windowId,
            request.selections,
            request.installed,
            request.settingsManager,
            request.supervisor,
        )
    }

    /** Completes the user-owned foreground GitHub authentication stage. */
    fun finishInteractiveGitHubAuth(completed: Boolean): Boolean {
        val current = _state.value
        val sessionId = current.sessionId ?: return false
        if (!current.awaitingGitHubAuthentication) return false
        val supervisor = retainedRequest?.takeIf { it.sessionId == sessionId }?.supervisor
        val message = if (completed) null else "GitHub authentication was skipped"
        updateSession(sessionId) { it.copy(awaitingGitHubAuthentication = false, finished = true) }
        scope.launch {
            retainedRequest?.takeIf { it.sessionId == sessionId }?.settingsManager?.let { settings ->
                runCatching { settings.updateSetting { copy(onboardingCompleted = true) } }
            }
            // Authentication is an optional foreground continuation. An explicit skip completes
            // setup successfully while preserving an informational message for the supervisor.
            runCatching { supervisor?.finish(sessionId, true, message) }
        }
        return true
    }

    private suspend fun executeTaskWithRepairs(
        plan: TaskPlan,
        index: Int,
        sessionId: String,
        supervisor: BossTermSetupSupervisor?,
        supervised: Boolean,
    ): CommandResult {
        var result = runTaskWithHandoff(plan, sessionId, supervisor)
        var repairAttempt = 0
        while (result.exitCode != 0 && result.exitCode !in USER_CANCEL_EXIT_CODES &&
            isTerminalConnected() && supervised && repairAttempt < MAX_REPAIR_ATTEMPTS
        ) {
            repairAttempt += 1
            updateTask(sessionId, index, SetupTaskStatus.REPAIRING, supervisor)
            val decision = runCatching {
                supervisor?.repair(
                    SetupFailure(
                        sessionId,
                        plan.state.id,
                        plan.state.title,
                        repairAttempt,
                        result.exitCode,
                        result.output.takeLast(MAX_FAILURE_OUTPUT),
                        TargetOs.current(),
                    ),
                ) ?: SetupRepair.STOP
            }.getOrDefault(SetupRepair.STOP)
            if (decision == SetupRepair.REFRESH_PACKAGES_AND_RETRY) {
                appendOutput(sessionId, "Fluck Agent is refreshing package information")
                result = runCommand(packageRefreshCommand(TargetOs.current()), sessionId)
                val handedOff = handoffRequestedSessionId == sessionId
                if (handedOff) {
                    result = runAgentHandoff(sessionId, plan, result, supervisor)
                    if (result.exitCode == 0) return result
                }
                if (result.exitCode != 0) {
                    appendOutput(sessionId, "Package information refresh failed")
                    continue
                }
            }
            if (decision == SetupRepair.STOP) {
                appendOutput(sessionId, "This step needs your attention before setup can continue")
                break
            }
            appendOutput(sessionId, "Fluck Agent is retrying ${plan.state.title}")
            result = runTaskWithHandoff(plan, sessionId, supervisor)
        }
        return result
    }

    private suspend fun runTaskWithHandoff(
        plan: TaskPlan,
        sessionId: String,
        supervisor: BossTermSetupSupervisor?,
    ): CommandResult {
        val result = runTask(plan, sessionId)
        return if (handoffRequestedSessionId == sessionId) {
            runAgentHandoff(sessionId, plan, result, supervisor)
        } else {
            result
        }
    }

    internal fun supportsSelections(selections: OnboardingSelections, targetOs: TargetOs): Boolean {
        val shell = resolveConfiguredShell(selections.shell, targetOs)
        val prompt = selections.shellCustomization
        return when {
            targetOs.isWindows && prompt in setOf(
                ShellCustomizationChoice.OH_MY_ZSH,
                ShellCustomizationChoice.PREZTO,
            ) -> false
            !targetOs.isWindows && prompt == ShellCustomizationChoice.OH_MY_POSH -> false
            prompt in setOf(ShellCustomizationChoice.OH_MY_ZSH, ShellCustomizationChoice.PREZTO) &&
                shell != ShellChoice.ZSH -> false
            shell == ShellChoice.CMD && prompt !in setOf(
                ShellCustomizationChoice.NONE,
                ShellCustomizationChoice.KEEP_EXISTING,
            ) -> false
            else -> true
        }
    }

    fun sendToBackground() {
        _state.update { current ->
            if (current.sessionId != null) current.copy(isBackgrounded = true) else current
        }
    }

    fun bringToForeground() {
        _state.update { it.copy(isBackgrounded = false) }
    }

    @Synchronized
    fun clearFinished() {
        var current: BossTermSetupState
        while (true) {
            current = _state.value
            if (current.isRunning || current.awaitingGitHubAuthentication) return
            if (_state.compareAndSet(current, BossTermSetupState())) break
        }
        if (retainedRequest?.sessionId == current.sessionId) retainedRequest = null
        sessionSupervisor = null
        disposeTerminal()
    }

    /** Stops an active setup when this dynamic plugin is disabled or replaced. Idempotent. */
    @Synchronized
    fun abortForPluginDispose() {
        val sessionId = _state.value.sessionId ?: return
        activeSessionJob?.cancel()
        activeSessionJob = null
        terminalReadyJob?.cancel()
        terminalReadyJob = null
        val tabId = terminalTabId
        if (tabId != null) terminalStateOrNull()?.sendInput(byteArrayOf(0x03), tabId)
        val pending = synchronized(terminalLock) {
            pendingTerminalCommand.also {
                pendingTerminalCommand = null
                activeHandoffRequestId = null
                terminalStateOrNull()?.let(McpTerminalRegistry::unregister)
            }
        }
        pending?.completion?.complete(CommandResult(130, "Terminal setup stopped because the plugin was disabled"))
        handoffRequestedSessionId = null
        resumeDebugAndVerifyRequested = false
        retainedRequest = null
        sessionSupervisor = null
        disposeTerminal()
        terminalReady = CompletableDeferred()
        terminalBoundarySafe = true
        updateSession(sessionId) { BossTermSetupState() }
    }

    /** Hard isolation for tests that exercise this process-lifetime singleton with a real PTY. */
    internal fun resetForTest() {
        val worker = activeSessionJob
        abortForPluginDispose()
        if (worker != null) runBlocking { worker.join() }
        _state.value = BossTermSetupState()
    }

    private fun terminalOutput(sessionId: String?, chunk: String) {
        if (sessionId == null || _state.value.sessionId != sessionId) return
        val pending = synchronized(terminalLock) { pendingTerminalCommand }
        if (pending != null) {
            synchronized(pending.output) { pending.output.setLength(0); pending.output.append(chunk.takeLast(MAX_CAPTURED_OUTPUT)) }
            pending.parser.acceptSnapshot(chunk)?.let { exitCode ->
                if (synchronized(terminalLock) {
                        if (pendingTerminalCommand === pending) {
                            pendingTerminalCommand = null
                            true
                        } else false
                    }) {
                    val output = synchronized(pending.output) { pending.output.toString() }
                    lastTerminalOutputForTest = output
                    // Only the wrapper's random completion marker proves that an interrupted
                    // command reached a shell boundary. Timeout and cleanup paths do not.
                    terminalBoundarySafe = true
                    pending.completion.complete(CommandResult(exitCode, output))
                    notifyDebugEligibilityChanged(sessionId)
                }
            }
        }
    }

    fun terminalState(windowId: String, containerId: String): TabbedTerminalState? =
        TabbedTerminalStateRegistry.get(windowId, containerId)

    /** Called from the committed setup renderer so tab creation stays on Compose's UI thread. */
    fun ensureSetupTerminalTab(windowId: String, containerId: String): Boolean {
        if (terminalWindowId != windowId || terminalContainerId != containerId) return false
        val tabId = terminalTabId ?: return false
        val readyCommand = terminalReadyCommand ?: return false
        val terminal = terminalState(windowId, containerId) ?: return false
        if (!terminal.isInitialized) return false
        return terminal.getTabById(tabId) != null ||
            terminal.createTab(initialCommand = readyCommand, tabId = tabId, activate = true) == tabId
    }

    fun hasSetupTerminal(terminalId: String): Boolean =
        _state.value.setupTerminalId == terminalId && terminalTabId == terminalId && isTerminalConnected()

    fun hasSetupTerminalHandoff(terminalId: String, requestId: String): Boolean =
        synchronized(terminalLock) {
            hasSetupTerminal(terminalId) && activeHandoffRequestId == requestId &&
                (_state.value.agentDebugRequestInFlight || _state.value.agentDebugActive)
        }

    fun sendSetupTerminalInput(terminalId: String, requestId: String, bytes: ByteArray): Boolean {
        return synchronized(terminalLock) {
            if (!hasSetupTerminalHandoff(terminalId, requestId)) return@synchronized false
            terminalStateOrNull()?.sendInput(bytes, terminalId) == true
        }
    }

    fun interruptSetupTerminal(terminalId: String, requestId: String): Boolean {
        return synchronized(terminalLock) {
            if (!hasSetupTerminalHandoff(terminalId, requestId)) return@synchronized false
            terminalStateOrNull()?.sendInput(byteArrayOf(0x03), terminalId) == true
        }
    }

    fun setupTerminalActivity(terminalId: String): SetupTerminalActivity = when {
        !hasSetupTerminal(terminalId) -> SetupTerminalActivity.UNKNOWN
        !terminalBoundarySafe -> SetupTerminalActivity.UNKNOWN
        _state.value.agentDebugRequestInFlight || _state.value.agentDebugActive -> SetupTerminalActivity.HANDOFF
        synchronized(terminalLock) { pendingTerminalCommand != null } -> SetupTerminalActivity.BUSY
        else -> SetupTerminalActivity.IDLE
    }

    fun canAskFluckToDebugAndFix(): Boolean {
        val current = _state.value
        return current.fluckAvailable && current.setupTerminalId != null &&
            hasSetupTerminal(current.setupTerminalId) && terminalBoundarySafe &&
            !current.agentDebugRequestInFlight &&
            !current.agentDebugActive && !current.agentDebugAwaitingVerification &&
            (synchronized(terminalLock) { pendingTerminalCommand != null } || current.failureMessage != null)
    }

    /** Requests an exclusive terminal handoff. Active setup work is interrupted at its sentinel boundary. */
    fun askFluckToDebugAndFix(): Boolean {
        val sessionId = _state.value.sessionId ?: return false
        val interrupted = synchronized(terminalLock) {
            val current = _state.value
            val terminalId = current.setupTerminalId
            if (
                current.sessionId != sessionId || !current.fluckAvailable || terminalId == null ||
                !hasSetupTerminal(terminalId) || !terminalBoundarySafe || current.agentDebugRequestInFlight ||
                current.agentDebugActive || current.agentDebugAwaitingVerification
            ) return false
            val pending = pendingTerminalCommand
            if (pending == null && current.failureMessage == null) return false

            activeHandoffRequestId = UUID.randomUUID().toString()
            if (pending != null) {
                handoffRequestedSessionId = sessionId
                val tabId = terminalTabId
                if (tabId == null || terminalStateOrNull()?.sendInput(byteArrayOf(0x03), tabId) != true) {
                    activeHandoffRequestId = null
                    handoffRequestedSessionId = null
                    return false
                }
            } else {
                handoffRequestedSessionId = null
            }
            updateSession(sessionId) {
                it.copy(agentDebugRequestInFlight = true, agentDebugActive = false, agentDebugError = null)
            }
            pending
        }
        if (interrupted != null) {
            scope.launch {
                delay(HANDOFF_BOUNDARY_TIMEOUT_MS)
                val stillBusy = synchronized(terminalLock) { pendingTerminalCommand === interrupted }
                if (stillBusy && handoffRequestedSessionId == sessionId) {
                    terminalBoundarySafe = false
                    handoffRequestedSessionId = null
                    synchronized(terminalLock) { activeHandoffRequestId = null }
                    interrupted.completion.complete(
                        CommandResult(HANDOFF_FAILED_EXIT_CODE, "Could not pause the active terminal command safely"),
                    )
                    updateSession(sessionId) {
                        it.copy(
                            agentDebugRequestInFlight = false,
                            agentDebugActive = false,
                            agentDebugError = "Could not prove a safe shell boundary. Restart setup before debugging again.",
                        )
                    }
                }
            }
        } else {
            resumeFailedTaskWithAgent(sessionId)
        }
        return true
    }

    fun resumeActiveDebugAndVerify(): Boolean {
        val current = _state.value
        if (!current.agentDebugActive) return false
        val requestId = synchronized(terminalLock) { activeHandoffRequestId } ?: return false
        val supervisor = sessionSupervisor ?: return false
        resumeDebugAndVerifyRequested = true
        return supervisor.resumeDebug(requestId).also { accepted ->
            if (!accepted) resumeDebugAndVerifyRequested = false
        }
    }

    private fun resumeFailedTaskWithAgent(sessionId: String) {
        val request = retainedRequest?.takeIf { it.sessionId == sessionId }
            ?: run {
                clearHandoffRequest(sessionId, "Setup retry information is unavailable")
                return
            }
        val plans = buildTaskPlan(request.selections, request.installed)
        val failedIndex = _state.value.tasks.indexOfFirst { it.status == SetupTaskStatus.NEEDS_ATTENTION }
        val plan = plans.getOrNull(failedIndex)
            ?: run {
                clearHandoffRequest(sessionId, "Failed setup step is unavailable")
                return
            }
        activeSessionJob = scope.launch {
            val result = runAgentHandoff(
                sessionId,
                plan,
                CommandResult(-1, _state.value.failureOutput.orEmpty()),
                request.supervisor,
            )
            var success = result.exitCode == 0
            var failureOutput = result.output
            updateTask(sessionId, failedIndex, if (success) SetupTaskStatus.COMPLETE else SetupTaskStatus.NEEDS_ATTENTION, request.supervisor)
            if (success) {
                updateSession(sessionId) { it.copy(finished = false, failureMessage = null, failureOutput = null) }
                for (index in (failedIndex + 1) until plans.size) {
                    updateTask(sessionId, index, SetupTaskStatus.RUNNING, request.supervisor)
                    val remaining = executeTaskWithRepairs(
                        plans[index],
                        index,
                        sessionId,
                        request.supervisor,
                        _state.value.fluckAvailable,
                    )
                    success = remaining.exitCode == 0
                    failureOutput = remaining.output
                    updateTask(
                        sessionId,
                        index,
                        if (success) SetupTaskStatus.COMPLETE else SetupTaskStatus.NEEDS_ATTENTION,
                        request.supervisor,
                    )
                    if (!success) break
                }
            }
            if (success && request.selections.authenticateGitHub) {
                updateSession(sessionId) {
                    it.copy(finished = false, awaitingGitHubAuthentication = true, failureMessage = null, failureOutput = null)
                }
            } else {
                updateSession(sessionId) {
                    it.copy(
                        finished = true,
                        failureMessage = if (success) null else "Setup still needs attention",
                        failureOutput = if (success) null else failureOutput.takeLast(MAX_FAILURE_OUTPUT),
                    )
                }
                if (success) {
                    runCatching { request.settingsManager.updateSetting { copy(onboardingCompleted = true) } }
                }
                runCatching { request.supervisor?.finish(sessionId, success, if (success) null else "Setup still needs attention") }
            }
        }
    }

    private fun clearHandoffRequest(sessionId: String, error: String): Boolean {
        synchronized(terminalLock) { activeHandoffRequestId = null }
        handoffRequestedSessionId = null
        resumeDebugAndVerifyRequested = false
        updateSession(sessionId) {
            it.copy(
                agentDebugRequestInFlight = false,
                agentDebugActive = false,
                agentDebugAwaitingVerification = false,
                agentDebugError = error,
            )
        }
        return false
    }

    private fun handoffFailure(sessionId: String, error: String): CommandResult {
        clearHandoffRequest(sessionId, error)
        return CommandResult(HANDOFF_FAILED_EXIT_CODE, error)
    }

    private suspend fun runAgentHandoff(
        sessionId: String,
        plan: TaskPlan,
        failure: CommandResult,
        supervisor: BossTermSetupSupervisor?,
    ): CommandResult {
        val terminalId = terminalTabId
            ?: return handoffFailure(sessionId, "Setup terminal is unavailable")
        handoffRequestedSessionId = null
        val requestId = synchronized(terminalLock) { activeHandoffRequestId }
            ?: return handoffFailure(sessionId, "Fluck handoff request is unavailable")
        val windowId = terminalWindowId
            ?: return handoffFailure(sessionId, "Setup window is unavailable")
        // The dedicated setup tools resolve this terminal through the controller and require this
        // request token. Keep the setup PTY out of the generic MCP registry throughout handoff.
        val result = runCatching {
            supervisor?.debugAndFix(
                SetupDebugRequest(
                    requestId,
                    sessionId,
                    windowId,
                    terminalId,
                    plan.state,
                    failure.output.takeLast(MAX_FAILURE_OUTPUT),
                ),
            ) {
                updateSession(sessionId) {
                    it.copy(
                        agentDebugRequestInFlight = false,
                        agentDebugActive = true,
                        agentDebugError = null,
                        isBackgrounded = true,
                    )
                }
            } ?: SetupDebugResult(false, "Fluck debugging is unavailable")
        }.getOrElse { SetupDebugResult(false, it.message ?: "Fluck debugging failed") }
        val resumeWasRequested = resumeDebugAndVerifyRequested
        // Revoke guarded MCP writes before verification enters this PTY queue.
        synchronized(terminalLock) {
            terminalStateOrNull()?.let(McpTerminalRegistry::unregister)
            activeHandoffRequestId = null
        }
        resumeDebugAndVerifyRequested = false
        updateSession(sessionId) {
            it.copy(
                agentDebugRequestInFlight = false,
                agentDebugActive = false,
                agentDebugError = result.error,
            )
        }
        if (!result.completed) {
            return CommandResult(HANDOFF_FAILED_EXIT_CODE, result.error ?: "Fluck could not complete debugging")
        }
        if (!resumeWasRequested) {
            return CommandResult(HANDOFF_FAILED_EXIT_CODE, "Fluck debugging ended without an explicit resume")
        }
        resumeDebugAndVerifyRequested = false
        terminalBoundarySafe = false
        terminalStateOrNull()?.sendInput(byteArrayOf(0x03), terminalId)
        delay(TERMINAL_BOUNDARY_PROBE_DELAY_MS)
        val boundary = withTimeoutOrNull(HANDOFF_BOUNDARY_TIMEOUT_MS) {
            runCommand(if (TargetOs.current().isWindows) "\$null = 0" else ":", sessionId)
        }
        if (boundary?.exitCode != 0 || !terminalBoundarySafe) {
            return CommandResult(
                HANDOFF_FAILED_EXIT_CODE,
                "Could not confirm that the terminal returned to its shell after Fluck debugging",
            )
        }
        val verification = plan.verificationCommand
            ?: return CommandResult(HANDOFF_FAILED_EXIT_CODE, "This setup step has no safe verification command")
        updateSession(sessionId) { it.copy(agentDebugAwaitingVerification = true) }
        appendOutput(sessionId, "Verifying Fluck's fix for ${plan.state.title}")
        return runCommand(verification, sessionId).also {
            updateSession(sessionId) { current -> current.copy(agentDebugAwaitingVerification = false) }
        }
    }

    fun setupTerminalScrollback(
        terminalId: String,
        requestId: String,
        lines: Int = 200,
    ): SetupTerminalScrollback? = synchronized(terminalLock) {
        if (!hasSetupTerminalHandoff(terminalId, requestId)) return@synchronized null
        val snapshot = terminalStateOrNull()?.getTabById(terminalId)?.textBuffer?.createSnapshot()
            ?: return@synchronized null
        val all = (snapshot.historyLines + snapshot.screenLines).map { it.text.trimEnd() }
        SetupTerminalScrollback(all.takeLast(lines.coerceIn(1, 2_000)), all.size)
    }

    internal fun terminalCapturedOutputForTest(): String = synchronized(terminalLock) {
        pendingTerminalCommand?.let { pending ->
            synchronized(pending.output) { pending.output.toString() }
        } ?: lastTerminalOutputForTest
    }

    private fun initializeTerminal(windowId: String, sessionId: String) {
        val containerId = "bossterm-setup-$sessionId"
        val setupTabId = "bossterm-setup-tab-$sessionId"
        val readyToken = UUID.randomUUID().toString().replace("-", "")
        val readyMarker = "__BOSS_SETUP_READY_${readyToken}__"
        terminalWindowId = windowId
        terminalContainerId = containerId
        terminalTabId = setupTabId
        terminalReadyMarker = readyMarker
        terminalReadyCommand = if (TargetOs.current().isWindows) "echo $readyMarker" else "printf '\\n$readyMarker\\n'"
        val terminal = TabbedTerminalStateRegistry.getOrCreate(windowId, containerId)
        // Setup commands remain private to the dedicated, request-token-guarded MCP tools.
        McpTerminalRegistry.unregister(terminal)
        updateSession(sessionId) {
            it.copy(setupTerminalContainerId = containerId, setupTerminalId = null)
        }
        terminalReadyJob = scope.launch {
            repeat(TERMINAL_READY_POLL_ATTEMPTS) {
                val terminal = terminalStateOrNull()
                if (terminal != null) {
                    val tab = terminal.getTabById(setupTabId)
                    if (tab?.connectionState?.value is ConnectionState.Connected) {
                        val snapshot = tab.textBuffer.createSnapshot()
                        val output = joinTerminalLines(
                            (snapshot.historyLines + snapshot.screenLines).map { it.text.trimEnd() to it.isWrapped },
                        )
                        if (output.lineSequence().any { it.trim() == readyMarker }) {
                            updateSession(sessionId) { it.copy(setupTerminalId = setupTabId) }
                            terminalReady.complete(Unit)
                            return@launch
                        }
                    }
                }
                if (_state.value.sessionId != sessionId) {
                    return@launch
                }
                delay(TERMINAL_READY_POLL_MS)
            }
        }
    }

    /** Harmless task-runner seam for PTY lifecycle tests; callers supply the complete test script. */
    internal fun startTerminalTaskForTest(
        command: String,
        verificationCommand: String? = null,
        supervisor: BossTermSetupSupervisor? = null,
        authenticateGitHub: Boolean = false,
    ): Boolean {
        if (_state.value.isRunning) return false
        val sessionId = UUID.randomUUID().toString()
        disposeTerminal()
        terminalReady = CompletableDeferred()
        synchronized(terminalLock) { pendingTerminalCommand = null }
        terminalCommandSubmittedForTest = false
        lastTerminalOutputForTest = ""
        activeHandoffRequestId = null
        handoffRequestedSessionId = null
        terminalBoundarySafe = true
        val plan = TaskPlan(SetupTaskState("test", "Test terminal task", "Test only"), command, verificationCommand)
        sessionSupervisor = supervisor
        _state.value = BossTermSetupState(
            sessionId = sessionId,
            tasks = listOf(plan.state),
            fluckAvailable = supervisor != null,
            supervisionChecked = true,
            authenticateGitHubAfterSetup = authenticateGitHub,
        )
        initializeTerminal("test-window", sessionId)
        activeSessionJob = scope.launch {
            updateTask(sessionId, 0, SetupTaskStatus.RUNNING, null)
            val result = executeTaskWithRepairs(plan, 0, sessionId, supervisor, supervisor != null)
            val success = result.exitCode == 0
            updateTask(sessionId, 0, if (success) SetupTaskStatus.COMPLETE else SetupTaskStatus.NEEDS_ATTENTION, null)
            updateSession(sessionId) {
                it.copy(
                    finished = !success || !authenticateGitHub,
                    awaitingGitHubAuthentication = success && authenticateGitHub,
                    failureMessage = if (success) null else "Test terminal task needs attention",
                    failureOutput = result.output.takeLast(MAX_FAILURE_OUTPUT),
                )
            }
        }
        return true
    }

    private suspend fun updateTask(
        sessionId: String,
        index: Int,
        status: SetupTaskStatus,
        supervisor: BossTermSetupSupervisor?,
    ) {
        var updatedTask: SetupTaskState? = null
        updateSession(sessionId) { current ->
            if (index !in current.tasks.indices) return@updateSession current
            val tasks = current.tasks.toMutableList()
            tasks[index] = tasks[index].copy(status = status)
            updatedTask = tasks[index]
            current.copy(tasks = tasks)
        }
        updatedTask?.let { task -> runCatching { supervisor?.taskChanged(sessionId, task) } }
    }

    private data class SetupRequest(
        val sessionId: String,
        val windowId: String,
        val selections: OnboardingSelections,
        val installed: InstalledTools,
        val settingsManager: SettingsManager,
        val supervisor: BossTermSetupSupervisor?,
    )

    internal data class TaskPlan(
        val state: SetupTaskState,
        val command: String,
        val verificationCommand: String? = null,
    )

    internal fun buildTaskPlan(
        selections: OnboardingSelections,
        installed: InstalledTools,
        targetOs: TargetOs = TargetOs.current(),
    ): List<TaskPlan> {
        val packageManager = resolvePackageManager(selections.packageManager, installed, targetOs)
        val effectiveInstalled = installed.withPackageManager(packageManager)
        val configuredShell = resolveConfiguredShell(selections.shell, targetOs)
        fun command(selection: OnboardingSelections) = buildInstallCommand(selection, effectiveInstalled, targetOs)
        val nothingElse = OnboardingSelections(
            packageManager = PackageManagerChoice.NONE,
            shell = ShellChoice.KEEP_CURRENT,
            shellCustomization = ShellCustomizationChoice.KEEP_EXISTING,
            installGit = false,
            installGitHubCLI = false,
            authenticateGitHub = false,
            aiAssistants = emptySet(),
        )
        return listOfNotNull(
            packageManagerTask(packageManager, installed, targetOs),
            TaskPlan(
                SetupTaskState("preferences", "Save terminal preferences", selections.shell.displayName),
                command(nothingElse.copy(shell = selections.shell)),
                shellVerification(selections.shell, targetOs),
            ),
            TaskPlan(
                SetupTaskState("prompt", "Configure ${selections.shellCustomization.displayName}", "Shell prompt"),
                withShellEnvironment(
                    command(nothingElse.copy(shellCustomization = selections.shellCustomization)) +
                        ensurePromptActivationCommand(selections.shellCustomization, configuredShell, targetOs),
                    configuredShell,
                    targetOs,
                ),
                promptVerification(selections.shellCustomization, configuredShell, targetOs),
            ),
            selections.installGit.takeIf { it }?.let {
                TaskPlan(
                    SetupTaskState("git", "Verify Git", if (installed.git) "Already installed" else "Install Git"),
                    command(nothingElse.copy(installGit = true)),
                    executableVerification("git", targetOs),
                )
            },
            selections.installGitHubCLI.takeIf { it }?.let {
                TaskPlan(
                    SetupTaskState(
                        "github-cli",
                        "Verify GitHub CLI",
                        if (installed.gh) "Already installed" else "Install GitHub CLI",
                    ),
                    command(nothingElse.copy(installGitHubCLI = true)),
                    executableVerification("gh", targetOs),
                )
            },
            *selections.aiAssistants.mapNotNull { id ->
                val assistant = AIAssistants.BUILTIN.firstOrNull { it.id == id } ?: return@mapNotNull null
                TaskPlan(
                    SetupTaskState(
                        "ai-$id",
                        "Verify ${assistant.displayName}",
                        if (installed.isAiInstalled(id)) "Already installed" else "Install ${assistant.displayName}",
                    ),
                    withShellEnvironment(
                        command(nothingElse.copy(aiAssistants = setOf(id))),
                        configuredShell,
                        targetOs,
                    ),
                    assistantVerification(
                        assistant.command,
                        assistant.resolvedDetectPaths(System.getProperty("user.home").orEmpty()),
                        targetOs,
                    ),
                )
            }.toTypedArray(),
        )
    }

    internal fun resolveConfiguredShell(
        choice: ShellChoice,
        targetOs: TargetOs,
        currentShell: String = System.getenv("SHELL").orEmpty(),
    ): ShellChoice {
        if (choice != ShellChoice.KEEP_CURRENT) return choice
        val current = currentShell.substringAfterLast('/')
        return ShellChoice.entries.firstOrNull {
            it != ShellChoice.KEEP_CURRENT && current.isNotEmpty() && it.command.substringBefore('.') == current
        }
            ?: if (targetOs.isWindows) ShellChoice.POWERSHELL else ShellChoice.ZSH
    }

    private fun shellVerification(choice: ShellChoice, targetOs: TargetOs): String? {
        val executable = executableVerification(choice.command, targetOs) ?: return null
        if (targetOs.isWindows || choice == ShellChoice.KEEP_CURRENT) return executable
        val loginShell = if (targetOs.isMac) {
            "dscl . -read /Users/\"\$USER\" UserShell | awk '{print \$2}'"
        } else {
            "{ getent passwd \"\$USER\" 2>/dev/null || grep \"^\$USER:\" /etc/passwd; } | cut -d: -f7"
        }
        return "#!/bin/bash\nset -e\n$executable\nLOGIN_SHELL=\$($loginShell)\n" +
            "test \"\$(basename \"\$LOGIN_SHELL\")\" = \"${choice.command}\"\n"
    }

    private fun withShellEnvironment(script: String, shell: ShellChoice, targetOs: TargetOs): String =
        if (targetOs.isWindows || shell.command.isBlank()) script else "export SHELL=\"\$(command -v ${shell.command})\"\n$script"

    private fun ensurePromptActivationCommand(
        choice: ShellCustomizationChoice,
        shell: ShellChoice,
        targetOs: TargetOs,
    ): String {
        if (targetOs.isWindows) return ""
        val file = when (shell) {
            ShellChoice.ZSH -> "\$HOME/.zshrc"
            ShellChoice.BASH -> "\$HOME/.bashrc"
            ShellChoice.FISH -> "\$HOME/.config/fish/config.fish"
            else -> return ""
        }
        val line = when (choice) {
            ShellCustomizationChoice.STARSHIP -> when (shell) {
                ShellChoice.FISH -> "starship init fish | source"
                else -> "eval \"\$(starship init ${shell.command})\""
            }
            ShellCustomizationChoice.OH_MY_ZSH -> "source \$HOME/.oh-my-zsh/oh-my-zsh.sh"
            ShellCustomizationChoice.PREZTO -> "source \$HOME/.zprezto/init.zsh"
            else -> return ""
        }
        return "\nmkdir -p \"\$(dirname \"$file\")\" && touch \"$file\" && { " +
            "grep -Fq '$line' \"$file\" || echo '$line' >> \"$file\"; }\n"
    }

    private fun resolvePackageManager(
        choice: PackageManagerChoice,
        installed: InstalledTools,
        targetOs: TargetOs,
    ): PackageManagerChoice = when (choice) {
        PackageManagerChoice.AUTO -> when {
            targetOs.isMac -> PackageManagerChoice.HOMEBREW
            installed.winget -> PackageManagerChoice.WINGET
            installed.chocolatey -> PackageManagerChoice.CHOCOLATEY
            targetOs.isWindows -> PackageManagerChoice.WINGET
            else -> PackageManagerChoice.NONE
        }
        else -> choice
    }

    private fun InstalledTools.withPackageManager(choice: PackageManagerChoice): InstalledTools = when (choice) {
        PackageManagerChoice.HOMEBREW -> copy(homebrew = true, winget = false, chocolatey = false)
        PackageManagerChoice.WINGET -> copy(homebrew = false, winget = true, chocolatey = false)
        PackageManagerChoice.CHOCOLATEY -> copy(homebrew = false, winget = false, chocolatey = true)
        PackageManagerChoice.NONE -> copy(homebrew = false, winget = false, chocolatey = false)
        PackageManagerChoice.AUTO -> this
    }

    private fun packageManagerTask(
        choice: PackageManagerChoice,
        installed: InstalledTools,
        targetOs: TargetOs,
    ): TaskPlan? {
        if (choice == PackageManagerChoice.NONE || choice == PackageManagerChoice.AUTO) return null
        val alreadyInstalled = when (choice) {
            PackageManagerChoice.HOMEBREW -> installed.homebrew
            PackageManagerChoice.WINGET -> installed.winget
            PackageManagerChoice.CHOCOLATEY -> installed.chocolatey
            PackageManagerChoice.AUTO, PackageManagerChoice.NONE -> false
        }
        val executable = when (choice) {
            PackageManagerChoice.HOMEBREW -> "brew"
            PackageManagerChoice.WINGET -> "winget"
            PackageManagerChoice.CHOCOLATEY -> "choco"
            PackageManagerChoice.AUTO, PackageManagerChoice.NONE -> return null
        }
        val installCommand = when {
            alreadyInstalled -> executableVerification(executable, targetOs).orEmpty()
            choice == PackageManagerChoice.HOMEBREW && targetOs.isMac ->
                "#!/bin/bash\nsudo -v && " +
                    "/bin/bash -c " +
                    "\"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\"\n"
            choice == PackageManagerChoice.CHOCOLATEY && targetOs.isWindows ->
                "Set-ExecutionPolicy Bypass -Scope Process -Force; " +
                    "[System.Net.ServicePointManager]::SecurityProtocol = " +
                    "[System.Net.ServicePointManager]::SecurityProtocol -bor 3072; " +
                    "iex ((New-Object System.Net.WebClient)." +
                    "DownloadString('https://community.chocolatey.org/install.ps1'))"
            choice == PackageManagerChoice.WINGET && targetOs.isWindows -> wingetInstallCommand()
            else -> "Write-Error '${choice.displayName} must be installed by the user'; exit 1"
        }
        return TaskPlan(
            SetupTaskState(
                "package-manager",
                "Verify ${choice.displayName}",
                if (alreadyInstalled) "Already installed" else "Install ${choice.displayName}",
            ),
            installCommand,
            executableVerification(executable, targetOs),
        )
    }

    private fun wingetInstallCommand(): String =
        "\$progressPreference = 'silentlyContinue'; " +
            "\$release = Invoke-RestMethod -Uri 'https://api.github.com/repos/microsoft/winget-cli/releases/latest'; " +
            "\$url = \$release.assets | Where-Object { \$_.name -match '\\.msixbundle\$' } | " +
            "Select-Object -First 1 -ExpandProperty browser_download_url; " +
            "if (-not \$url) { exit 1 }; " +
            "Invoke-WebRequest -Uri \$url -OutFile \"\$env:TEMP\\winget.msixbundle\"; " +
            "Add-AppxPackage -Path \"\$env:TEMP\\winget.msixbundle\""

    private suspend fun runTask(plan: TaskPlan, sessionId: String): CommandResult {
        val installation = runCommand(plan.command, sessionId)
        if (installation.exitCode != 0 || plan.verificationCommand == null) return installation
        appendOutput(sessionId, "Verifying ${plan.state.title}")
        val verification = runCommand(plan.verificationCommand, sessionId)
        return if (verification.exitCode == 0) {
            installation
        } else {
            CommandResult(
                verification.exitCode,
                listOf(installation.output, "Post-install verification failed.", verification.output)
                    .filter { it.isNotBlank() }
                    .joinToString("\n"),
            )
        }
    }

    internal fun executableVerification(command: String, targetOs: TargetOs): String? {
        if (command.isBlank()) return null
        return if (targetOs.isWindows) {
            val smoke = when (command.lowercase()) {
                "cmd.exe", "cmd" -> "cmd.exe /c ver"
                "powershell.exe", "powershell" -> "powershell -NoProfile -Command '\$PSVersionTable.PSVersion'"
                else -> "& '$command' --version"
            }
            "if (-not (Get-Command '$command' -ErrorAction SilentlyContinue)) { exit 1 }; " +
                "$smoke; if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }"
        } else {
            "#!/bin/bash\ncommand -v '$command' >/dev/null 2>&1 && '$command' --version >/dev/null 2>&1\n"
        }
    }

    private fun assistantVerification(command: String, paths: List<String>, targetOs: TargetOs): String? {
        if (command.isBlank()) return null
        return if (targetOs.isWindows) {
            val pathBranches = paths.joinToString(" ") { path ->
                val safePath = path.replace("'", "''")
                "elseif (Test-Path '$safePath') { & '$safePath' --version; " +
                    "if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE } }"
            }
            "if (Get-Command '$command' -ErrorAction SilentlyContinue) { " +
                "& '$command' --version; if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE } " +
                "} $pathBranches else { exit 1 }"
        } else {
            val pathBranches = paths.joinToString(" ") { path ->
                val safePath = path.replace("'", "'\"'\"'")
                "elif test -x '$safePath'; then '$safePath' --version >/dev/null 2>&1;"
            }
            "#!/bin/bash\nif command -v '$command' >/dev/null 2>&1; then " +
                "'$command' --version >/dev/null 2>&1; $pathBranches else exit 1; fi\n"
        }
    }

    internal fun promptVerification(
        choice: ShellCustomizationChoice,
        shell: ShellChoice,
        targetOs: TargetOs,
    ): String? =
        when (choice) {
            ShellCustomizationChoice.STARSHIP -> promptExecutableVerification("starship", "starship init", shell, targetOs)
            ShellCustomizationChoice.OH_MY_POSH -> promptExecutableVerification("oh-my-posh", "oh-my-posh init", shell, targetOs)
            ShellCustomizationChoice.OH_MY_ZSH -> unixPromptVerification("\$HOME/.oh-my-zsh", "oh-my-zsh.sh")
            ShellCustomizationChoice.PREZTO -> unixPromptVerification("\$HOME/.zprezto", "zprezto")
            ShellCustomizationChoice.NONE, ShellCustomizationChoice.KEEP_EXISTING -> null
        }

    private fun promptExecutableVerification(
        command: String,
        marker: String,
        shell: ShellChoice,
        targetOs: TargetOs,
    ): String {
        val executable = requireNotNull(executableVerification(command, targetOs))
        val config = when (shell) {
            ShellChoice.ZSH -> "\$HOME/.zshrc"
            ShellChoice.BASH -> "\$HOME/.bashrc"
            ShellChoice.FISH -> "\$HOME/.config/fish/config.fish"
            ShellChoice.POWERSHELL, ShellChoice.CMD ->
                "\$PROFILE.CurrentUserCurrentHost"
            ShellChoice.KEEP_CURRENT -> "\$HOME/.zshrc"
        }
        return if (targetOs.isWindows) {
            "$executable; if (-not (Select-String -LiteralPath $config -Pattern '$marker' -Quiet)) { exit 1 }"
        } else {
            "#!/bin/bash\nset -e\n$executable\ngrep -q '$marker' \"$config\"\n"
        }
    }

    private fun unixPromptVerification(path: String, marker: String): String =
        "#!/bin/bash\ntest -d \"$path\" && grep -q '$marker' \"\$HOME/.zshrc\"\n"

    private data class CommandResult(val exitCode: Int, val output: String)

    private data class PendingTerminalCommand(
        val parser: SetupTerminalSentinelParser,
        val completion: CompletableDeferred<CommandResult>,
        val output: StringBuilder = StringBuilder(),
    )

    private suspend fun runCommand(script: String, sessionId: String): CommandResult {
        if (withTimeoutOrNull(TERMINAL_READY_TIMEOUT_MS) { terminalReady.await() } == null) {
            return CommandResult(-1, "Interactive terminal did not become ready")
        }
        if (_state.value.sessionId != sessionId) return CommandResult(-1, "Setup session changed")
        val windows = TargetOs.current().isWindows
        val file = File.createTempFile("bossterm-setup-", if (windows) ".ps1" else ".sh").apply {
            restrictToOwner(this)
            writeText(script)
            if (!windows) setExecutable(true)
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        val wrapper = File.createTempFile("bossterm-setup-wrapper-", if (windows) ".ps1" else ".sh").also(::restrictToOwner)
        val completion = CompletableDeferred<CommandResult>()
        val pending = PendingTerminalCommand(SetupTerminalSentinelParser(token), completion)
        var poller: Job? = null
        try {
            synchronized(terminalLock) {
                check(pendingTerminalCommand == null) { "A setup command is already active" }
                pendingTerminalCommand = pending
            }
            notifyDebugEligibilityChanged(sessionId)
            terminalCommandSubmittedForTest = false
            val quotedPath = file.absolutePath.replace("'", if (windows) "''" else "'\"'\"'")
            val submitted = if (windows) {
                wrapper.writeText(
                    "\$global:LASTEXITCODE = 0\n& '$quotedPath'\n" +
                        "\$__bossExit = if (-not \$?) { 1 } elseif (\$null -eq \$LASTEXITCODE) { 0 } else { \$LASTEXITCODE }\n" +
                        "Write-Output ('__BOSS_SETUP_${token}__:' + \$__bossExit)\n",
                )
                "powershell.exe -NoProfile -ExecutionPolicy Bypass -File \"${wrapper.absolutePath}\""
            } else {
                wrapper.writeText(
                    "#!/bin/bash\n" +
                        "trap ':' INT TERM\n" +
                        "bash '$quotedPath'\n__boss_exit=\$?\n" +
                        "printf '\\n__BOSS_SETUP_${token}__:%s\\n' \"\$__boss_exit\"\n",
                )
                wrapper.setExecutable(true)
                "bash '${wrapper.absolutePath.replace("'", "'\"'\"'")}'"
            }
            val terminal = terminalStateOrNull()
                ?: return CommandResult(-1, "Interactive terminal is unavailable")
            val tabId = terminalTabId ?: return CommandResult(-1, "Interactive terminal tab is unavailable")
            if (!terminal.sendInput("$submitted\r".toByteArray(Charsets.UTF_8), tabId)) {
                return CommandResult(-1, "Interactive terminal rejected the setup command")
            }
            terminalCommandSubmittedForTest = true
            poller = scope.launch {
                var previous = ""
                while (!completion.isCompleted && _state.value.sessionId == sessionId) {
                    val snapshot = terminal.getTabById(tabId)?.textBuffer?.createSnapshot()
                    val current = snapshot?.let { snap ->
                        joinTerminalLines(
                            (snap.historyLines + snap.screenLines).map { it.text.trimEnd() to it.isWrapped },
                        )
                    }.orEmpty()
                    if (current != previous) {
                        terminalOutput(sessionId, current)
                        previous = current
                    }
                    if (!isTerminalConnected()) {
                        completion.complete(CommandResult(-1, "Interactive terminal exited"))
                    }
                    delay(TERMINAL_OUTPUT_POLL_MS)
                }
            }
            return (withTimeoutOrNull(TASK_TIMEOUT_MINUTES * 60_000L) { completion.await() }
                ?: run {
                    terminalBoundarySafe = false
                    synchronized(terminalLock) {
                        if (pendingTerminalCommand === pending) pendingTerminalCommand = null
                    }
                    terminal.sendInput(byteArrayOf(0x03), tabId)
                    CommandResult(TIMEOUT_EXIT_CODE, "Task timed out after $TASK_TIMEOUT_MINUTES minutes")
                })
        } catch (cancelled: CancellationException) {
            synchronized(terminalLock) {
                if (pendingTerminalCommand === pending) pendingTerminalCommand = null
            }
            terminalStateOrNull()?.sendInput(byteArrayOf(0x03), terminalTabId ?: "")
            throw cancelled
        } catch (error: Exception) {
            return CommandResult(-1, error.message ?: error::class.simpleName.orEmpty())
        } finally {
            poller?.cancel()
            synchronized(terminalLock) {
                if (pendingTerminalCommand === pending) pendingTerminalCommand = null
            }
            notifyDebugEligibilityChanged(sessionId)
            file.delete()
            wrapper.delete()
        }
    }

    internal class SetupTerminalSentinelParser(private val token: String) {
        private val marker = "__BOSS_SETUP_${token}__:"
        private val buffered = StringBuilder()

        @Synchronized
        fun accept(chunk: String): Int? {
            buffered.append(ANSI_ESCAPE.replace(chunk, ""))
            val lines = buffered.toString().split('\r', '\n')
            buffered.clear()
            if (!chunk.endsWith('\r') && !chunk.endsWith('\n')) buffered.append(lines.last())
            return lines.dropLast(if (buffered.isEmpty()) 0 else 1)
                .firstNotNullOfOrNull { line ->
                    line.trim().takeIf { it.startsWith(marker) }
                        ?.removePrefix(marker)?.trim()?.takeIf { value -> value.all(Char::isDigit) }
                        ?.toIntOrNull()
                }
        }

        @Synchronized
        fun acceptSnapshot(snapshot: String): Int? {
            buffered.clear()
            return accept(snapshot + "\n")
        }
    }

    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        check(file.setReadable(true, true) && file.setWritable(true, true)) {
            "Could not restrict setup command file permissions"
        }
    }

    internal fun joinTerminalLines(lines: List<Pair<String, Boolean>>): String = buildString {
        lines.forEachIndexed { index, (text, wrapsToNext) ->
            append(text)
            if (!wrapsToNext && index != lines.lastIndex) append('\n')
        }
    }

    private fun appendOutput(sessionId: String, line: String) {
        val printable = line.trim().take(MAX_VISIBLE_LINE_LENGTH)
        if (printable.isEmpty()) return
        updateSession(sessionId) { current ->
            current.copy(outputLines = (current.outputLines + printable).takeLast(MAX_VISIBLE_LINES))
        }
    }

    private fun notifyDebugEligibilityChanged(sessionId: String) {
        updateSession(sessionId) { it.copy(debugEligibilityRevision = it.debugEligibilityRevision + 1) }
    }

    private inline fun updateSession(
        sessionId: String,
        transform: (BossTermSetupState) -> BossTermSetupState,
    ) {
        _state.update { current -> if (current.sessionId == sessionId) transform(current) else current }
    }

    private fun packageRefreshCommand(os: TargetOs): String = when (os) {
        TargetOs.MAC -> "#!/bin/bash\nset -e\nexport PATH=/opt/homebrew/bin:/usr/local/bin:\$PATH\nbrew update\n"
        TargetOs.LINUX ->
            "#!/bin/bash\nset -e\n" +
                "if command -v apt >/dev/null; then sudo -n apt update; " +
                "elif command -v dnf >/dev/null; then sudo -n dnf makecache; " +
                "elif command -v pacman >/dev/null; then sudo -n pacman -Sy; fi\n"
        TargetOs.WINDOWS -> "winget source update"
    }

    private const val MAX_FAILURE_OUTPUT = 4_000
    private const val MAX_CAPTURED_OUTPUT = 32_000
    private const val MAX_REPAIR_ATTEMPTS = 2
    private const val TERMINAL_READY_TIMEOUT_MS = 30_000L
    private const val TERMINAL_READY_POLL_MS = 25L
    private const val TERMINAL_OUTPUT_POLL_MS = 200L
    private const val TERMINAL_READY_POLL_ATTEMPTS = 1_200
    private const val TIMEOUT_EXIT_CODE = 124
    private const val HANDOFF_FAILED_EXIT_CODE = 125
    private const val HANDOFF_BOUNDARY_TIMEOUT_MS = 5_000L
    private const val TERMINAL_BOUNDARY_PROBE_DELAY_MS = 150L
    private val USER_CANCEL_EXIT_CODES = setOf(TIMEOUT_EXIT_CODE, HANDOFF_FAILED_EXIT_CODE, 130, 143)
    private const val MAX_VISIBLE_LINES = 80
    private const val MAX_VISIBLE_LINE_LENGTH = 500
    private const val TASK_TIMEOUT_MINUTES = 10L
    private val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
}
