package ai.rever.boss.plugin.dynamic.terminaltab

/**
 * BossConsole hosts pin the JVM-wide `pty4j.preferred.native.folder` and
 * pre-extract `libpty` into it from the *host* classpath. Now that the
 * terminal — and pty4j — live inside this plugin (not the host), that
 * folder is empty on hosts that no longer carry pty4j, so pty4j loads its
 * native *only* from the pinned (empty) folder and ignores the `libpty`
 * bundled in THIS plugin's JAR → every shell spawn fails with "Failed to
 * spawn process".
 *
 * This plugin always carries its own pty4j native, so clearing the pin is
 * always correct: pty4j then self-extracts the native from this plugin's
 * JAR (its default behaviour), which works on every host — including the
 * release `.app`, where the host-provided folder is merely redundant.
 *
 * We clear unconditionally rather than probing the folder: pty4j's pinned
 * lookup uses a `<folder>/<platform>` layout that's easy to mis-check
 * (e.g. a sibling `pty4j-darwin/` left by a previous self-extraction can
 * make the folder look populated when the platform subdir is empty).
 * Runs at plugin load, before any terminal tab — and thus any PTY — exists.
 */
internal fun neutralizeStalePty4jNativeFolder() {
    try {
        if (System.getProperty("pty4j.preferred.native.folder") != null) {
            System.clearProperty("pty4j.preferred.native.folder")
        }
    } catch (_: Throwable) {
        // Best-effort: never let native-path housekeeping block plugin load.
    }
}
