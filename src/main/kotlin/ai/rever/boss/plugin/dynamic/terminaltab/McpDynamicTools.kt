package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.RegisteredMcpTool
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

private val dynLogger = BossLogger.forComponent("TerminalTabMcpDynamicTools")

private val schemaJson = Json { ignoreUnknownKeys = true }

/**
 * Tool names owned by BossTerm's built-ins, its `manage_tools` meta-tool, and
 * this plugin's host-facing tools (see [bossHostMcpTools]). A plugin-contributed
 * tool must never shadow one of these, so the bridge skips them.
 */
private val RESERVED_TOOL_NAMES: Set<String> = setOf(
    "list_tabs", "get_active_tab", "list_panes", "read_scrollback",
    "search_output", "get_last_command", "read_debug_console",
    "send_input", "send_signal", "run_in_panel", "run_command", "show_image",
    "manage_tools", "run_in_sidebar", "cli",
)

/**
 * The single active registry→server sync coroutine. The MCP engine may stop and
 * restart (settings toggle / port change), calling [installDynamicPluginTools]
 * again with a fresh [Server]; we cancel the prior collector so only the newest
 * server is driven and the old one's coroutine doesn't leak.
 */
@Volatile
private var currentSyncJob: Job? = null

/**
 * Bridge the host [McpToolRegistry] (tools contributed by other active plugins)
 * onto the live MCP [server]. A [kotlinx.coroutines.flow.StateFlow] collector
 * does an initial sync (StateFlow replays its current value) and re-syncs on
 * every change — so a plugin's `mcp__boss__*` tools appear when it loads/enables
 * and disappear when it disables/unloads.
 *
 * Wired in via [ai.rever.bossterm.compose.mcp.BossTermMcpConfig.additionalTools],
 * so these names are NOT prefixed (the server is keyed `boss`).
 */
internal fun installDynamicPluginTools(
    server: Server,
    registry: McpToolRegistry,
    scope: CoroutineScope,
) {
    currentSyncJob?.cancel()
    val mutex = Mutex()
    val present = mutableSetOf<String>() // plugin tool names currently on this server
    currentSyncJob = scope.launch {
        registry.tools.collect { tools ->
            mutex.withLock { syncTools(server, registry, tools, present) }
        }
    }
    dynLogger.info(LogCategory.TERMINAL, "Dynamic plugin MCP tool bridge installed")
}

/** Cancel the active sync collector (called from the plugin's dispose()). */
internal fun stopDynamicPluginTools() {
    currentSyncJob?.cancel()
    currentSyncJob = null
}

private suspend fun syncTools(
    server: Server,
    registry: McpToolRegistry,
    desired: List<RegisteredMcpTool>,
    present: MutableSet<String>,
) {
    // Build the wanted set, dropping reserved names (registry already dedups by name).
    val wanted = LinkedHashMap<String, RegisteredMcpTool>()
    for (tool in desired) {
        val name = tool.definition.name
        if (name in RESERVED_TOOL_NAMES) {
            dynLogger.warn(
                LogCategory.TERMINAL, "Skipping plugin MCP tool with reserved name",
                mapOf("tool" to name, "providerId" to tool.providerId),
            )
            continue
        }
        wanted.putIfAbsent(name, tool)
    }

    // Remove tools that are no longer wanted.
    for (name in present.toList()) {
        if (name !in wanted) {
            runCatching { server.removeTool(name) }.onFailure {
                dynLogger.warn(
                    LogCategory.TERMINAL, "removeTool failed",
                    mapOf("tool" to name, "error" to (it.message ?: "")),
                )
            }
            present.remove(name)
        }
    }

    // Add newly wanted tools.
    for ((name, tool) in wanted) {
        if (name in present) continue
        if (registerOne(server, registry, tool)) present.add(name)
    }
}

private fun registerOne(server: Server, registry: McpToolRegistry, tool: RegisteredMcpTool): Boolean = try {
    val def = tool.definition
    server.addTool(
        name = def.name,
        description = def.description,
        inputSchema = parseSchema(def.inputSchema),
    ) { request ->
        val argsJson = request.arguments?.toString() ?: "{}"
        val result = registry.invoke(def.name, argsJson)
        CallToolResult(
            content = listOf(TextContent(text = result.text)),
            isError = result.isError,
            structuredContent = null,
            meta = null,
        )
    }
    true
} catch (t: Throwable) {
    dynLogger.warn(
        LogCategory.TERMINAL, "Failed to register plugin MCP tool",
        mapOf("tool" to tool.definition.name, "providerId" to tool.providerId, "error" to (t.message ?: "")),
    )
    false
}

/**
 * Parse a plugin's JSON-Schema object string into the MCP SDK's [ToolSchema]
 * (which takes the inner `properties` object + the `required` list). Falls back
 * to an empty (no-argument) schema on any parse error.
 */
private fun parseSchema(schema: String): ToolSchema = try {
    val root = schemaJson.parseToJsonElement(schema) as? JsonObject
    val properties = (root?.get("properties") as? JsonObject) ?: buildJsonObject {}
    val required = (root?.get("required") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?: emptyList()
    ToolSchema(properties = properties, required = required)
} catch (t: Throwable) {
    dynLogger.warn(LogCategory.TERMINAL, "Invalid tool inputSchema; using empty schema", mapOf("error" to (t.message ?: "")))
    ToolSchema(properties = buildJsonObject {}, required = emptyList())
}
