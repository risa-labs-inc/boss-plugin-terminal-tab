package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.compose.settings.SettingsManager
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.assertFails
import kotlin.test.Test
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

    @Test
    fun `a save that silently fails throws and writes no marker`() {
        // SettingsManager does not create a custom path's parent and swallows the
        // failed write, so the flip lives only in memory. Recording the marker
        // here would lose the flip for good; it must throw so register()'s catch
        // logs it and the next launch tries again.
        val missing = File(dir, "missing")
        val sm = SettingsManager(File(missing, "settings.json").absolutePath)

        assertFails { applyCollapsedTabStripDefault(missing, sm) }
        assertFalse(File(missing, COLLAPSED_TAB_STRIP_MARKER).exists())
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
