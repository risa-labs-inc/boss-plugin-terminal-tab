package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Projects [bossHostMcpToolDefs] (`run_in_sidebar`, `cli`) onto the host's
 * [ai.rever.boss.plugin.api.McpToolRegistry], so the two host-facing tools reach an agent through
 * the same door as every plugin-contributed tool instead of being registered straight onto the
 * MCP server.
 *
 * What that door adds, and what the direct registration in [bossHostMcpTools] skipped
 * (BossConsole#495): the per-tool kill-switch, RBAC, the ALLOW/ASK/DENY policy and its approval
 * dialog, the operation ledger, the host result cap, and `{{secret:<id>}}` references resolved
 * by the host after approval. `run_in_sidebar` is already named in the host's shell-tool risk
 * table; this is what makes that entry apply.
 *
 * The definitions themselves are unchanged: [HostMcpTool] stays the single site both the server
 * and the voice source project from, and this class is a third projection of the same list. The
 * handler adapter parses the registry's raw JSON back into the [kotlinx.serialization.json.JsonObject]
 * the existing handlers take, and flattens the SDK result to the registry's text-plus-flag shape
 * exactly as the bridge does in the other direction (`McpDynamicTools.registerOne`).
 *
 * The voice surface is deliberately NOT routed through the registry by this change: BossTerm's
 * voice executor calls [HostMcpTool.handler] directly and gates those calls with its own policy.
 * That path was ungoverned before and stays as it was; this class only closes the MCP endpoint's
 * half of #495 for this plugin's own two tools. BossTerm's built-ins are out of scope here.
 */
internal class HostMcpToolProvider(
    private val tools: List<HostMcpTool> = bossHostMcpToolDefs,
) : McpToolProvider {
    override val providerId: String = PROVIDER_ID

    override fun tools(): List<McpToolDefinition> = tools.map { it.asRegistryTool() }

    companion object {
        /** The plugin id, so provider-wide trust ("Trust this plugin") names the plugin the operator sees. */
        const val PROVIDER_ID: String = "ai.rever.boss.plugin.dynamic.terminaltab"

        private val json = Json { ignoreUnknownKeys = true }

        private val logger = BossLogger.forComponent("TerminalTabHostMcpToolProvider")

        /**
         * The registry hands a raw JSON string; the handlers take the object it decodes to. Anything
         * else becomes `{}`, which the handler then refuses by name ("Missing required argument"),
         * and a debug line records it so host/plugin schema drift is diagnosable. The raw text is
         * never logged: it can hold resolved secret values.
         */
        internal fun argumentsObject(raw: String): JsonObject {
            val parsed =
                try {
                    json.parseToJsonElement(raw) as? JsonObject
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: kotlinx.serialization.SerializationException) {
                    null
                }
            if (parsed == null) {
                logger.debug(LogCategory.TERMINAL, "Registry arguments were not a JSON object; using {}", mapOf("length" to raw.length))
            }
            return parsed ?: JsonObject(emptyMap())
        }
    }
}

/**
 * One [HostMcpTool] as the registry sees it. `readOnly = false` for both: `run_in_sidebar` runs a
 * shell command and `cli` dispatches deep links that open panels, terminals and URLs.
 */
internal fun HostMcpTool.asRegistryTool(): McpToolDefinition =
    McpToolDefinition(
        name = name,
        description = description,
        inputSchema = schema.asJsonSchema(),
        readOnly = false,
        handler = McpToolHandler { args -> handler(HostMcpToolProvider.argumentsObject(args.raw)).asRegistryResult() },
    )

/**
 * The SDK's schema holds `properties` and `required`; the registry wants the JSON Schema
 * document a plugin author would have written. The bridge parses in the other direction
 * (`McpDynamicTools.parseSchema`), so a round trip through both is the identity.
 */
internal fun ToolSchema.asJsonSchema(): String =
    buildJsonObject {
        put("type", "object")
        put("properties", properties ?: JsonObject(emptyMap()))
        put("required", JsonArray((required ?: emptyList()).map(::JsonPrimitive)))
    }.toString()

/** Flatten an SDK result to the registry's text-plus-flag shape; non-text content has no place in it. */
internal fun CallToolResult.asRegistryResult(): McpToolResult =
    McpToolResult(
        text = content.filterIsInstance<TextContent>().joinToString("\n") { it.text },
        isError = isError == true,
    )
