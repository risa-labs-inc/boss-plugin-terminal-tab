package ai.rever.boss.plugin.dynamic.terminaltab.onboarding

import ai.rever.bossterm.compose.TabbedTerminal
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BossTermSetupTerminalPersistenceTest {
    @BeforeTest
    fun resetBefore() = BossTermSetupController.resetForTest()
    @AfterTest
    fun resetAfter() = BossTermSetupController.resetForTest()

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun `backgrounded renderer keeps the same interactive PTY and sentinel command`() = runComposeUiTest {
        if (TargetOs.current().isWindows) return@runComposeUiTest
        val visible = mutableStateOf(true)
        BossTermSetupController.clearFinished()
        try {
            assertTrue(
                BossTermSetupController.startTerminalTaskForTest(
                    "printf 'READY_FOR_INPUT\\n'; read value; printf 'input:%s\\n' \"\$value\"",
                ),
            )
            setContent {
                val state by BossTermSetupController.state.collectAsState()
                val containerId = state.setupTerminalContainerId
                if (containerId != null) {
                    BossTermSetupController.terminalState("test-window", containerId)?.let { terminal ->
                        TabbedTerminal(
                            state = terminal,
                            onExit = {},
                            settingsOverride = TerminalSettingsOverride(alwaysShowTabBar = false),
                            isActive = visible.value && !state.isBackgrounded,
                            modifier = Modifier.size(800.dp, 600.dp),
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 15_000) {
                "READY_FOR_INPUT" in BossTermSetupController.terminalCapturedOutputForTest()
            }
            val state = BossTermSetupController.state.value
            val containerId = requireNotNull(state.setupTerminalContainerId)
            val terminal = requireNotNull(BossTermSetupController.terminalState("test-window", containerId))
            val tabId = requireNotNull(terminal.activeTab?.id)

            BossTermSetupController.sendToBackground()
            visible.value = false
            waitForIdle()
            assertSame(terminal, BossTermSetupController.terminalState("test-window", containerId))
            assertTrue(terminal.sendInput("hello-from-background\r".toByteArray(), tabId))
            waitUntil(timeoutMillis = 15_000) { BossTermSetupController.state.value.finished }

            visible.value = true
            waitForIdle()
            assertEquals(SetupTaskStatus.COMPLETE, BossTermSetupController.state.value.tasks.single().status)
            assertTrue("input:hello-from-background" in BossTermSetupController.terminalCapturedOutputForTest())
            assertSame(terminal, BossTermSetupController.terminalState("test-window", containerId))
        } finally {
            BossTermSetupController.clearFinished()
        }
    }
}
