package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.SplitViewOperations

/**
 * Where a link clicked in a terminal goes, through the plugin API.
 *
 * The host's event bus is inaccessible across the plugin classloader. The host-implemented
 * SplitViewOperations.openTerminalLink API routes requests to that bus, preserving the
 * destination chooser and the user's remembered choice.
 */
internal sealed interface TerminalLinkTarget {
    data class Web(val url: String) : TerminalLinkTarget

    data class File(val path: String, val line: Int, val column: Int) : TerminalLinkTarget {
        val fileName: String get() = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }
    }
}

/**
 * [url] as a web page to open in a browser tab, or null when it is not an http or https page.
 * Terminal output can be written by anyone (a `cat`, a commit message, a server's reply), so a
 * `boss://` or `javascript:` link matched in it is left to BossTerm's own handling rather than
 * handed to BOSS's deep-link dispatcher by this plugin.
 */
internal fun webLinkTarget(url: String): TerminalLinkTarget.Web? {
    val scheme = runCatching { java.net.URI(url.trim()).scheme?.lowercase() }.getOrNull()
    return if (scheme == "http" || scheme == "https") TerminalLinkTarget.Web(url) else null
}

/** Request [target] through the host's chooser and remembered preference. Call on the UI thread. */
internal fun openTerminalLink(
    operations: SplitViewOperations,
    target: TerminalLinkTarget,
    sourceTerminalId: String? = null,
) {
    if (!operations.supportsOpenTerminalLink) {
        when (target) {
            is TerminalLinkTarget.Web -> operations.openUrlInActivePanel(target.url, target.url)
            is TerminalLinkTarget.File -> if (target.line > 0) {
                operations.openFileAtPosition(target.path, target.fileName, target.line, target.column)
            } else {
                operations.openFileInActivePanel(target.path, target.fileName)
            }
        }
        return
    }
    val url = when (target) {
        is TerminalLinkTarget.Web -> target.url
        is TerminalLinkTarget.File -> buildString {
            // This is the host's parseFileReference format, not a URI: it decodes before
            // splitting :line[:column]. Escape percent/plus so canonical paths survive unchanged.
            // Like the terminal parser, a literal :digits filename suffix remains ambiguous.
            append("file:")
            append(target.path.replace("%", "%25").replace("+", "%2B"))
            if (target.line > 0) {
                append(":").append(target.line)
                if (target.column > 0) append(":").append(target.column)
            }
        }
    }
    operations.openTerminalLink(url, sourceTerminalId)
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

/**
 * A `file:` URL's path, in each of the forms terminals print (`file:///p`, `file://p`, `file:/p`,
 * `file:p`). A Windows `file:///C:/x` keeps its drive letter first, `C:/x`, so the drive's colon
 * is recognised as such rather than read against the path.
 */
internal fun stripFilePrefix(path: String): String {
    val stripped =
        when {
            path.startsWith("file:///") -> path.removePrefix("file://")
            path.startsWith("file://") -> path.removePrefix("file://")
            path.startsWith("file:") -> path.removePrefix("file:")
            else -> path
        }
    return if (WINDOWS_DRIVE_AFTER_SLASH.containsMatchIn(stripped)) stripped.substring(1) else stripped
}

private val WINDOWS_DRIVE_AFTER_SLASH = Regex("^/[A-Za-z]:[/\\\\]")

internal data class FileReference(val path: String, val line: Int = 0, val column: Int = 0)

/**
 * Split `path[:line[:column]]`, percent-decoding the path first. A Windows drive letter's colon
 * (`C:\...`) is never read as a line separator. The rules of the host's `parseFileReference` in
 * plugin-events, which this plugin cannot load, except that a `+` stays a `+`: `URLDecoder` is a
 * form decoder, and would turn `src/c++/main.cpp` into a path that does not exist.
 */
internal fun parseFileReference(fileUrl: String): FileReference {
    val decoded =
        runCatching { java.net.URLDecoder.decode(fileUrl.replace("+", "%2B"), "UTF-8") }.getOrDefault(fileUrl)
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
