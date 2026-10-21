package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.compose.mcp.BossTermMcpConfig
import ai.rever.bossterm.compose.mcp.BossTermMcpServer
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class McpHostTabIdsTest {
    private val productionConfig = TerminalMcpConfigHolder.config
    private val hostId = "terminal-1792501900063-94ef38779f807f67"
    private val resolve = { id: String -> if (id == hostId) "bossterm-tab" else null }
    private val known = { id: String -> id == "bossterm-tab" }
    private val connection = Proxy.newProxyInstance(
        ClientConnection::class.java.classLoader, arrayOf(ClientConnection::class.java),
    ) { _, _, _ -> null } as ClientConnection

    @BeforeTest
    fun configure() {
        TerminalMcpConfigHolder.config = BossTermMcpConfig()
    }

    @AfterTest
    fun restore() {
        TerminalMcpConfigHolder.config = productionConfig
    }

    private fun request(tool: String, tabId: String) =
        CallToolRequest(CallToolRequestParams(name = tool, arguments = buildJsonObject { put("tab_id", tabId); put("text", "ls") }))

    private fun server(): Server = BossTermMcpServer(
        config = BossTermMcpConfig(additionalTools = { it.acceptHostTabIds(resolve, known) }),
    ).createServer()

    private fun Server.call(tool: String, tabId: String): String = runBlocking {
        val result = tools.getValue(tool).handler(connection, request(tool, tabId))
        result.content.filterIsInstance<TextContent>().joinToString { it.text }
    }

    @Test
    fun `a host id is swapped for the BossTerm tab it names`() {
        val swapped = hostTabIdResolved(request("send_input", hostId), resolve, known)
        assertEquals(JsonPrimitive("bossterm-tab"), swapped.params.arguments?.get("tab_id"))
    }

    @Test
    fun `BossTerm ids and unknown ids pass through untouched`() {
        for (id in listOf("bossterm-tab", "terminal-unknown", "anything")) {
            val req = request("send_input", id)
            assertSame(req, hostTabIdResolved(req, resolve, known))
        }
        val unknownTarget = { _: String -> "not-a-tab" }
        val req = request("send_input", hostId)
        assertSame(req, hostTabIdResolved(req, unknownTarget, known))
    }

    @Test
    fun `the terminal tools on the server receive the resolved id`() {
        val server = server()
        // No tab is registered, so BossTerm names the id it was handed: the resolved one.
        for (tool in listOf("list_panes", "read_scrollback", "send_input")) {
            val out = server.call(tool, hostId)
            assertTrue("bossterm-tab" in out, "$tool did not get the resolved id: $out")
        }
        val unknownHostId = hostId.replace("94ef", "0000")
        assertTrue(unknownHostId in server.call("list_panes", unknownHostId))
    }

    @Test
    fun `a tool registered again behind the wrapper is wrapped again after manage_tools`() {
        val server = server()
        val original = BossTermMcpServer(config = BossTermMcpConfig()).createServer().tools.getValue("list_panes")
        server.removeTool("list_panes")
        server.addTool(original.tool, original.handler)
        assertTrue(hostId in server.call("list_panes", hostId))

        manageToolsList(server)
        assertTrue("bossterm-tab" in server.call("list_panes", hostId))
    }

    @Test
    fun `a tool disabled at creation is wrapped once manage_tools runs`() {
        val original = BossTermMcpServer(config = BossTermMcpConfig()).createServer().tools.getValue("read_scrollback")
        val server = BossTermMcpServer(
            config = BossTermMcpConfig(additionalTools = { it.removeTool("read_scrollback"); it.acceptHostTabIds(resolve, known) }),
        ).createServer()
        // BossTerm registers it again when it is enabled.
        server.addTool(original.tool, original.handler)
        assertTrue(hostId in server.call("read_scrollback", hostId))

        manageToolsList(server)
        assertTrue("bossterm-tab" in server.call("read_scrollback", hostId))
    }

    @Test
    fun `tools that are not BossTerm's own keep taking host ids as they are`() {
        val server = BossTermMcpServer(
            config = BossTermMcpConfig(additionalTools = {
                it.addTool(
                    Tool(
                        name = "tab_focus",
                        inputSchema = ToolSchema(
                            properties = buildJsonObject { put("tab_id", buildJsonObject { put("type", "string") }) },
                        ),
                    ),
                ) { request ->
                    CallToolResult(
                        content = listOf(TextContent("got ${request.arguments?.get("tab_id")}")),
                    )
                }
                it.acceptHostTabIds(resolve, known)
            }),
        ).createServer()
        assertTrue(hostId in server.call("tab_focus", hostId))
        manageToolsList(server)
        assertTrue(hostId in server.call("tab_focus", hostId))
    }

    private fun manageToolsList(server: Server) = runBlocking {
        server.tools.getValue("manage_tools").handler(
            connection,
            CallToolRequest(CallToolRequestParams(name = "manage_tools", arguments = buildJsonObject { put("operation", "list") })),
        )
    }

    @Test
    fun `close_panel keeps BossTerm ids only`() {
        // Not resolved: the raw host id reaches BossTerm, which names it as unknown.
        assertTrue(hostId in server().call("close_panel", hostId))
    }
}
