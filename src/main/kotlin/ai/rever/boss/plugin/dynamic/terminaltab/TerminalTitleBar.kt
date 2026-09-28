package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.ui.TerminalTitleBarAction
import ai.rever.boss.plugin.ui.TerminalTitleBarBridge
import ai.rever.bossterm.compose.mcp.LocalBossTermMcpConfig
import ai.rever.bossterm.compose.window.HostedCallBar
import ai.rever.bossterm.compose.window.HostedTerminalControls
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

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
                if (owner.live) {
                    TerminalTitleBarBridge.publish(
                        windowId = windowId,
                        owner = owner,
                        // Eligible across all selected tab types; this is not window keyboard focus.
                        active = true,
                        actions = actions.map {
                            TerminalTitleBarAction(it.id, it.label, it.symbol, it.icon, it.active, it.onClick)
                        },
                    )
                }
            },
        )
    }
}

/** The window owns status and toolbar controls, so neither terminal header slot is rendered. */
@Composable
internal fun terminalTitleBarHeader(
    windowId: String,
): (@Composable (@Composable () -> Unit, @Composable () -> Unit) -> Unit)? =
    if (TerminalTitleBarBridge.isHosted(windowId)) { _, _ -> } else null
