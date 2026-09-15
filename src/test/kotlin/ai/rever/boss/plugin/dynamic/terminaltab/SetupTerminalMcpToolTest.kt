package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.compose.mcp.BossTermMcpConfig
import ai.rever.bossterm.compose.mcp.BossTermMcpServer
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.AfterTest

class SetupTerminalMcpToolTest {
    private val productionBridge = setupTerminalToolBridge
    @AfterTest fun restoreBridge() { setupTerminalToolBridge = productionBridge }
    private fun server() = BossTermMcpServer(
        config = BossTermMcpConfig(additionalTools = bossHostMcpTools),
    ).createServer()

    @Test
    fun `dedicated setup terminal tools are registered on actual MCP server`() {
        val names = server().tools.keys
        assertTrue(names.containsAll(setOf(
            "setup_terminal_status", "setup_terminal_read",
            "setup_terminal_send_input", "setup_terminal_send_signal",
        )), names.toString())
    }

    @Test
    fun `write schemas require terminal and handoff request ids`() {
        for (name in listOf("setup_terminal_send_input", "setup_terminal_send_signal")) {
            val schema = server().tools.getValue(name).tool.inputSchema.toString()
            assertTrue(schema.contains("terminal_id"), schema)
            assertTrue(schema.contains("request_id"), schema)
        }
    }

    @Test
    fun `setup tools honor read only mode and per tool exposure`() {
        assertFalse(setupToolAllowed("setup_terminal_send_input", false, emptySet()))
        assertFalse(setupToolAllowed("setup_terminal_send_signal", false, emptySet()))
        assertTrue(setupToolAllowed("setup_terminal_read", false, emptySet()))
        assertFalse(setupToolAllowed("setup_terminal_read", true, setOf("setup_terminal_read")))
    }

    @Test
    fun `endpoint refuses a stale write instead of routing to another terminal`() {
        val tool = server().tools.getValue("setup_terminal_send_input")
        val result = runBlocking {
            tool.handler(CallToolRequest(CallToolRequestParams(
                name = "setup_terminal_send_input",
                arguments = buildJsonObject {
                    put("terminal_id", "stale-terminal")
                    put("request_id", "stale-request")
                    put("text", "echo should-not-run\r")
                },
            )))
        }
        assertEquals(true, result.isError)
        val body = result.content.filterIsInstance<TextContent>().joinToString { it.text.orEmpty() }
        assertTrue(body.contains("stale"), body)
    }

    @Test
    fun `actual endpoint routes valid read and token guarded input to setup PTY bridge`() {
        val writes = mutableListOf<String>()
        setupTerminalToolBridge = object : SetupTerminalToolBridge {
            override fun id() = "live-pty"
            override fun exists(id: String) = id == "live-pty"
            override fun acceptsRequest(id: String, requestId: String) =
                id == "live-pty" && requestId == "accepted-request"
            override fun activity(id: String) = "handoff"
            override fun read(id: String, lines: Int) = listOf("READY", "prompt") to 2
            override fun input(id: String, requestId: String, bytes: ByteArray): Boolean {
                if (id != "live-pty" || requestId != "accepted-request") return false
                writes += bytes.toString(Charsets.UTF_8)
                return true
            }
            override fun interrupt(id: String, requestId: String) = false
            override fun requestInFlight() = false
            override fun debugActive() = true
        }
        val mcp = server()
        fun call(name: String, args: kotlinx.serialization.json.JsonObject) = runBlocking {
            mcp.tools.getValue(name).handler(CallToolRequest(CallToolRequestParams(name = name, arguments = args)))
        }
        val read = call("setup_terminal_read", buildJsonObject { put("terminal_id", "live-pty"); put("lines", 20) })
        assertTrue(read.content.joinToString().contains("READY"), read.toString())
        val write = call("setup_terminal_send_input", buildJsonObject {
            put("terminal_id", "live-pty"); put("request_id", "accepted-request"); put("text", "answer\r")
        })
        assertEquals(false, write.isError)
        assertEquals(listOf("answer\r"), writes)
    }
}
