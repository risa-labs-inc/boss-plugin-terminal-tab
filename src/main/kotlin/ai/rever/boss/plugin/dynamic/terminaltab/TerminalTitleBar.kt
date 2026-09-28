package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.ui.TerminalTitleBarAction
import ai.rever.boss.plugin.ui.TerminalTitleBarBridge
import ai.rever.bossterm.compose.mcp.LocalBossTermMcpConfig
import ai.rever.bossterm.compose.window.HostedTerminalControls
import ai.rever.bossterm.compose.window.HostedCallBar
import androidx.compose.runtime.*

private class TitleBarOwner { var live = true }

/** Lives with the host window, including when no terminal tab is composed. */
@Composable
internal fun TerminalWindowControls(windowId: String) {
    val owner = remember { TitleBarOwner() }
    DisposableEffect(owner) {
        onDispose {
            owner.live = false
            TerminalTitleBarBridge.remove(owner)
        }
    }
    CompositionLocalProvider(LocalBossTermMcpConfig provides TerminalMcpConfigHolder.config) {
        HostedCallBar { bar ->
            DisposableEffect(owner) { onDispose { TerminalTitleBarBridge.removeCallBar(owner) } }
            SideEffect { TerminalTitleBarBridge.publishCallBar(windowId, owner, bar) }
        }
        HostedTerminalControls(
            activeTabId = { TabbedTerminalStateRegistry.titleBarTabId(windowId) },
            callLabel = CALL_LABEL,
            voiceToolSource = BossVoiceTools.source,
            onActions = { actions ->
                if (owner.live) TerminalTitleBarBridge.publish(windowId, owner, true,
                    actions.map { TerminalTitleBarAction(it.id, it.label, it.symbol, it.icon, it.active, it.onClick) })
            },
        )
    }
}

/** The window owns the controls; suppress the terminal's duplicate floating strip. */
@Composable
internal fun terminalTitleBarHeader(
    windowId: String,
): (@Composable (@Composable () -> Unit, @Composable () -> Unit) -> Unit)? =
    if (TerminalTitleBarBridge.isHosted(windowId)) { _, _ -> } else null
