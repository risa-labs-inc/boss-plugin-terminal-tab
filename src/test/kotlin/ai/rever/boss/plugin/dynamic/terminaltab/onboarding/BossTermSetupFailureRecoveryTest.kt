package ai.rever.boss.plugin.dynamic.terminaltab.onboarding

import ai.rever.boss.plugin.dynamic.terminaltab.TabbedTerminalStateRegistry
import ai.rever.bossterm.compose.TabbedTerminal
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Setup must never get stuck "running": a failure has to reach the visible, retryable
 * failure path. And a Fluck handoff must not inherit setup's cached sudo credentials.
 */
@OptIn(ExperimentalTestApi::class)
class BossTermSetupFailureRecoveryTest {
    @BeforeTest
    fun resetBefore() = BossTermSetupController.resetForTest()
    @AfterTest
    fun resetAfter() = BossTermSetupController.resetForTest()

    @Test
    fun `a temp file failure becomes a retryable failure`() = runComposeUiTest {
        if (TargetOs.current().isWindows) return@runComposeUiTest
        BossTermSetupController.setupTempFileFactoryForTest = { _, _ -> throw IOException("temp dir is read-only") }

        assertTrue(BossTermSetupController.startTerminalTaskForTest(command = "printf 'NEVER_RUNS\\n'"))
        showSetupTerminal()
        awaitCondition { BossTermSetupController.state.value.failureMessage != null }

        val state = BossTermSetupController.state.value
        assertFalse(state.isRunning, "state=$state")
        assertEquals(SetupTaskStatus.NEEDS_ATTENTION, state.tasks.single().status)
        // The task's own failure path, not the session guard's catch-all: runCommand turned
        // the throw into an ordinary failed command result.
        assertEquals("Test terminal task needs attention", state.failureMessage)
        assertTrue("temp dir is read-only" in state.failureOutput.orEmpty(), "state=$state")
        cleanup()
    }

    @Test
    fun `an unexpected throw ends the session as a failure instead of a stall`() = runComposeUiTest {
        if (TargetOs.current().isWindows) return@runComposeUiTest
        // An Error is not an Exception, so it escapes runCommand's own catch and reaches the
        // session guard: the case that used to kill the coroutine with the session "running".
        BossTermSetupController.setupTempFileFactoryForTest = { _, _ -> throw AssertionError("unexpected") }

        assertTrue(BossTermSetupController.startTerminalTaskForTest(command = "printf 'NEVER_RUNS\\n'"))
        showSetupTerminal()
        awaitCondition { BossTermSetupController.state.value.failureMessage != null }

        val state = BossTermSetupController.state.value
        assertFalse(state.isRunning, "state=$state")
        assertTrue(state.finished, "state=$state")
        assertEquals("Setup could not continue", state.failureMessage)
        assertEquals(SetupTaskStatus.NEEDS_ATTENTION, state.tasks.single().status)
        cleanup()
    }

    @Test
    fun `cached sudo credentials are dropped before Fluck gets the terminal`() = runComposeUiTest {
        if (TargetOs.current().isWindows) return@runComposeUiTest
        BossTermSetupController.dropSudoCredentialsScriptForTest = "#!/bin/bash\nprintf 'SUDO_DROPPED\\n'\n"
        val outputSeenByAgent = AtomicReference<String>()
        val supervisor = fakeSupervisor { outputSeenByAgent.set(BossTermSetupController.terminalCapturedOutputForTest()) }

        startTaskAndAskFluck(supervisor)
        awaitCondition { outputSeenByAgent.get() != null }

        assertTrue(
            "SUDO_DROPPED" in outputSeenByAgent.get(),
            "the credential drop must run before the agent is handed the terminal:\n${outputSeenByAgent.get()}",
        )
        cleanup()
    }

    @Test
    fun `handoff is refused when cached sudo credentials cannot be dropped`() = runComposeUiTest {
        if (TargetOs.current().isWindows) return@runComposeUiTest
        BossTermSetupController.dropSudoCredentialsScriptForTest = "#!/bin/bash\nexit 3\n"
        val agentCalled = AtomicBoolean(false)
        val supervisor = fakeSupervisor { agentCalled.set(true) }

        startTaskAndAskFluck(supervisor)
        awaitCondition { BossTermSetupController.state.value.agentDebugError != null }

        assertFalse(agentCalled.get(), "Fluck must not get a terminal that may still hold root")
        assertEquals(
            "Could not clear cached administrator access before debugging",
            BossTermSetupController.state.value.agentDebugError,
        )
        cleanup()
    }

