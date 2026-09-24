package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.compose.settings.SettingsManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Marker file (next to settings.json) recording that the one-time flip ran. */
internal const val COLLAPSED_TAB_STRIP_MARKER = ".collapsed-tab-strip-default-v1"

/**
 * Turn on BossTerm's "Show Collapsed Sidebar Strip" once for every BossConsole
 * install, new or existing.
 *
 * BossTerm defaults `showCollapsedTabStrip` to false, and its SettingsManager
 * writes every field (encodeDefaults), so an existing settings.json holds an
 * explicit `false` that nobody chose - there is no way to tell "never touched"
 * from "turned off". So this flips it on unconditionally, once, and records that
 * in [COLLAPSED_TAB_STRIP_MARKER]; after that the value is the user's to change.
 *
 * SettingsManager swallows save failures, so the flip is read back from
 * settings.json before the marker is written. If it did not land, this throws
 * and writes no marker, so the next launch tries again.
 *
 * @return true when the flip was applied on this call.
 */
internal fun applyCollapsedTabStripDefault(settingsDir: File, settingsManager: SettingsManager): Boolean {
    val marker = File(settingsDir, COLLAPSED_TAB_STRIP_MARKER)
    if (marker.exists()) return false
    settingsManager.updateSetting { copy(showCollapsedTabStrip = true) }
    check(persistedCollapsedTabStrip(File(settingsDir, "settings.json")) == true) {
        "showCollapsedTabStrip=true did not reach ${settingsDir.absolutePath}/settings.json"
    }
    marker.writeText("showCollapsedTabStrip defaulted to true\n")
    return true
}

private fun persistedCollapsedTabStrip(settingsFile: File): Boolean? = runCatching {
    Json.parseToJsonElement(settingsFile.readText())
        .jsonObject["showCollapsedTabStrip"]?.jsonPrimitive?.booleanOrNull
}.getOrNull()
