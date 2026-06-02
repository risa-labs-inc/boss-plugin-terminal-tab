package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder

private val hostToolsLogger = BossLogger.forComponent("TerminalTabMcpHostTools")

/**
 * Registers the host-facing MCP tools that let an agent drive BossConsole the way
 * the in-app UI does: open the sidebar terminal and run a command (the "Runner")
 * and dispatch the `boss` CLI's deep-link verbs. Wired into the `boss` MCP server
 * via [ai.rever.bossterm.compose.mcp.BossTermMcpConfig.additionalTools], so the
 * tools surface client-side as `mcp__boss__run_in_sidebar` / `mcp__boss__cli`
 * (names in `additionalTools` are NOT prefixed; the server is keyed `boss`).
 *
 * Both tools reach host-internal classes (`DeepLinkHandler`, `WindowFocusManager`)
 * by reflection — the same pattern [TabbedTerminalStateRegistry]'s `ShellUtils` /
 * `TerminalLinkEventBus` hops use — so the plugin needs no host or boss-plugin-api
 * change. Every host hop is guarded: a missing/renamed host class degrades the
 * tool to an MCP error result and never breaks terminals or the MCP server itself.
 */
internal val bossHostMcpTools: (Server) -> Unit = { server ->
    registerRunInSidebar(server)
    registerCliTool(server)
}

// ---------------------------------------------------------------------------
// Tool 1: run_in_sidebar — open the sidebar terminal and run a command
// ---------------------------------------------------------------------------

private fun registerRunInSidebar(server: Server) {
    server.addTool(
        name = "run_in_sidebar",
        description = "Open BossConsole's sidebar terminal in the focused window and run a shell " +
                "command there — the same flow as the in-app Runner. The sidebar terminal then " +
                "appears in list_tabs / read_scrollback like any other tab, so you can read its " +
                "output afterwards. Pass config_id to keep a stable tab per run configuration, and " +
                "is_rerun=true (with config_id) to re-run in that existing tab (sends Ctrl+C, " +
                "clears, then re-runs) instead of opening a new one.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("command") {
                    put("type", "string")
                    put("description", "Shell command to run in the sidebar terminal.")
                }
                putJsonObject("working_dir") {
                    put("type", "string")
                    put("description", "Optional working directory to cd into before running.")
                }
                putJsonObject("config_id") {
                    put("type", "string")
                    put("description", "Optional stable id for this run configuration; reused as " +
                            "the tab id so re-runs land in the same tab.")
                }
                putJsonObject("is_rerun") {
                    put("type", "boolean")
                    put("description", "When true (with config_id), re-run in the existing tab " +
                            "instead of opening a new one. Default false.")
                }
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Optional label for this run in the top-bar runner dropdown. " +
                            "Defaults to the command.")
                }
            },
            required = listOf("command")
        )
    ) { request ->
        val args = request.arguments
        val command = args.str("command")
        if (command.isNullOrBlank()) {
            return@addTool errorResult("Missing required argument: command")
        }
        val workingDir = args.str("working_dir")
        val isRerun = args.bool("is_rerun") ?: false

        // Stable id used as BOTH the sidebar tab id and the runner config id, so the
        // top-bar runner's running-state and the tab's close-cleanup line up. When the
        // caller doesn't supply one, derive a stable id from the command so re-runs of
        // the same command reuse the same tab/entry instead of piling up.
        val configId = args.str("config_id") ?: "mcp-run-${command.hashCode()}"
        val runName = args.str("name")
            ?: command.trim().lineSequence().firstOrNull()?.take(60)?.ifBlank { null }
            ?: command

        val windowId = focusedWindowId()
            ?: return@addTool errorResult("No focused BossConsole window; focus a window and retry.")

        val started = try {
            TabbedTerminalStateRegistry.newSidebarTab(
                windowId = windowId,
                command = command,
                workingDirectory = workingDir,
                configId = configId,
                isRerun = isRerun
            )
        } catch (t: Throwable) {
            hostToolsLogger.warn(LogCategory.TERMINAL, "run_in_sidebar: newSidebarTab failed", error = t)
            return@addTool errorResult("Failed to start sidebar command: ${t.message}")
        }

        // Ensure the sidebar terminal panel is visible. On a fresh open this also
        // drives the pending-command consumption that actually runs the command
        // (existing Runner flow); if the panel was already open, newSidebarTab
        // already created/re-ran the tab above.
        val panelRequested = processDeepLink("boss://plugin?id=terminal")

        // Register the run with the host runner so the top-bar runner reflects it
        // (selects the config + shows running/Stop). Best-effort; the command still
        // runs even if the host class isn't reachable.
        val runnerUpdated = registerSidebarRunWithRunner(
            windowId = windowId,
            configId = configId,
            command = command,
            workingDir = workingDir,
            name = runName
        )

        jsonResult(isError = false) {
            put("ok", started)
            put("windowId", windowId)
            put("command", command)
            put("configId", configId)
            put("isRerun", isRerun)
            put("panelOpenRequested", panelRequested)
            put("runnerUpdated", runnerUpdated)
        }
    }
}