    @Test
    fun `the session never reads as idle between Fluck ending and verification`() = runComposeUiTest {
        if (TargetOs.current().isWindows) return@runComposeUiTest
        BossTermSetupController.dropSudoCredentialsScriptForTest = "#!/bin/bash\n:\n"
        val requestId = AtomicReference<String>()
        val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
        val supervisor = object : BossTermSetupSupervisor {
            override suspend fun start(sessionId: String, windowId: String, tasks: List<SetupTaskState>) = true

            override suspend fun debugAndFix(request: SetupDebugRequest, onAccepted: () -> Unit): SetupDebugResult {
                requestId.set(request.requestId)
                onAccepted()
                resume.await()
                return SetupDebugResult(completed = true)
            }

            override fun resumeDebug(id: String): Boolean = id == requestId.get() && resume.complete(Unit)
        }

        assertTrue(
            BossTermSetupController.startTerminalTaskForTest(
                command = "printf 'TASK_RUNNING\\n'; sleep 60",
                verificationCommand = "true",
                supervisor = supervisor,
            ),
        )
        showSetupTerminal()
        awaitCondition { "TASK_RUNNING" in BossTermSetupController.terminalCapturedOutputForTest() }
        awaitCondition { BossTermSetupController.canAskFluckToDebugAndFix() }
        assertTrue(BossTermSetupController.askFluckToDebugAndFix())
        awaitCondition { BossTermSetupController.state.value.agentDebugActive }
        assertTrue(BossTermSetupController.resumeActiveDebugAndVerify())

        // The first state after the agent session ends. It must already say "verifying": the
        // Ctrl-C, the 150 ms settle and the boundary probe all follow, and an idle-looking state
        // there let Retry start a second session on top of this one.
        val deadline = System.nanoTime() + 15_000_000_000
        var afterAgent = BossTermSetupController.state.value
        while (afterAgent.agentDebugActive && System.nanoTime() < deadline) {
            Thread.sleep(1)
            afterAgent = BossTermSetupController.state.value
        }
        assertFalse(afterAgent.agentDebugActive, "Fluck session never ended: $afterAgent")
        assertTrue(
            afterAgent.agentDebugAwaitingVerification || afterAgent.finished,
            "session read as idle after Fluck ended: $afterAgent",
        )
        assertTrue(afterAgent.isRunning || afterAgent.finished, "state=$afterAgent")

        awaitCondition { BossTermSetupController.state.value.finished }
        assertFalse(BossTermSetupController.state.value.agentDebugAwaitingVerification)
        cleanup()
    }

    @Test
    fun `the real drop script uses sudo -K and succeeds where sudo is absent`() {
        if (TargetOs.current().isWindows) return
        val script = BossTermSetupController.DROP_SUDO_CREDENTIALS_SCRIPT
        // -K removes every cached credential for the user; -k would leave other terminals' ones.
        assertTrue("sudo -K" in script, script)
        val process = ProcessBuilder("/bin/bash", "-c", script)
            .apply { environment()["PATH"] = "/nonexistent" }
            .redirectErrorStream(true)
            .start()
        assertEquals(0, process.waitFor(), process.inputStream.bufferedReader().readText())
    }

    private fun fakeSupervisor(onAgentCalled: () -> Unit) = object : BossTermSetupSupervisor {
        override suspend fun start(sessionId: String, windowId: String, tasks: List<SetupTaskState>) = true

        override suspend fun debugAndFix(request: SetupDebugRequest, onAccepted: () -> Unit): SetupDebugResult {
            onAgentCalled()
            return SetupDebugResult(completed = false, error = "test agent declined")
        }
    }

    /** Asks Fluck while a task is still running: the interrupt-at-boundary handoff path. */
    private fun ComposeUiTest.startTaskAndAskFluck(supervisor: BossTermSetupSupervisor) {
        assertTrue(
            BossTermSetupController.startTerminalTaskForTest(
                command = "printf 'TASK_RUNNING\\n'; sleep 60",
                supervisor = supervisor,
            ),
        )
        showSetupTerminal()
        awaitCondition { "TASK_RUNNING" in BossTermSetupController.terminalCapturedOutputForTest() }
        awaitCondition { BossTermSetupController.canAskFluckToDebugAndFix() }
        assertTrue(BossTermSetupController.askFluckToDebugAndFix())
    }

    private fun ComposeUiTest.showSetupTerminal() {
        setContent {
            val state by BossTermSetupController.state.collectAsState()
            state.setupTerminalContainerId?.let { containerId ->
                BossTermSetupController.terminalState("test-window", containerId)?.let { terminal ->
                    TabbedTerminal(
                        state = terminal,
                        onExit = {},
                        settingsOverride = TerminalSettingsOverride(alwaysShowTabBar = false),
                        isActive = !state.isBackgrounded,
                        modifier = Modifier.size(800.dp, 600.dp),
                    )
                    LaunchedEffect(containerId) {
                        repeat(400) {
                            if (BossTermSetupController.ensureSetupTerminalTab("test-window", containerId)) return@LaunchedEffect
                            kotlinx.coroutines.delay(25)
                        }
                    }
                }
            }
        }
    }

    private fun cleanup() {
        BossTermSetupController.state.value.let { state ->
            val tabId = state.setupTerminalId
            val containerId = state.setupTerminalContainerId
            if (tabId != null && containerId != null) {
                TabbedTerminalStateRegistry.sendCtrlCToTab("test-window", containerId, tabId)
            }
        }
        BossTermSetupController.clearFinished()
    }

    private fun awaitCondition(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() >= deadline) {
                error("condition was not met within ${timeoutMs}ms; state=${BossTermSetupController.state.value}")
            }
            Thread.sleep(25)
        }
    }
}
