package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.compose.mcp.BossTermMcpConfig
import ai.rever.bossterm.compose.mcp.BossTermMcpServer
import ai.rever.bossterm.compose.settings.SettingsManager
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

class SetupTerminalMcpToolTest {
    private val productionBridge = setupTerminalToolBridge
    private val productionConfig = TerminalMcpConfigHolder.config
    @BeforeTest fun configureTools() { TerminalMcpConfigHolder.config = BossTermMcpConfig(additionalTools = bossHostMcpTools) }
    @AfterTest fun restoreBridge() {
        setupTerminalToolBridge = productionBridge
        TerminalMcpConfigHolder.config = productionConfig
    }
    private fun server() = BossTermMcpServer(
        config = BossTermMcpConfig(additionalTools = bossHostMcpTools),
    ).createServer()

    /**
     * MCP SDK 0.15 hands every tool handler the calling [ClientConnection]. These tools
     * answer from the setup bridge alone - none of them elicits, logs or notifies - so
     * the test passes a proxy that fails loudly the moment one reaches for the client,
     * rather than a stub whose silent defaults would hide that.
     */
    private val noClientConnection = Proxy.newProxyInstance(
        ClientConnection::class.java.classLoader,
        arrayOf(ClientConnection::class.java),
    ) { _, method, _ ->
        error("setup tools must answer without the client connection, but called ${method.name}()")
    } as ClientConnection

    @Test
    fun `dedicated setup terminal tools are registered on actual MCP server`() {
        val names = server().tools.keys
        assertTrue(names.containsAll(setOf(
            "setup_terminal_status", "setup_terminal_read",
            "setup_terminal_send_input", "setup_terminal_send_signal",
        )), names.toString())
    }

    @Test
    fun `dynamic plugins cannot shadow guarded setup tool names`() {
        assertTrue(RESERVED_TOOL_NAMES.containsAll(setupTerminalMcpToolDefs.map { it.name }))
    }

    @Test
    fun `all setup schemas require terminal and handoff request ids`() {
        for (name in setupTerminalMcpToolDefs.map { it.name }) {
            val schema = server().tools.getValue(name).tool.inputSchema.toString()
            assertTrue(schema.contains("terminal_id"), schema)
            assertTrue(schema.contains("request_id"), schema)
        }
    }

    @Test
    fun `missing config denies setup writes`() {
        TerminalMcpConfigHolder.config = null
        assertFalse(server().tools.containsKey("setup_terminal_send_input"))
        assertFalse(server().tools.containsKey("setup_terminal_send_signal"))
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
            tool.handler(noClientConnection, CallToolRequest(CallToolRequestParams(
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
        var handoffActive = true
        setupTerminalToolBridge = object : SetupTerminalToolBridge {
            override fun id() = "live-pty"
            override fun exists(id: String) = id == "live-pty"
            override fun acceptsRequest(id: String, requestId: String) =
                handoffActive && id == "live-pty" && requestId == "accepted-request"
            override fun activity(id: String) = "handoff"
            override fun read(id: String, requestId: String, lines: Int) =
                if (acceptsRequest(id, requestId)) listOf("READY", "prompt") to 2 else null
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
            mcp.tools.getValue(name).handler(noClientConnection, CallToolRequest(CallToolRequestParams(name = name, arguments = args)))
        }
        val readArguments = buildJsonObject {
            put("terminal_id", "live-pty"); put("request_id", "accepted-request"); put("lines", 20)
        }
        val read = call("setup_terminal_read", readArguments)
        assertTrue(read.content.joinToString().contains("READY"), read.toString())
        val write = call("setup_terminal_send_input", buildJsonObject {
            put("terminal_id", "live-pty"); put("request_id", "accepted-request"); put("text", "answer\r")
        })
        assertEquals(false, write.isError)
        assertEquals(listOf("answer\r"), writes)
        val oversized = call("setup_terminal_send_input", buildJsonObject {
            put("terminal_id", "live-pty"); put("request_id", "accepted-request"); put("text", "é".repeat(9_000))
        })
        assertEquals(true, oversized.isError)
        assertEquals(listOf("answer\r"), writes)
        val settings = SettingsManager.instance
        val originalDisabledTools = settings.settings.value.disabledMcpTools
        try {
            settings.updateSetting { copy(disabledMcpTools = setOf("setup_terminal_send_input")) }
            val disabledInputSignal = call("setup_terminal_send_signal", buildJsonObject {
                put("terminal_id", "live-pty"); put("request_id", "accepted-request"); put("signal", "ctrl_d")
            })
            assertEquals(true, disabledInputSignal.isError)
            assertEquals(listOf("answer\r"), writes)
        } finally {
            settings.updateSetting { copy(disabledMcpTools = originalDisabledTools) }
        }
        handoffActive = false
        val staleRead = call("setup_terminal_read", readArguments)
        assertEquals(true, staleRead.isError)
        assertFalse(staleRead.content.joinToString().contains("READY"))
        val missingRequest = call("setup_terminal_read", buildJsonObject { put("terminal_id", "live-pty") })
        assertEquals(true, missingRequest.isError)
    }
}
