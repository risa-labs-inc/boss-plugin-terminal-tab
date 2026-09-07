package ai.rever.boss.plugin.dynamic.terminaltab

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the host-facing MCP tools do with arguments that do not match their schema.
 *
 * [bossHostMcpToolDefs] states the contract these hold themselves to: "Every host hop is guarded:
 * a missing/renamed host class degrades the tool to an error result and never breaks terminals,
 * the MCP server or a call." The host hops are guarded. Argument reading was not.
 *
 * `str` and `bool` reach `JsonElement.jsonPrimitive`, which throws `IllegalArgumentException` on a
 * `JsonObject` or `JsonArray` rather than returning null. Nothing between the transport and those
 * helpers catches it, so a value of the wrong SHAPE - as opposed to the wrong type, which the
 * helpers already tolerate - throws out of the handler.
 *
 * That matters more here than for a hand-written client: these arguments are produced by a model.
 * `{"command": {"value": "ls"}}` and `{"panel_id": ["codebase"]}` are exactly the shapes a model
 * emits when it over-structures a call, and they are not rejected by the JSON parse.
 */
class HostMcpToolArgumentsTest {
    private fun tool(name: String): HostMcpTool =
        bossHostMcpToolDefs.firstOrNull { it.name == name }
            ?: fail("no host tool named '$name'")

    /** Call [name]'s handler, failing the test if it throws rather than returning a result. */
    private fun callOrFail(
        name: String,
        args: JsonObject,
    ) = runBlocking {
        try {
            tool(name).handler(args)
        } catch (t: Throwable) {
            fail("'$name' threw ${t::class.simpleName} out of the handler for $args: ${t.message}")
        }
    }

    @Test
    fun `cli survives an object where a string belongs`() {
        // A model that wraps its argument: {"uri": {"value": "boss://plugin?id=codebase"}}
        val args =
            buildJsonObject {
                put("uri", buildJsonObject { put("value", "boss://plugin?id=codebase") })
            }

        val result = callOrFail("cli", args)
        assertTrue(result.isError == true, "an unusable uri should be an error result, not a success")
    }

    @Test
    fun `cli survives an array where a string belongs`() {
        val args =
            buildJsonObject {
                put("open_panel", true)
                put("panel_id", buildJsonArray { })
            }

        val result = callOrFail("cli", args)
        assertTrue(result.isError == true, "an unusable panel_id should be an error result, not a success")
    }

    @Test
    fun `cli survives an object where a boolean belongs`() {
        // `bool` reaches jsonPrimitive too, so the flag arguments have the same exposure as the
        // string ones and need their own case.
        val args =
            buildJsonObject {
                put("open_terminal", buildJsonObject { put("enabled", true) })
                put("command", "echo hi")
            }

        callOrFail("cli", args)
    }

    @Test
    fun `run_in_sidebar survives an object where a string belongs`() {
        val args =
            buildJsonObject {
                put("command", buildJsonObject { put("value", "ls -la") })
            }

        val result = callOrFail("run_in_sidebar", args)
        assertTrue(result.isError == true, "a missing command should be an error result, not a success")
    }

    @Test
    fun `run_in_sidebar survives a wrongly shaped optional argument`() {
        // The optional arguments are read after the required one passes, so they need their own
        // case: a valid `command` with a wrongly shaped `config_id` reaches further into the
        // handler than any case above.
        val args =
            buildJsonObject {
                put("command", "ls -la")
                put("config_id", buildJsonArray { })
                put("is_rerun", buildJsonObject { put("x", 1) })
            }

        callOrFail("run_in_sidebar", args)
    }

    @Test
    fun `arguments of the wrong primitive type are still read, not rejected`() {
        // The helpers deliberately tolerate a primitive of the wrong type - `bool` falls back to
        // parsing the string form, and `str` takes any primitive's content. Fixing the shape
        // problem must not narrow that, or a model sending "true" instead of true starts failing.
        val args =
            buildJsonObject {
                put("open_panel", "true")
                put("panel_id", "codebase")
            }

        // The host is not reachable from a test JVM, so `cli` errors either way. What separates
        // the two outcomes is WHICH error: failing to resolve a uri at all reports the "Provide
        // one action" message, and resolving one and then failing to dispatch it does not.
        val body = callOrFail("cli", args).content.joinToString { it.toString() }
        assertTrue(
            !body.contains("Provide one action"),
            "open_panel=\"true\" should still resolve a panel uri; instead nothing resolved: $body",
        )
    }
}
