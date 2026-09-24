package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.compose.settings.SettingsManager
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
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
    fun `a later choice to turn it off survives the next launch`() {
        val sm = manager()
        applyCollapsedTabStripDefault(dir, sm)
        sm.updateSetting { copy(showCollapsedTabStrip = false) }

        val relaunched = manager()
        assertFalse(applyCollapsedTabStripDefault(dir, relaunched))
        assertFalse(relaunched.settings.value.showCollapsedTabStrip)
    }
}