// ---------------------------------------------------------------------------
// Tool 2: cli — dispatch a boss:// deep link (the `boss` CLI's verbs)
// ---------------------------------------------------------------------------

private fun registerCliTool(server: Server) {
    server.addTool(
        name = "cli",
        description = "Run a BOSS action in the focused window via the host's boss:// deep-link " +
                "dispatcher — the same verbs the `boss` command-line tool uses. Provide exactly " +
                "ONE action: open_panel (+panel_id) to open any sidebar plugin/panel; open_terminal " +
                "(+command) to open the MAIN terminal (for the sidebar Runner use run_in_sidebar); " +
                "open_folder (+path) to open a folder in the codebase; open_url (+url) to open a " +
                "URL in the browser; or a raw `uri` starting with boss://. Known panel ids: " +
                "terminal, console, codebase, bookmarks, downloads, run-configurations, git-status, " +
                "git-log, performance, topofmind, plugin-manager, secret-manager.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("open_panel") {
                    put("type", "boolean")
                    put("description", "Open a sidebar panel/plugin by id (use with panel_id).")
                }
                putJsonObject("panel_id") {
                    put("type", "string")
                    put("description", "Panel id to open, e.g. console, codebase, terminal, git-status.")
                }
                putJsonObject("open_terminal") {
                    put("type", "boolean")
                    put("description", "Open the MAIN terminal (use with optional command).")
                }
                putJsonObject("command") {
                    put("type", "string")
                    put("description", "Command to run for open_terminal.")
                }
                putJsonObject("open_folder") {
                    put("type", "boolean")
                    put("description", "Open a folder in the codebase panel (use with path).")
                }
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Absolute folder path for open_folder.")
                }
                putJsonObject("open_url") {
                    put("type", "boolean")
                    put("description", "Open a URL in the in-app browser (use with url).")
                }
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "URL to open for open_url.")
                }
                putJsonObject("uri") {
                    put("type", "string")
                    put("description", "Raw boss:// deep link (escape hatch); must start with boss://.")
                }
            },
            required = emptyList()
        )
    ) { request ->
        val uri = resolveCliUri(request.arguments)
            ?: return@addTool errorResult(
                "Provide one action: open_panel+panel_id, open_terminal(+command), " +
                        "open_folder+path, open_url+url, or a raw boss:// uri."
            )
        if (!uri.startsWith("boss://")) {
            return@addTool errorResult("Resolved uri must start with boss:// (got: $uri)")
        }
        val dispatched = processDeepLink(uri)
        if (!dispatched) {
            return@addTool errorResult("Host deep-link dispatcher unavailable (DeepLinkHandler not reachable).")
        }
        jsonResult(isError = false) {
            put("ok", true)
            put("uri", uri)
        }
    }
}

/** Map the `cli` tool's structured args to a single `boss://` deep link, or null if none given. */
private fun resolveCliUri(args: JsonObject?): String? {
    args.str("uri")?.let { return it }
    fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    return when {
        args.bool("open_panel") == true -> args.str("panel_id")?.let { "boss://plugin?id=${enc(it)}" }
        args.bool("open_terminal") == true ->
            args.str("command")?.let { "boss://terminal?command=${enc(it)}" } ?: "boss://terminal"
        args.bool("open_folder") == true -> args.str("path")?.let { "boss://folder?path=${enc(it)}" }
        args.bool("open_url") == true -> args.str("url")?.let { "boss://url?url=${enc(it)}" }
        // Shorthand: a bare panel_id with no explicit open_* flag still opens the panel.
        args.str("panel_id") != null -> "boss://plugin?id=${enc(args.str("panel_id")!!)}"
        else -> null
    }
}

// ---------------------------------------------------------------------------
// Host reflection helpers (guarded — never throw out of a tool handler)
// ---------------------------------------------------------------------------

/**
 * Best window id to target, from the host's `WindowFocusManager`. Tries, in order:
 *
 *  1. The private `focusedWindowId` field — set at window registration AND on every
 *     focus gain, so it is non-null in a running app even while BOSS is backgrounded.
 *     This is the field we rely on: an agent drives the app from *another* window
 *     (this terminal), so BOSS is rarely the OS-focused window when a tool fires.
 *  2. The public `focusedWindowFlow.value` (read via the StateFlow interface getter).
 *     Less reliable: that flow is initialized to null and only set on a real
 *     `windowGainedFocus` AWT event — never on registration — so it can be null even
 *     when a window is plainly available.
 *  3. Any registered window (the only one, for the common single-window case).
 *
 * Returns null only if `WindowFocusManager` isn't reachable or has no windows.
 */
