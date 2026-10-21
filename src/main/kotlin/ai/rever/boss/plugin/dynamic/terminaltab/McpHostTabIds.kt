package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Collections
import java.util.WeakHashMap

private val logger = BossLogger.forComponent("TerminalTabMcpHostTabIds")

// Identity set of our wrapper lambdas; each Server holds its handlers strongly, so entries live as long as needed.
private val wrappers: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

/**
 * BOSS names a terminal by its host tab id (`terminal-…`, from tabs_list and open_terminal), while
 * BossTerm's tools only know their own tab ids. Wraps BossTerm's own tools that take `tab_id` so a
 * host id reaches them as that terminal's active tab; this plugin's and the host's tools are left as
 * they are. See AGENTS.md, "Host tab ids on BossTerm's tools".
 */
internal fun Server.acceptHostTabIds(
    resolve: (String) -> String? = TabbedTerminalStateRegistry::activeTabIdForHost,
    known: (String) -> Boolean = { McpTerminalRegistry.findTab(it) != null },
) {
    wrapTabIdTools(resolve, known)
    // manage_tools re-enables a tool by registering BossTerm's own handler again; wrap it back.
    // A call landing between the remove and the add sees "tool not found"; only here and at creation.
    tools["manage_tools"]?.let { manage ->
        replaceHandler(manage) { request -> manage.handler(this, request).also { wrapTabIdTools(resolve, known) } }
    }
}

/** [request] with a host `tab_id` swapped for the BossTerm tab it names; unchanged otherwise. */
internal fun hostTabIdResolved(request: CallToolRequest, resolve: (String) -> String?, known: (String) -> Boolean): CallToolRequest {
    val args = request.params.arguments ?: return request
    val tabId = (args["tab_id"] as? JsonPrimitive)?.contentOrNull ?: return request
    if (known(tabId)) return request
    val resolved = runCatching { resolve(tabId) }.getOrNull()?.takeIf(known) ?: return request
    return request.copy(params = request.params.copy(arguments = JsonObject(args + ("tab_id" to JsonPrimitive(resolved)))))
}

/** Read from the live set each pass, so a tool disabled at creation is wrapped once it is enabled. */
private fun Server.wrapTabIdTools(resolve: (String) -> String?, known: (String) -> Boolean) =
    tools.values.filter { it.tool.name in bossTermOwnToolNames && it.takesTabId() }
        .forEach { wrapTabIdTool(it.tool.name, resolve, known) }

private fun Server.wrapTabIdTool(name: String, resolve: (String) -> String?, known: (String) -> Boolean) {
    val registered = tools[name] ?: return
    if (registered.handler in wrappers) return
    replaceHandler(registered) { request -> registered.handler(this, hostTabIdResolved(request, resolve, known)) }
}

private fun Server.replaceHandler(
    registered: RegisteredTool,
    handler: suspend ClientConnection.(CallToolRequest) -> CallToolResult,
) {
    wrappers += handler
    // The SDK refuses a duplicate name, so this is remove-then-add; a concurrent re-add loses the race.
    repeat(3) {
        removeTool(registered.tool.name)
        if (runCatching { addTool(registered.tool, handler) }.isSuccess) return
    }
    // Never leave the tool missing: at worst it keeps BossTerm's own handler and takes only BossTerm ids.
    if (tools[registered.tool.name] == null) runCatching { addTool(registered.tool, registered.handler) }
    logger.warn(LogCategory.TERMINAL, "Could not wrap ${registered.tool.name} to accept host tab ids")
}

private fun RegisteredTool.takesTabId(): Boolean = tool.inputSchema.properties?.containsKey("tab_id") == true
