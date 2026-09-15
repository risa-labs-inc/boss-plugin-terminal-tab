package ai.rever.boss.plugin.dynamic.terminaltab.onboarding

import ai.rever.boss.plugin.dynamic.terminaltab.TabbedTerminalStateRegistry
import ai.rever.bossterm.compose.TabbedTerminal
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossTermSetupHandoffStateTest {
    @BeforeTest
    fun resetBefore() = BossTermSetupController.resetForTest()
    @AfterTest
    fun resetAfter() = BossTermSetupController.resetForTest()

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun `one explicit resume releases Fluck and runs verifier before authentication`() = runComposeUiTest {
        if (TargetOs.current().isWindows) return@runComposeUiTest
        BossTermSetupController.clearFinished()
        val fix = Files.createTempFile("terminal-tab-agent-fix-", ".marker").toFile().apply { delete() }
        val debugRequest = AtomicReference<SetupDebugRequest>()
        val resume = CompletableDeferred<Unit>()
        val supervisor = object : BossTermSetupSupervisor {
            override suspend fun start(sessionId: String, windowId: String, tasks: List<SetupTaskState>) = true

            override suspend fun debugAndFix(
                request: SetupDebugRequest,
                onAccepted: () -> Unit,
            ): SetupDebugResult {
                debugRequest.set(request)
                onAccepted()
                assertTrue(BossTermSetupController.hasSetupTerminalHandoff(request.terminalId, request.requestId))
                assertEquals(
                    null,
                    McpTerminalRegistry.findTab(request.terminalId),
                    "setup PTY must stay hidden from generic MCP tools during handoff",
                )
                assertTrue(
                    BossTermSetupController.sendSetupTerminalInput(
                        request.terminalId,
                        request.requestId,
                        "touch '${fix.absolutePath}'; sleep 60\r".toByteArray(),
                    ),
                )
                resume.await()
                return SetupDebugResult(completed = true)
            }

            override fun resumeDebug(requestId: String): Boolean {
                if (debugRequest.get()?.requestId != requestId) return false
                return resume.complete(Unit)
            }
        }

        try {
            assertTrue(
                BossTermSetupController.startTerminalTaskForTest(
                    command = "printf 'ACTIVE_HANDOFF_TASK\\n'; sleep 60",
                    verificationCommand = "test -f '${fix.absolutePath}' && printf 'VERIFIER_RAN\\n'",
                    supervisor = supervisor,
                    authenticateGitHub = true,
                ),
            )
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
                                delay(25)
                            }
                        }
                    }
                }
            }
            waitUntil(timeoutMillis = 15_000) {
                "ACTIVE_HANDOFF_TASK" in BossTermSetupController.terminalCapturedOutputForTest()
            }
            waitUntil(timeoutMillis = 15_000) { BossTermSetupController.canAskFluckToDebugAndFix() }
            assertTrue(BossTermSetupController.askFluckToDebugAndFix())
            waitUntil(timeoutMillis = 15_000) { BossTermSetupController.state.value.agentDebugActive }
            // The fix has landed, but the fake agent deliberately leaves a foreground process.
            // Resume must interrupt it and prove a fresh shell boundary before verification.
            waitUntil(timeoutMillis = 15_000) { fix.exists() }

            assertTrue(BossTermSetupController.resumeActiveDebugAndVerify())
            waitUntil(timeoutMillis = 15_000) {
                BossTermSetupController.state.value.let { it.awaitingGitHubAuthentication || it.finished }
            }
            assertTrue(
                BossTermSetupController.state.value.awaitingGitHubAuthentication,
                "state=${BossTermSetupController.state.value}; " +
                    "terminal=${BossTermSetupController.terminalCapturedOutputForTest()}",
            )
            assertEquals(SetupTaskStatus.COMPLETE, BossTermSetupController.state.value.tasks.single().status)
            assertTrue("VERIFIER_RAN" in BossTermSetupController.terminalCapturedOutputForTest())

            val accepted = requireNotNull(debugRequest.get())
            assertFalse(BossTermSetupController.hasSetupTerminalHandoff(accepted.terminalId, accepted.requestId))
            assertFalse(
                BossTermSetupController.sendSetupTerminalInput(
                    accepted.terminalId,
                    accepted.requestId,
                    "echo stale\r".toByteArray(),
                ),
            )
        } finally {
            BossTermSetupController.state.value.let { state ->
                val tabId = state.setupTerminalId
                val containerId = state.setupTerminalContainerId
                if (tabId != null && containerId != null) {
                    TabbedTerminalStateRegistry.sendCtrlCToTab("test-window", containerId, tabId)
                }
            }
            BossTermSetupController.finishInteractiveGitHubAuth(completed = false)
            BossTermSetupController.clearFinished()
            fix.delete()
        }
    }

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("condition was not met within ${timeoutMs}ms")
            Thread.sleep(25)
        }
    }
}
