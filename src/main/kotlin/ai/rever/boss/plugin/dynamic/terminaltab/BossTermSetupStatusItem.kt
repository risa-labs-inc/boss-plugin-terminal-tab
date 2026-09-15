package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.StatusBarAlignment
import ai.rever.boss.plugin.api.StatusBarItemProvider
import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.bossterm.compose.onboarding.BossTermSetupController
import ai.rever.bossterm.compose.onboarding.BossTermSetupState
import ai.rever.bossterm.compose.onboarding.OnboardingWizard
import ai.rever.bossterm.compose.settings.SettingsManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reopenable BOSS status-bar home for a setup the user explicitly backgrounded. */
internal class BossTermSetupStatusItem(
    private val settingsManager: SettingsManager,
    private val supervisor: BossTermFluckSupervisor,
) : StatusBarItemProvider {
    override val itemId: String = ITEM_ID
    override val alignment: StatusBarAlignment = StatusBarAlignment.RIGHT
    override val order: Int = 8

    @Composable
    override fun Content() {
        val state by BossTermSetupController.state.collectAsState()
        var showWizard by remember { mutableStateOf(false) }

        if (state.sessionId != null) {
            val label = setupStatusLabel(state)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).clickable(enabled = setupStatusCanOpen(state)) {
                    BossTermSetupController.bringToForeground()
                    showWizard = true
                }.padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(label, color = BossThemeColors.TextSecondary, fontSize = 11.sp, maxLines = 1)
                Spacer(Modifier.width(7.dp))
                LinearProgressIndicator(
                    progress = state.progress,
                    modifier = Modifier.width(54.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = BossThemeColors.AccentColor,
                    backgroundColor = BossThemeColors.AccentColor.copy(alpha = 0.2f),
                )
                if (!state.finished) {
                    Spacer(Modifier.width(5.dp))
                    Text("${(state.progress * 100).toInt()}%", color = BossThemeColors.TextSecondary, fontSize = 10.sp)
                }
            }
        }

        if (showWizard) {
            ApplyHostThemeToTerminal()
            OnboardingWizard(
                onDismiss = { showWizard = false },
                onComplete = { showWizard = false },
                settingsManager = settingsManager,
                supervisor = supervisor,
                canRunInBackground = true,
            )
        }
    }

    companion object {
        const val ITEM_ID = "ai.rever.boss.plugin.dynamic.terminaltab:setup-progress"
    }
}

internal fun setupStatusCanOpen(state: BossTermSetupState): Boolean =
    state.sessionId != null && state.isBackgrounded

internal fun setupStatusLabel(state: BossTermSetupState): String = when {
    state.failureMessage != null -> "BOSS Term setup needs attention"
    state.finished && state.authenticateGitHubAfterSetup -> "BOSS Term GitHub sign-in"
    state.finished -> "BOSS Term setup complete"
    else -> state.activeTask?.title ?: "Setting up BOSS Term"
}
