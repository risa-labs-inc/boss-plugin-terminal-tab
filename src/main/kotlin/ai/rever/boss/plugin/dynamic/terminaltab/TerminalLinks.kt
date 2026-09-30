package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.SplitViewOperations

/**
 * Where a link clicked in a terminal goes, through the plugin API.
 *
 * These used to be emitted on the host's `TerminalLinkEventBus` by reflection, which the plugin
 * classloader refuses since BOSS 9.5.25 (see [HostWindows]), so every click logged "Failed to emit
 * terminal link click" and did nothing. The host's ask-or-remember link dialog listens only on
 * that bus and has no plugin-API equivalent, so a web link now opens straight in a browser tab
 * and a file at its line; bringing the dialog back needs a host-implemented API.
 */
internal sealed interface TerminalLinkTarget {
    data class Web(val url: String) : TerminalLinkTarget

    data class File(val path: String, val line: Int, val column: Int) : TerminalLinkTarget {
        val fileName: String get() = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }
    }
}

/** Open [target] in the window that [operations] belong to. Call on the UI thread. */
internal fun openTerminalLink(operations: SplitViewOperations, target: TerminalLinkTarget) {
    when (target) {
        is TerminalLinkTarget.Web -> operations.openUrlInActivePanel(target.url, target.url)
        is TerminalLinkTarget.File ->
            if (target.line > 0) {
                operations.openFileAtPosition(target.path, target.fileName, target.line, target.column)
            } else {
                operations.openFileInActivePanel(target.path, target.fileName)
            }
    }
}

/**
 * A file link's reference as it will be opened: the `file:` prefix and any `:line[:column]`
 * suffix split off, the path made canonical, or null when no such file exists. [canonical] is a
 * seam for tests; it answers null for a path that is not an existing file.
 */
internal fun resolveFileLink(
    url: String,
    canonical: (String) -> String? = ::canonicalExistingPath,
): TerminalLinkTarget.File? {
    val reference = parseFileReference(stripFilePrefix(url))
    val path = canonical(reference.path) ?: return null
    return TerminalLinkTarget.File(path, reference.line, reference.column)
}

private fun canonicalExistingPath(path: String): String? =
    try {
        java.io.File(path).takeIf { it.exists() }?.canonicalPath
    } catch (_: Exception) {
        null
    }

/** A `file:` URL's path, in each of the forms terminals print (`file:///p`, `file://p`, `file:/p`, `file:p`). */
internal fun stripFilePrefix(path: String): String =
    when {
        path.startsWith("file:///") -> path.removePrefix("file://")
        path.startsWith("file://") -> path.removePrefix("file://")
        path.startsWith("file:") -> path.removePrefix("file:")
        else -> path
    }

internal data class FileReference(val path: String, val line: Int = 0, val column: Int = 0)

/**
 * Split `path[:line[:column]]`, URL-decoding the path first. A Windows drive letter's colon
 * (`C:\...`) is never read as a line separator. Same rules as the host's `parseFileReference`
 * in plugin-events, which this plugin cannot load.
 */
internal fun parseFileReference(fileUrl: String): FileReference {
    val decoded = runCatching { java.net.URLDecoder.decode(fileUrl, "UTF-8") }.getOrDefault(fileUrl)
    val start = if (decoded.length >= 2 && decoded[0].isLetter() && decoded[1] == ':') 2 else 0
    val last = decoded.lastIndexOf(':')
    val secondLast = if (last > 0) decoded.lastIndexOf(':', last - 1) else -1
    val afterLast = if (last > start) decoded.substring(last + 1).toIntOrNull() else null
    val afterSecondLast =
        if (secondLast > start && last > secondLast) decoded.substring(secondLast + 1, last).toIntOrNull() else null
    return when {
        afterSecondLast != null && afterLast != null -> FileReference(decoded.substring(0, secondLast), afterSecondLast, afterLast)
        afterLast != null -> FileReference(decoded.substring(0, last), afterLast)
        else -> FileReference(decoded)
    }
}
