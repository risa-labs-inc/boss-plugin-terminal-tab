package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.RegisteredMcpTool
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.bossterm.compose.mcp.BossTermMcpServer
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
 * Every tool name BossTerm registers on a server of its own, read from BossTerm
 * instead of restated here.
 *
 * `BUILT_IN_READ_TOOLS` and `BUILT_IN_WRITE_TOOLS` are the two registration maps
 * `createServer` walks; `UNDISABLABLE_TOOLS` is how `manage_tools` — always
 * registered, and deliberately absent from its own `list` output — becomes
 * visible. All three are public on `BossTermMcpServer`'s companion, so this is a
 * read rather than a mirror, and it is a **compile-time** reference: bossterm is
 * bundled in this plugin's JAR, so the version compiled against is the version
 * running, and a rename would fail the build rather than silently skew the set.
 *
 * A hand-written copy of this list is what was here before, and it had already
 * drifted: it was missing `close_panel`, which BossTerm has registered as a write
 * tool for some time. That is what a mirror does. It cost nothing yet only because
 * no plugin happens to have claimed the name — the bridge would have let it
 * through to collide with BossTerm's own on the live server.
 *
 * Union rather than concatenation so a tool moving between the lists (a read
 * becoming undisablable, say) cannot double-count.
 */
internal val bossTermOwnToolNames: Set<String> = (
    BossTermMcpServer.BUILT_IN_READ_TOOLS +
        BossTermMcpServer.BUILT_IN_WRITE_TOOLS +
        BossTermMcpServer.UNDISABLABLE_TOOLS
    ).toSet()

/**
 * Tool names owned by BossTerm ([bossTermOwnToolNames]) and by this plugin's
 * host-facing tools (see [bossHostMcpTools]). A plugin-contributed tool must never
 * shadow one of these, so the bridge skips them.
 */
internal val RESERVED_TOOL_NAMES: Set<String> =
    bossTermOwnToolNames + bossHostMcpToolDefs.map { it.name }

/**
 * The single active registry→server sync coroutine. The MCP engine may stop and
 * restart (settings toggle / port change), calling [installDynamicPluginTools]
 * again with a fresh [Server]; we cancel the prior collector so only the newest
 * server is driven and the old one's coroutine doesn't leak.
 */
@Volatile
private var currentSyncJob: Job? = null

/**
 * Set by [stopDynamicPluginTools] on plugin dispose, cleared by
 * [resumeDynamicPluginTools] on (re-)registration. Guards the dispose-vs-
 * reconcile race: an in-flight engine start (startEngineLocked is not
 * cancellation-aware) can invoke additionalTools AFTER dispose has torn the
 * bridge down — without this flag that would launch a collector on mcpScope
 * (which outlives dispose), pinning the disposed plugin's classloader forever.
 */
@Volatile
private var bridgeDisposed = false

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
    if (bridgeDisposed) {
        dynLogger.warn(LogCategory.TERMINAL, "Bridge install skipped: plugin already disposed")
        return
    }
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

/** Re-arm the bridge; called at plugin (re-)registration before the MCP manager starts. */
internal fun resumeDynamicPluginTools() {
    bridgeDisposed = false
}

/** Cancel the active sync collector and refuse late installs (plugin dispose()). */
internal fun stopDynamicPluginTools() {
    bridgeDisposed = true
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
 *
 * Shared with [BossVoiceToolSource], which projects the same registry onto the
 * in-app voice agent: one parse means the MCP client and the voice agent cannot
 * be shown different parameters for the same tool.
 */
internal fun parseSchema(schema: String): ToolSchema = try {
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
