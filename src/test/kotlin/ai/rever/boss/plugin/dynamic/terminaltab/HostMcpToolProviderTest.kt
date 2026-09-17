package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.McpToolArgs
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The registry projection of this plugin's two host tools: same names, same descriptions, the
 * same schema the MCP bridge would parse back, and a handler that reaches the existing one.
 */
class HostMcpToolProviderTest {
    @Test
    fun `both host tools are offered under the plugin id, neither read-only`() {
        val provider = HostMcpToolProvider()
        assertEquals("ai.rever.boss.plugin.dynamic.terminaltab", provider.providerId)
        val tools = provider.tools()
        assertEquals(bossHostMcpToolDefs.map { it.name }.toSet(), tools.map { it.name }.toSet())
        tools.forEach { assertFalse(it.readOnly, "${it.name} runs a shell or dispatches a deep link") }
        assertEquals(bossHostMcpToolDefs.map { it.description }, tools.map { it.description })
    }

    @Test
    fun `the projected schema round-trips through the bridge's parser`() {
        for (tool in bossHostMcpToolDefs) {
            val parsed = parseSchema(tool.asRegistryTool().inputSchema)
            assertEquals(tool.schema.properties, parsed.properties, tool.name)
            assertEquals(tool.schema.required, parsed.required, tool.name)
        }
    }

    @Test
    fun `run_in_sidebar declares the env argument as an object of strings`() {
        val schema = Json.parseToJsonElement(bossHostMcpToolDefs.first { it.name == "run_in_sidebar" }.asRegistryTool().inputSchema).jsonObject
        val env = schema["properties"]!!.jsonObject["env"]!!.jsonObject
        assertEquals("object", env["type"]!!.jsonPrimitive.content)
        assertEquals("string", env["additionalProperties"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("command"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `the handler adapter hands the existing handler the decoded object and flattens its result`() =
        runBlocking {
            var received: JsonObject? = null
            val tool =
                HostMcpTool(
                    name = "probe",
                    description = "probe",
                    schema = ToolSchema(properties = buildJsonObject { putJsonObject("x") { put("type", "string") } }, required = listOf("x")),
                    handler = { args ->
                        received = args
                        CallToolResult(
                            content = listOf(TextContent(text = "first"), TextContent(text = "second")),
                            isError = true,
                            structuredContent = null,
                            meta = null,
                        )
                    },
                )
            val result = tool.asRegistryTool().handler.call(McpToolArgs(mapOf("x" to "1"), """{"x":"1","n":2}"""))
            assertEquals("1", received!!["x"]!!.jsonPrimitive.content)
            assertEquals("2", received!!["n"]!!.jsonPrimitive.content)
            assertEquals("first\nsecond", result.text)
            assertTrue(result.isError)
        }

    @Test
    fun `malformed raw arguments reach the handler as an empty object, not an exception`() =
        runBlocking {
            var received: JsonObject? = null
            val tool =
                HostMcpTool(
                    name = "probe",
                    description = "probe",
                    schema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
                    handler = { args ->
                        received = args
                        CallToolResult(content = listOf(TextContent(text = "ok")), isError = false, structuredContent = null, meta = null)
                    },
                )
            tool.asRegistryTool().handler.call(McpToolArgs(emptyMap(), "not json"))
            assertEquals(JsonObject(emptyMap()), received)
            tool.asRegistryTool().handler.call(McpToolArgs(emptyMap(), "[1,2]"))
            assertEquals(JsonObject(emptyMap()), received)
        }

    @Test
    fun `the bridge carries the host tools when only BossTerm's names are reserved`() {
        // With the host tools registered through the registry, the bridge must not skip them;
        // the reserved set it is given then holds BossTerm's own names and nothing else.
        assertTrue("run_in_sidebar" in RESERVED_TOOL_NAMES)
        assertFalse("run_in_sidebar" in bossTermOwnToolNames)
        assertFalse("cli" in bossTermOwnToolNames)
    }
}
