package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
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
 * re-skins live when the user switches the host theme.
 *
 * The host theme system and BossTerm's own theme engine are independent. This
 * composable observes the host's reactive [BossThemeColors] tokens and pushes a
 * matching terminal [Theme] + [ColorPalette] into BossTerm's global
 * [ThemeManager] / [ColorPaletteManager], which recolor every terminal in the
 * process live (no restart).
 *
 * Note that this runs on **every** host theme change and therefore overwrites
 * whatever terminal theme was previously active — a BossTerm builtin only ever
 * shows through here if [buildTerminalTheme] chooses it, which is why that
 * function prefers a bundled builtin over synthesis. Light vs dark for the
 * synthesized case is inferred from the host background luminance, so a light
 * host theme yields a light terminal.
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
        // Both ways this can go wrong — a floor that matches nothing, and a floor
        // that matches the wrong thing — are silent: the terminal just looks
        // slightly off. One line turns "why does Blueprint look like Operator?"
        // into a grep.
        logger.debug(
            LogCategory.TERMINAL,
            "Applied host theme to terminal",
            mapOf(
                "themeId" to theme.id,
                "hostFloor" to hex(background),
                "source" to if (theme.isBuiltin) "curated-builtin" else "synthesized",
            ),
        )
        ThemeManager.instance.applyTheme(theme)
        // applyTheme alone leaves the ANSI cache stale; applying the palette
        // invalidates it so the 16 ANSI colors repaint to match.
        ColorPaletteManager.instance.applyPalette(ColorPalette.fromTheme(theme))
    }
}

private val logger = BossLogger.forComponent("HostTerminalThemeBridge")

private fun hex(c: Color): String = Theme.colorToHex(c)

/**
 * The BOSS builtin whose floor is [floor], or null to synthesize.
 *
 * Compared case-insensitively: `colorToHex` emits uppercase today, but this is a
 * string comparison against values owned by another repo, and case drift there
 * would disable the whole feature with no signal. (The exact-value risk —
 * `colorToHex` truncating `(channel * 255).toInt()` — is pinned upstream by
 * BossTerm's `builtin background hex survives a Color round-trip`.)
 */
internal fun curatedBossThemeFor(floor: String): Theme? =
    BuiltinThemes.ALL.firstOrNull {
        it.id.startsWith("boss-") && it.background.equals(floor, ignoreCase = true)
    }

/**
 * Builds a terminal [Theme] from the active host chrome colors.
 *
 * A hand-authored **BOSS** builtin beats synthesis, so BOSS Blueprint and BOSS
 * Operator get their real ANSI 16 and their exact
 * [ai.rever.bossterm.compose.settings.theme.UiTheme] chrome instead of a derived
 * approximation. Matching is on the floor: a host theme and its terminal
 * counterpart share `ink` by design, and that is the one value both sides commit
 * to publicly — no host API for "which theme id is active" is reachable from a
 * plugin, because plugins compile against boss-plugin-api's `ui` mirror, which
 * has no theme controller in it.
 *
 * Deliberately restricted to the `boss-` builtins rather than all of
 * [BuiltinThemes.ALL]. The intent is "a BOSS identity should win", and a floor is
 * not a unique key: pure white is the likeliest background for any bundled light
 * theme *and* for a light host theme, so an unrestricted match could silently
 * adopt a third-party builtin's entire palette and drop every host token — the
 * failure this function exists to prevent, in the other direction and harder to
 * spot because it looks deliberate. It would also make behaviour depend on
 * declaration order in [BuiltinThemes.ALL].
 *
 * Unmatched host themes (Daylight, Clean, anything added later) fall through to
 * synthesis: brand-significant ANSI slots (red/green/yellow/cyan) from the host
 * status/data tokens, the rest from a curated light or dark base chosen by
 * background luminance.
 */
internal fun buildTerminalTheme(
    background: Color,
    foreground: Color,
    accent: Color,
    data: Color,
    error: Color,
    success: Color,
    warning: Color,
): Theme {
    val floor = hex(background)
    curatedBossThemeFor(floor)?.let { return it }

    val isLight = background.luminance() > 0.5f
    val selection = accent.copy(alpha = 0.30f)
    return if (isLight) {
        Theme(
            id = "boss-host-light",
            name = "BOSS Host (Light)",
            foreground = hex(foreground),
            background = floor,
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
            background = floor,
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
