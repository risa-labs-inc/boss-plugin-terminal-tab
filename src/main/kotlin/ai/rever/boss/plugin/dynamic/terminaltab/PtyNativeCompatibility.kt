package ai.rever.boss.plugin.dynamic.terminaltab

internal fun neutralizeStalePty4jNativeFolder() {
        try {
            if (System.getProperty("pty4j.preferred.native.folder") != null) {
                System.clearProperty("pty4j.preferred.native.folder")
            }
        } catch (_: Throwable) {
            // Best-effort: never let native-path housekeeping block plugin load.
        }
    }
