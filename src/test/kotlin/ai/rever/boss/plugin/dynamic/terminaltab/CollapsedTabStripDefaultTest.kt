package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.compose.settings.SettingsManager
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollapsedTabStripDefaultTest {
    private val dir: File = Files.createTempDirectory("collapsed-strip").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun manager() = SettingsManager(File(dir, "settings.json").absolutePath)

    @Test
    fun `existing install with an explicit false is flipped on once`() {
        val sm = manager()
        sm.updateSetting { copy(showCollapsedTabStrip = false) }

        assertTrue(applyCollapsedTabStripDefault(dir, sm))
        assertTrue(sm.settings.value.showCollapsedTabStrip)
        assertTrue(File(dir, COLLAPSED_TAB_STRIP_MARKER).exists())
        // Persisted, not just in memory.
        assertTrue(manager().settings.value.showCollapsedTabStrip)
    }

    @Test
    fun `fresh install with no settings file gets the strip and the marker`() {
        // The relocated settings dir exists (BossTerm creates it) but holds no
        // settings.json yet - what a first BossConsole launch looks like.
        assertFalse(File(dir, "settings.json").exists())

        assertTrue(applyCollapsedTabStripDefault(dir, manager()))
        assertTrue(File(dir, "settings.json").exists())
        assertTrue(File(dir, COLLAPSED_TAB_STRIP_MARKER).exists())
        assertTrue(manager().settings.value.showCollapsedTabStrip)
    }

    /** A manager whose saves silently go nowhere: its custom path's parent is never created. */
    private fun brokenManager() = SettingsManager(File(dir, "missing/settings.json").absolutePath)

    @Test
    fun `a save that silently fails throws and is not recorded as done`() {
        // SettingsManager swallows the failed write, so the flip lives only in
        // memory. Recording it as done would lose it for good; it must throw so
        // register()'s catch logs it and the next launch tries again.
        assertFails { applyCollapsedTabStripDefault(dir, brokenManager()) }
        assertEquals("attempts=1", File(dir, COLLAPSED_TAB_STRIP_MARKER).readText().trim())
    }

    @Test
    fun `an unconfirmed flip gives up after the attempt cap`() {
        repeat(COLLAPSED_TAB_STRIP_MAX_ATTEMPTS) {
            assertFails { applyCollapsedTabStripDefault(dir, brokenManager()) }
        }

        // Past the cap it stops touching the setting, so a user who turns the
        // strip off is not overridden on every launch by a check that never passes.
        val sm = brokenManager()
        sm.updateSetting { copy(showCollapsedTabStrip = false) }
        assertFalse(applyCollapsedTabStripDefault(dir, sm))
        assertFalse(sm.settings.value.showCollapsedTabStrip)
    }

    @Test
    fun `a later choice to turn it off survives the next launch`() {
        val sm = manager()
        applyCollapsedTabStripDefault(dir, sm)
        sm.updateSetting { copy(showCollapsedTabStrip = false) }

        val relaunched = manager()
        assertFalse(applyCollapsedTabStripDefault(dir, relaunched))
        assertFalse(relaunched.settings.value.showCollapsedTabStrip)
    }
}
