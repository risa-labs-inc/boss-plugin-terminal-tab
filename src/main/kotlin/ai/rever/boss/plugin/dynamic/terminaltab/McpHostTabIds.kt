package ai.rever.boss.plugin.dynamic.terminaltab

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

/**
 * BOSS names a terminal by its host tab id (`terminal-…`, from tabs_list and open_terminal), while
 * BossTerm's tools only know their own tab ids. Wraps every built-in that takes `tab_id` so a host id
 * reaches it as that terminal's active tab. Call before any host tool is added: those take host ids.
 */
internal fun Server.acceptHostTabIds(
    resolve: (String) -> String? = TabbedTerminalStateRegistry::activeTabIdForHost,
    known: (String) -> Boolean = { McpTerminalRegistry.findTab(it) != null },
) {
    val names = tools.values.filter { it.takesTabId() }.map { it.tool.name }.toSet()
    names.forEach { wrapTabIdTool(it, resolve, known) }
    // manage_tools re-enables a tool by registering BossTerm's own handler again; wrap it back.
    tools["manage_tools"]?.let { manage ->
        replaceHandler(manage) { request ->
            manage.handler(this, request).also { names.forEach { wrapTabIdTool(it, resolve, known) } }
        }
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

private val wrappers: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

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
}

private fun RegisteredTool.takesTabId(): Boolean = tool.inputSchema.properties?.containsKey("tab_id") == true
