package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import ai.rever.bossterm.compose.settings.theme.Theme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bridge picks a curated BOSS builtin by comparing a hex string it derives
 * from the host floor against strings owned by another repo. That comparison can
 * fail *silently* — no exception, just a quiet downgrade to a synthesized theme
 * that looks almost right.
 *
 * These tests run against whichever bossterm-compose this plugin actually bundles
 * (it is on the test classpath), so they fail here when the upstream pin moves in
 * a way that breaks the match, rather than relying on a test in a repo this one
 * cannot see or enforce.
 */
class HostTerminalThemeBridgeTest {
    @Test
    fun `every BOSS builtin floor round-trips through colorToHex`() {
        val boss = BuiltinThemes.ALL.filter { it.id.startsWith("boss-") }
        assertTrue(boss.isNotEmpty(), "the bundled BossTerm has no boss-* builtin at all")
        for (theme in boss) {
            assertEquals(
                theme.background,
                Theme.colorToHex(theme.backgroundColorValue),
                "${theme.id}: parse → colorToHex is not the identity, so the bridge's " +
                    "floor comparison can never match and silently synthesizes instead",
            )
        }
    }

    @Test
    fun `a BOSS floor resolves to that BOSS builtin`() {
        for (theme in BuiltinThemes.ALL.filter { it.id.startsWith("boss-") }) {
            val resolved = curatedBossThemeFor(theme.background)
            assertNotNull(resolved, "${theme.id}: its own floor must resolve to it")
            assertEquals(theme.id, resolved.id)
            assertTrue(resolved.isBuiltin, "${theme.id}: a curated match must be a builtin")
        }
    }

    @Test
    fun `case drift in the hex representation still matches`() {
        val operator = BuiltinThemes.ALL.first { it.id == "boss-operator" }
        assertEquals(operator.id, curatedBossThemeFor(operator.background.lowercase())?.id)
        assertEquals(operator.id, curatedBossThemeFor(operator.background.uppercase())?.id)
    }

    @Test
    fun `a non-BOSS builtin floor does not hijack the match`() {
        // The point of the boss- restriction: a bundled third-party theme must
        // never lend its whole palette to a host theme that happens to share a
        // floor with it.
        val others = BuiltinThemes.ALL.filterNot { it.id.startsWith("boss-") }
        for (theme in others) {
            assertNull(
                curatedBossThemeFor(theme.background),
                "${theme.id} is not a BOSS identity and must not be selectable by floor",
            )
        }
    }

    @Test
    fun `an unknown floor synthesizes rather than matching anything`() {
        assertNull(curatedBossThemeFor("0xFF123456"))
    }

    @Test
    fun `synthesis keeps the host floor and follows luminance for light versus dark`() {
        val dark = buildTerminalTheme(
            background = Color(0xFF101820),
            foreground = Color(0xFFEEEEEE),
            accent = Color(0xFF3366FF),
            data = Color(0xFF88AAFF),
            error = Color(0xFFFF5555),
            success = Color(0xFF55CC88),
            warning = Color(0xFFFFCC55),
        )
        assertEquals("boss-host-dark", dark.id)
        assertEquals("0xFF101820", dark.background, "synthesis must keep the host floor verbatim")
        assertFalse(dark.isBuiltin, "a synthesized theme is not a builtin")

        val light = buildTerminalTheme(
            background = Color(0xFFF7F8FA),
            foreground = Color(0xFF111111),
            accent = Color(0xFF0044CC),
            data = Color(0xFF0055DD),
            error = Color(0xFFCC2222),
            success = Color(0xFF227744),
            warning = Color(0xFF886600),
        )
        assertEquals("boss-host-light", light.id)
        assertEquals("0xFFF7F8FA", light.background)
    }

    @Test
    fun `a BOSS floor beats synthesis end to end`() {
        val operator = BuiltinThemes.ALL.first { it.id == "boss-operator" }
        val built = buildTerminalTheme(
            background = operator.backgroundColorValue,
            foreground = Color(0xFFE9EEF3),
            accent = Color(0xFFF2A93B),
            data = Color(0xFF56C7E0),
            error = Color(0xFFF2685F),
            success = Color(0xFF6FD08C),
            warning = Color(0xFFF0B429),
        )
        assertEquals(operator.id, built.id, "the curated builtin must win over synthesis")
        assertEquals(operator.magenta, built.magenta, "and bring its own ANSI 16, not the curated base")
    }
}
