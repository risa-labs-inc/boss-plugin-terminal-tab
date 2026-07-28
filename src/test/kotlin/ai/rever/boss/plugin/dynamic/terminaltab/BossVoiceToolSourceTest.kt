package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.McpToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The projection from the host's MCP tool registry onto BossTerm's voice seam.
 *
 * What is NOT tested here, deliberately: the merge itself (name sanitising, the
 * collision drop, the ceiling) is `internal` to bossterm-compose and has its own
 * tests there. These pin the half this plugin owns — what goes in, and what comes
 * back out through `call`.
 */
class BossVoiceToolSourceTest {

    private fun source(registry: FakeToolRegistry?) =
        BossVoiceToolSource(registryProvider = { registry })

    private fun sourceWithoutHostTools(registry: FakeToolRegistry?) =
        BossVoiceToolSource(registryProvider = { registry }, hostTools = emptyList())

    // ---------------------------------------------------------------- mapping

    @Test
    fun `projects description schema and required off the definition`() {
        val registry = FakeToolRegistry(
            listOf(
                toolDef(
                    name = "git_status",
                    description = "Working-tree status of the current project.",
                    inputSchema = schemaOf("path", "porcelain", required = listOf("path")),
                )
            )
        )

        val tool = sourceWithoutHostTools(registry).tools().single()

        assertEquals("git_status", tool.name)
        assertEquals("Working-tree status of the current project.", tool.description)
        assertEquals(setOf("path", "porcelain"), tool.properties.keys)
        assertEquals("string", tool.properties["path"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("path"), tool.required)
    }

    @Test
    fun `write mirrors the definition's readOnly hint`() {
        val registry = FakeToolRegistry(
            listOf(
                toolDef("git_log", readOnly = true),
                toolDef("git_stage", readOnly = false),
            )
        )

        val byName = sourceWithoutHostTools(registry).tools().associateBy { it.name }

        assertFalse(byName.getValue("git_log").write)
        assertTrue(byName.getValue("git_stage").write)
    }

    @Test
    fun `host tools are offered with their own schema and are not read-only`() {
        val tools = source(FakeToolRegistry()).tools().associateBy { it.name }

        val cli = assertNotNull(tools["cli"], "cli should be offered")
        val runner = assertNotNull(tools["run_in_sidebar"], "run_in_sidebar should be offered")
        assertTrue(cli.description.contains("boss://"), "description comes from McpHostTools")
        assertTrue(cli.write && runner.write, "both drive the app")
        assertTrue("command" in runner.properties.keys)
        assertEquals(listOf("command"), runner.required)
        // Neither is confirmation-gated — see BossVoiceToolSafety's KDoc for why.
        assertFalse(cli.irreversible || runner.irreversible)
    }

    @Test
    fun `a host tool is classified by the same rules as a registry tool`() {
        // Neither host tool is destructive today, so the classifier call in the host
        // projection has no observable effect — this is what makes it observable. A
        // third host tool that overwrites something must be gated without anyone
        // remembering that the host path is a separate projection.
        val destructive = HostMcpTool(
            name = "codebase_write",
            description = "overwrites a file",
            schema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
            handler = { CallToolResult(content = listOf(TextContent(text = "{}"))) },
        )

        val tool = BossVoiceToolSource(registryProvider = { null }, hostTools = listOf(destructive))
            .tools()
            .single()

        assertTrue(tool.irreversible, "a destructive host tool must be confirmation-gated")
    }

    // ------------------------------------------------- BossTerm's own thirteen

    @Test
    fun `never offers a tool BossTerm already advertises`() {
        // A plugin that registers `run_command` is skipped by the MCP bridge, so it
        // must be skipped here too: BossTerm's implementation is the one the agent's
        // instructions describe, and a second route would bypass its scope logic.
        val registry = FakeToolRegistry(
            BOSSTERM_OWN_TOOL_NAMES.map { toolDef(it) } + toolDef("git_status")
        )

        val offered = source(registry).tools().map { it.name }

        assertEquals(listOf("cli", "run_in_sidebar", "git_status").sorted(), offered.sorted())
        BOSSTERM_OWN_TOOL_NAMES.forEach {
            assertFalse(it in offered, "$it is BossTerm's own and must not be re-exported")
        }
    }

    @Test
    fun `the live surface contributes no name BossTerm owns`() {
        val registry = FakeToolRegistry(LIVE_EXTERNAL_TOOL_NAMES.map { toolDef(it) })

        val offered = source(registry).tools().map { it.name }.toSet()

        assertTrue(offered.intersect(BOSSTERM_OWN_TOOL_NAMES).isEmpty())
        // 105 registry tools (107 external minus the 2 this plugin defines itself,
        // which the fixture also lists) + the 2 host tools projected once each.
        assertEquals(LIVE_EXTERNAL_TOOL_NAMES.size, offered.size)
    }

    // --------------------------------------------------------------- dynamism

    @Test
    fun `tools is re-read so a plugin registering mid-call is seen`() {
        val registry = FakeToolRegistry(listOf(toolDef("git_status")))
        val source = sourceWithoutHostTools(registry)

        assertEquals(listOf("git_status"), source.tools().map { it.name })

        registry.setTools(listOf(toolDef("git_status"), toolDef("docker_ps")))
        assertEquals(listOf("git_status", "docker_ps").sorted(), source.tools().map { it.name }.sorted())

        registry.setTools(emptyList())
        assertEquals(emptyList(), source.tools().map { it.name })
    }

    @Test
    fun `an unavailable registry leaves the host tools reachable`() {
        val tools = source(null).tools().map { it.name }

        assertEquals(listOf("cli", "run_in_sidebar"), tools)
    }

    // ----------------------------------------------------------- malformed input

    @Test
    fun `a malformed schema degrades to no parameters instead of dropping the tool`() {
        val registry = FakeToolRegistry(
            listOf(
                toolDef("a_tool", inputSchema = "not json at all"),
                toolDef("b_tool", inputSchema = """{"type":"object","properties":5}"""),
                toolDef("c_tool", inputSchema = ""),
            )
        )

        val tools = sourceWithoutHostTools(registry).tools()

        assertEquals(3, tools.size)
        tools.forEach { assertTrue(it.properties.isEmpty(), "${it.name} should have no parameters") }
        // A no-argument tool is legitimate, so BossTerm keeps these; a tool that
        // declared parameters and lost them all is what it drops.
        tools.forEach { assertTrue(it.required.isEmpty()) }
    }

    // ----------------------------------------------------------------- routing

    @Test
    fun `call reaches the registry under the source's own name`() = runBlocking {
        val registry = FakeToolRegistry(listOf(toolDef("git_status")))
        val args = buildJsonObject { put("path", "/tmp") }

        val result = source(registry).call("git_status", args)

        assertEquals(1, registry.invocations.size)
        assertEquals("git_status", registry.invocations.single().first)
        assertEquals("""{"path":"/tmp"}""", registry.invocations.single().second)
        assertEquals("""{"invoked":"git_status"}""", result)
    }

    @Test
    fun `call routes a host tool to its handler and never to the registry`() = runBlocking {
        val registry = FakeToolRegistry()

        // `cli` with no action is a clean, host-independent refusal from the tool
        // itself — enough to prove the handler ran rather than the registry.
        val result = source(registry).call("cli", JsonObject(emptyMap()))

        assertTrue(registry.invocations.isEmpty(), "must not fall through to the registry")
        val error = Json.parseToJsonElement(result).jsonObject["error"]
        assertNotNull(error, "expected the cli tool's own error payload, got: $result")
        assertTrue(error.jsonPrimitive.content.contains("open_panel"))
    }

    @Test
    fun `call without a registry answers with JSON rather than throwing`() = runBlocking {
        val result = source(null).call("git_status", JsonObject(emptyMap()))

        val error = Json.parseToJsonElement(result).jsonObject["error"]
        assertNotNull(error)
        assertTrue(error.jsonPrimitive.content.contains("registry"))
    }

    // ---------------------------------------------------------- result shaping

    @Test
    fun `a JSON result is passed through untouched`() {
        assertEquals("""{"a":1}""", BossVoiceToolSource.asJson("""{"a":1}"""))
        assertEquals("""[1,2]""", BossVoiceToolSource.asJson("""  [1,2]  """))
    }

    @Test
    fun `prose is wrapped so the contract holds`() {
        val wrapped = BossVoiceToolSource.asJson("3 containers are running")
        assertEquals(
            "3 containers are running",
            Json.parseToJsonElement(wrapped).jsonObject["result"]!!.jsonPrimitive.content,
        )
        // Looks structured but is not parseable: must still come back as valid JSON.
        val broken = BossVoiceToolSource.asJson("{not really json}")
        assertNotNull(Json.parseToJsonElement(broken).jsonObject["result"])
    }

    @Test
    fun `blank becomes an empty object`() {
        assertEquals("{}", BossVoiceToolSource.asJson(""))
        assertEquals("{}", BossVoiceToolSource.asJson("   \n "))
    }

    @Test
    fun `an error result is reported as an error payload`() = runBlocking {
        val registry = FakeToolRegistry(
            listOf(toolDef("git_status")),
            invokeResult = { _, _ -> McpToolResult("no project selected", isError = true) },
        )

        val result = source(registry).call("git_status", JsonObject(emptyMap()))

        assertEquals(
            "no project selected",
            Json.parseToJsonElement(result).jsonObject["error"]!!.jsonPrimitive.content,
        )
    }

    // ---------------------------------------------------------------- ordering

    @Test
    fun `the ceiling drops the least useful families, not whatever registered last`() {
        val registry = FakeToolRegistry(
            LIVE_EXTERNAL_TOOL_NAMES.filterNot { it in setOf("cli", "run_in_sidebar") }
                // Reversed, so registry order is actively hostile to the expectation.
                .reversed()
                .map { toolDef(it) }
        )

        val source = source(registry)
        val ordered = source.tools().map { it.name }

        assertEquals(listOf("cli", "run_in_sidebar"), ordered.take(2))
        assertTrue(ordered.indexOf("git_status") < ordered.indexOf("docker_ps"))
        assertTrue(ordered.indexOf("codebase_read") < ordered.indexOf("flow_run"))
        // RBAC is the last thing advertised: the least plausible thing to do by
        // voice and the worst to mishear, so it is what the ceiling takes first.
        // Measured against what CAN be advertised — the excluded secret tools sort
        // behind even RBAC, and are dropped before the ceiling is counted, so their
        // position buys nothing either way.
        val advertisable = ordered.filterNot { name ->
            source.tools().first { it.name == name }.let { source.policy.isExcluded(it) }
        }
        val rbac = advertisable.filter {
            it.startsWith("role_") || it.startsWith("permission") || it.startsWith("user") ||
                it == "roles_list" || it == "users_list"
        }
        assertEquals(12, rbac.size)
        assertEquals(advertisable.takeLast(rbac.size), rbac)
    }

    @Test
    fun `an unlisted family sorts after every listed one`() {
        val registry = FakeToolRegistry(
            listOf(toolDef("zzz_brand_new_tool"), toolDef("users_list"), toolDef("git_status"))
        )

        val ordered = sourceWithoutHostTools(registry).tools().map { it.name }

        assertEquals(listOf("git_status", "users_list", "zzz_brand_new_tool"), ordered)
    }

    @Test
    fun `ordering is deterministic regardless of registry order`() {
        val names = LIVE_EXTERNAL_TOOL_NAMES.filterNot { it in setOf("cli", "run_in_sidebar") }
        val forward = source(FakeToolRegistry(names.map { toolDef(it) })).tools().map { it.name }
        val shuffled = source(FakeToolRegistry(names.shuffled().map { toolDef(it) })).tools().map { it.name }

        assertEquals(forward, shuffled)
    }

    @Test
    fun `the ceiling is high enough for the measured surface`() {
        val registry = FakeToolRegistry(
            LIVE_EXTERNAL_TOOL_NAMES.filterNot { it in setOf("cli", "run_in_sidebar") }.map { toolDef(it) }
        )
        val source = source(registry)

        val advertisable = source.tools().filterNot { source.policy.isExcluded(it) }

        assertTrue(
            advertisable.size <= BossVoiceToolSource.MAX_EXTERNAL_TOOLS,
            "${advertisable.size} advertisable tools against a ceiling of " +
                "${BossVoiceToolSource.MAX_EXTERNAL_TOOLS}: the ordering is now load-bearing, " +
                "and tools past the ceiling are dropped silently to the user.",
        )
        // Plus BossTerm's own 13, under the 128-function limit the ceiling is set from.
        assertTrue(BossVoiceToolSource.MAX_EXTERNAL_TOOLS + BOSSTERM_OWN_TOOL_NAMES.size <= 128)
    }

    // ------------------------------------------------------ policy consistency

    @Test
    fun `policy and per-tool flags agree on the whole live surface`() {
        val registry = FakeToolRegistry(LIVE_EXTERNAL_TOOL_NAMES.map { toolDef(it) })
        val source = source(registry)

        source.tools().forEach { tool ->
            // The flag route and the excludeExtra route are independent by design;
            // if they ever disagree, one of them has stopped protecting anything.
            assertEquals(
                tool.sensitive,
                BossVoiceToolSafety.isSecret(tool.name),
                "sensitive flag and excludeExtra disagree on ${tool.name}",
            )
            assertEquals(
                tool.irreversible,
                BossVoiceToolSafety.isIrreversible(tool.name),
                "irreversible flag and irreversibleExtra disagree on ${tool.name}",
            )
            if (tool.sensitive) {
                assertNotNull(source.policy.exclusionReason(tool), "${tool.name} must be excluded")
            }
            if (tool.irreversible) {
                assertTrue(source.policy.isIrreversible(tool), "${tool.name} must be gated")
            }
        }
    }

    @Test
    fun `no host approver is installed so the in-band handshake is the gate`() {
        // Documented behaviour rather than an accident: with an approver installed
        // BossTerm advertises no confirmation token, so flipping this changes what
        // every gated tool tells the model. See BossVoiceToolSource's KDoc.
        assertNull(source(FakeToolRegistry()).policy.approve)
    }
}
