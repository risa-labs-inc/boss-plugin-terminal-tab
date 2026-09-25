package ai.rever.boss.plugin.dynamic.terminaltab

import java.io.File

/**
 * A child of BossConsole's data root - `~/.boss` in normal mode, `~/.boss_debug` in dev mode.
 *
 * Resolved through the host's `BossDirectories` (the single source of truth) by reflection, since
 * that class lives in the host classloader rather than boss-plugin-api, with the same dev-mode
 * rule as a fallback when the host class is not reachable. One function for the two callers
 * (BossTerm's settings directory and the env-injection files) so they cannot resolve the root
 * differently.
 */
internal fun bossDataDir(child: String): File =
    try {
        val clazz = Class.forName("ai.rever.boss.plugin.pathutils.BossDirectories")
        val instance = clazz.getField("INSTANCE").get(null)
        clazz.getMethod("resolve", String::class.java).invoke(instance, child) as File
    } catch (_: Throwable) {
        val root = if (isBossDevMode()) ".boss_debug" else ".boss"
        File(File(System.getProperty("user.home"), root), child)
    }

internal fun isBossDevMode(): Boolean {
    fun truthy(v: String?) = v?.trim()?.lowercase()?.let { it == "true" || it == "1" || it == "yes" } ?: false
    return truthy(System.getProperty("boss.dev.mode")) || truthy(System.getenv("BOSS_DEV_MODE"))
}
