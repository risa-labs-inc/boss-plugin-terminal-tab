package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.compose.settings.SettingsManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Marker file (next to settings.json) for the one-time flip. While the flip has
 * not been confirmed on disk it holds `attempts=N`; any other content means done.
 */
internal const val COLLAPSED_TAB_STRIP_MARKER = ".collapsed-tab-strip-default-v1"

/** Launches allowed to retry an unconfirmed flip before giving up for good. */
internal const val COLLAPSED_TAB_STRIP_MAX_ATTEMPTS = 3

private const val ATTEMPTS_PREFIX = "attempts="
private const val DONE_TEXT = "showCollapsedTabStrip defaulted to true\n"

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
 * settings.json before it counts as done. If it did not land, this throws and
 * the next launch tries again, at most [COLLAPSED_TAB_STRIP_MAX_ATTEMPTS] times:
 * a check that can never pass must not re-flip the setting on every launch and
 * leave the user unable to turn the strip off.
 *
 * `settings.json` is the file name bossterm's SettingsManager uses under
 * `bossterm.settings.dir` (as of 1.2.166), and the read-back relies on
 * updateSetting saving synchronously. Both are re-checked on a bossterm bump
 * (see bosstermVersion in build.gradle.kts).
 *
 * @return true when the flip was applied and confirmed on this call.
 */
internal fun applyCollapsedTabStripDefault(settingsDir: File, settingsManager: SettingsManager): Boolean {
    val marker = File(settingsDir, COLLAPSED_TAB_STRIP_MARKER)
    val attempts = if (marker.exists()) {
        val text = marker.readText()
        if (!text.startsWith(ATTEMPTS_PREFIX)) return false
        text.removePrefix(ATTEMPTS_PREFIX).trim().toIntOrNull() ?: COLLAPSED_TAB_STRIP_MAX_ATTEMPTS
    } else {
        0
    }
    if (attempts >= COLLAPSED_TAB_STRIP_MAX_ATTEMPTS) return false

    // Count the attempt before flipping, so even a launch that dies mid-flip
    // uses one up.
    marker.writeText("$ATTEMPTS_PREFIX${attempts + 1}\n")
    settingsManager.updateSetting { copy(showCollapsedTabStrip = true) }
    check(persistedCollapsedTabStrip(File(settingsDir, "settings.json")) == true) {
        "showCollapsedTabStrip=true did not reach ${settingsDir.absolutePath}/settings.json " +
            "(attempt ${attempts + 1} of $COLLAPSED_TAB_STRIP_MAX_ATTEMPTS)"
    }
    marker.writeText(DONE_TEXT)
    return true
}

private fun persistedCollapsedTabStrip(settingsFile: File): Boolean? = try {
    Json.parseToJsonElement(settingsFile.readText())
        .jsonObject["showCollapsedTabStrip"]?.jsonPrimitive?.booleanOrNull
} catch (_: Exception) {
    null
}
