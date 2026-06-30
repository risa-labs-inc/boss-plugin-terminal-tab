package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.bossterm.compose.settings.theme.ColorPalette
import ai.rever.bossterm.compose.settings.theme.ColorPaletteManager
import ai.rever.bossterm.compose.settings.theme.Theme
import ai.rever.bossterm.compose.settings.theme.ThemeManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Bridges the BOSS host theme into the bundled BossTerm terminal so the terminal
 * re-skins live when the user switches the host theme (Operator / Daylight / Clean).
 *
 * The host theme system and BossTerm's own theme engine are independent. This
 * composable observes the host's reactive [BossThemeColors] tokens and pushes a
 * matching terminal [Theme] + [ColorPalette] into BossTerm's global
 * [ThemeManager] / [ColorPaletteManager], which recolor every terminal in the
 * process live (no restart). Light vs dark is inferred from the host background
 * luminance, so a light host theme (Daylight) yields a light terminal.
 *
 * Strategy A — no BossTerm change/republish: this uses the runtime custom-theme
 * API (`applyTheme` + `applyPalette`) that ships in the bundled bossterm-compose.
 * Notes (BossTerm runtime behaviour): `applyTheme` updates fg/bg/selection live
 * but does NOT invalidate the ANSI color cache, so we also `applyPalette` to
 * repaint the 16 ANSI colors. The cursor color is driven by OSC 12, not the
 * theme, so it is not affected here.
 */
@Composable
fun ApplyHostThemeToTerminal() {
    // Reactive reads — recompose (and re-apply) when the host theme switches.
    val background = BossThemeColors.BackgroundColor   // content floor (ink)
    val foreground = BossThemeColors.TextPrimary
    val accent = BossThemeColors.AccentColor           // signal
    val data = BossThemeColors.SecondaryColor          // links / data
    val error = BossThemeColors.ErrorColor
    val success = BossThemeColors.SuccessColor
    val warning = BossThemeColors.WarningColor

    LaunchedEffect(background, foreground, accent, data, error, success, warning) {
        val theme = buildTerminalTheme(background, foreground, accent, data, error, success, warning)
        ThemeManager.instance.applyTheme(theme)
        // applyTheme alone leaves the ANSI cache stale; applying the palette
        // invalidates it so the 16 ANSI colors repaint to match.
        ColorPaletteManager.instance.applyPalette(ColorPalette.fromTheme(theme))
    }
}

private fun hex(c: Color): String = Theme.colorToHex(c)

/**
 * Builds a terminal [Theme] from the active host chrome colors. Brand-significant
 * ANSI slots (red/green/yellow/cyan) are taken from the host status/data tokens;
 * the rest come from a curated light or dark base chosen by background luminance.
 */
private fun buildTerminalTheme(
    background: Color,
    foreground: Color,
    accent: Color,
    data: Color,
    error: Color,
    success: Color,
    warning: Color,
): Theme {
    val isLight = background.luminance() > 0.5f
    val selection = accent.copy(alpha = 0.30f)
    return if (isLight) {
        Theme(
            id = "boss-host-light",
            name = "BOSS Host (Light)",
            foreground = hex(foreground),
            background = hex(background),
            cursor = hex(accent),
            cursorText = hex(background),
            selection = hex(selection),
            selectionText = hex(foreground),
            searchMatch = hex(warning),
            hyperlink = hex(data),
            // Light base: saturated, dark-enough to read on a light background.
            black = "0xFF24292E",
            red = hex(error),
            green = hex(success),
            yellow = hex(warning),
            blue = hex(data),
            magenta = "0xFF6F42C1",
            cyan = "0xFF1B7C83",
            white = "0xFFD1D5DA",
            brightBlack = "0xFF6A737D",
            brightRed = "0xFFCB2431",
            brightGreen = "0xFF22863A",
            brightYellow = "0xFFB08800",
            brightBlue = "0xFF0366D6",
            brightMagenta = "0xFF8E44AD",
            brightCyan = "0xFF1B7C83",
            brightWhite = hex(foreground),
            isBuiltin = false,
        )
    } else {
        Theme(
            id = "boss-host-dark",
            name = "BOSS Host (Dark)",
            foreground = hex(foreground),
            background = hex(background),
            cursor = hex(accent),
            cursorText = hex(background),
            selection = hex(selection),
            selectionText = hex(foreground),
            searchMatch = hex(warning),
            hyperlink = hex(data),
            // Dark base: BOSS Operator ANSI palette, brand accents from host tokens.
            black = "0xFF15202B",
            red = hex(error),
            green = hex(success),
            yellow = hex(warning),
            blue = "0xFF5C9FE0",
            magenta = "0xFFC792EA",
            cyan = hex(data),
            white = "0xFFC7D1DB",
            brightBlack = "0xFF3A4B5C",
            brightRed = "0xFFFF8A80",
            brightGreen = "0xFF8FE0A6",
            brightYellow = "0xFFFFC560",
            brightBlue = "0xFF82B7F0",
            brightMagenta = "0xFFDDB0F5",
            brightCyan = "0xFF7FD9EE",
            brightWhite = "0xFFE9EEF3",
            isBuiltin = false,
        )
    }
}