private fun focusedWindowId(): String? {
    val clazz = try {
        Class.forName("ai.rever.boss.utils.WindowFocusManager")
    } catch (t: Throwable) {
        hostToolsLogger.warn(LogCategory.SYSTEM, "WindowFocusManager not reachable", error = t)
        return null
    }
    val instance = clazz.getField("INSTANCE").get(null)

    // 1) private var focusedWindowId: String?
    runCatching {
        clazz.getDeclaredField("focusedWindowId").apply { isAccessible = true }.get(instance) as? String
    }.getOrNull()?.let { return it }

    // 2) focusedWindowFlow.value — invoke getValue() on the StateFlow interface so
    //    we don't depend on the concrete (internal) StateFlow impl class.
    runCatching {
        val flow = clazz.getMethod("getFocusedWindowFlow").invoke(instance)
        val stateFlow = Class.forName("kotlinx.coroutines.flow.StateFlow")
        flow?.let { stateFlow.getMethod("getValue").invoke(it) as? String }
    }.getOrNull()?.let { return it }

    // 3) windows map keys — any registered window.
    runCatching {
        val windows = clazz.getDeclaredField("windows").apply { isAccessible = true }.get(instance) as? Map<*, *>
        windows?.keys?.firstOrNull() as? String
    }.getOrNull()?.let { return it }

    hostToolsLogger.warn(LogCategory.SYSTEM, "focusedWindowId: no window id available from WindowFocusManager")
    return null
}

/**
 * Dispatch a `boss://` deep link through the host's `DeepLinkHandler.processDeepLink`.
 * Non-suspend at the call boundary (the handler launches panel opens on the main
 * dispatcher internally). Returns false if the host class isn't reachable.
 */
private fun processDeepLink(uri: String): Boolean = try {
    val clazz = Class.forName("ai.rever.boss.utils.DeepLinkHandler")
    val instance = clazz.getField("INSTANCE").get(null)
    clazz.getMethod("processDeepLink", String::class.java).invoke(instance, uri)
    true
} catch (t: Throwable) {
    hostToolsLogger.warn(LogCategory.SYSTEM, "processDeepLink reflection failed", error = t)
    false
}

/**
 * Tell the host runner about a sidebar run so the top-bar runner reflects it:
 * selects the config in the window's dropdown and marks it running (Stop enabled).
 * Reflects into `RunnerTerminalService.registerSidebarRun` (non-suspend, primitive
 * args). The sidebar terminal is already opened by [TabbedTerminalStateRegistry];
 * this only updates runner state, which the host clears on tab close. Returns false
 * if the host class isn't reachable.
 */
private fun registerSidebarRunWithRunner(
    windowId: String,
    configId: String,
    command: String,
    workingDir: String?,
    name: String
): Boolean = try {
    val clazz = Class.forName("ai.rever.boss.run.RunnerTerminalService")
    val instance = clazz.getField("INSTANCE").get(null)
    clazz.getMethod(
        "registerSidebarRun",
        String::class.java, String::class.java, String::class.java, String::class.java, String::class.java
    ).invoke(instance, windowId, configId, command, workingDir, name)
    true
} catch (t: Throwable) {
    hostToolsLogger.warn(LogCategory.TERMINAL, "registerSidebarRun reflection failed", error = t)
    false
}

// ---------------------------------------------------------------------------
// MCP arg / result helpers (mcp-sdk 0.8.3 shapes)
// ---------------------------------------------------------------------------

private fun JsonObject?.str(key: String): String? {
    val el = this?.get(key) ?: return null
    if (el is JsonNull) return null
    return el.jsonPrimitive.content
}

private fun JsonObject?.bool(key: String): Boolean? {
    val el = this?.get(key) ?: return null
    if (el is JsonNull) return null
    val prim = el.jsonPrimitive
    return prim.booleanOrNull ?: prim.content.toBooleanStrictOrNull()
}

private fun jsonResult(isError: Boolean, build: JsonObjectBuilder.() -> Unit): CallToolResult {
    val body = buildJsonObject(build).toString()
    return CallToolResult(
        content = listOf(TextContent(text = body)),
        isError = isError,
        structuredContent = null,
        meta = null
    )
}

private fun errorResult(message: String): CallToolResult = jsonResult(isError = true) { put("error", message) }
