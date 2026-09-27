package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.ui.TerminalTitleBarAction
import ai.rever.boss.plugin.ui.TerminalTitleBarBridge
import ai.rever.bossterm.compose.window.HostedStatusActions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun terminalTitleBarHeader(
    windowId: String,
    active: Boolean,
): (@Composable (@Composable () -> Unit, @Composable () -> Unit) -> Unit)? {
    if (!TerminalTitleBarBridge.isHosted(windowId)) return null
    val owner = remember { Any() }
    var actions by remember { mutableStateOf(emptyList<TerminalTitleBarAction>()) }
    SideEffect { TerminalTitleBarBridge.publish(windowId, owner, active, actions) }
    DisposableEffect(owner) { onDispose { TerminalTitleBarBridge.remove(owner) } }
    return { status, _ ->
        Box(Modifier.size(0.dp)) {
            HostedStatusActions(onActions = { updated ->
                actions = updated.map { TerminalTitleBarAction(it.id, it.label, it.symbol, it.icon, it.active, it.onClick) }
            }, content = status)
        }
    }
}
