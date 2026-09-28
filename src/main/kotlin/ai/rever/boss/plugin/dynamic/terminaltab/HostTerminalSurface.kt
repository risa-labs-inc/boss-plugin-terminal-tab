package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.compose.window.LocalNativeWindowGlass
import ai.rever.bossterm.compose.window.LocalWindowGlassMode
import ai.rever.bossterm.compose.window.LocalWindowGlassTint
import ai.rever.bossterm.compose.window.WindowGlassMode
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The host gives its content background alpha only after native glass is installed and enabled
 * for app surfaces. Read it before entering BossTerm's own theme. Older hosts, sidebar-only glass,
 * disabled glass and failed native installation all retain an opaque content background.
 *
 * BossTerm's native-glass locals make the default terminal background transparent without fading
 * text, ANSI backgrounds, selections or controls. The host already paints the material and tint;
 * painting another terminal surface would cover it or apply the tint twice. No global settings
 * are written: terminals in different host windows can have different backdrop availability.
 */
@Composable
internal fun HostTerminalSurface(
    modifier: Modifier = Modifier,
    color: Color,
    content: @Composable () -> Unit,
) {
    val hostBackground = MaterialTheme.colors.background
    val glass = hostBackground.alpha < 1f
    CompositionLocalProvider(
        LocalNativeWindowGlass provides glass,
        LocalWindowGlassMode provides if (glass) WindowGlassMode.WINDOW else WindowGlassMode.OFF,
        LocalWindowGlassTint provides hostBackground.alpha,
    ) {
        Surface(modifier = modifier, color = if (glass) Color.Transparent else color, content = content)
    }
}
