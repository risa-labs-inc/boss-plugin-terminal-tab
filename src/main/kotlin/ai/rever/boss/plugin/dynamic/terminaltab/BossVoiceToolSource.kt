package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.bossterm.compose.voice.ExternalVoiceTool
import ai.rever.bossterm.compose.voice.VoiceToolPolicy
import ai.rever.bossterm.compose.voice.VoiceToolSource
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Process-wide holder for the `boss` tool surface offered to Boss Calling.
 *
 * Mirrors [TerminalMcpConfigHolder]: the registry arrives in
 * [TerminalTabDynamicPlugin.register] and the consumer is a composable
 * ([TerminalComposables]' two `TabbedTerminal` call sites), with no plugin-API
 * surface between them to thread it through.
 *
 * One [source] instance for the JVM, deliberately. BossTerm namespaces its
 * "already warned about that tool" log slots by the source's identity hash, so a
 * fresh instance per composition would re-warn the whole surface on every
 * recomposition of a terminal panel.
 */
internal object BossVoiceTools {

    @Volatile
    private var registry: McpToolRegistry? = null

    /** Read afresh on every enumeration — never captured. See [BossVoiceToolSource.tools]. */
    val source: VoiceToolSource = BossVoiceToolSource(registryProvider = { registry })

    /** Called from the plugin's register(); null is tolerated (host tools still work). */
    fun bind(registry: McpToolRegistry?) {
        this.registry = registry
    }

    /** Called from the plugin's dispose(), so a disposed registry isn't held past unload. */
    fun unbind() {
        registry = null
    }
}

/**
 * The `boss` MCP tool surface, offered to BossTerm's in-app voice agent.
 *
 * ### Where the tools come from, and why from there
 *
 * The `boss` MCP server this plugin hosts serves three groups of tools, and they
 * have three different definition sites:
 *
 *  1. **BossTerm's own thirteen** (`run_command`, `read_scrollback`, `list_tabs`, …
 *     plus `manage_tools`) — registered by `BossTermMcpServer` itself. These are
 *     deliberately **not** projected here: BossTerm's voice executor already
 *     advertises them from `registeredToolInfo()`, which is their definition site.
 *     Re-exporting them would produce a second route to the same tool, past the
 *     scope and focused-pane logic in front of BossTerm's implementation — the
 *     case `VoiceToolCollisionPolicy.DropExternal` exists to catch. Leaving them
 *     out means the merge has nothing to drop rather than relying on it to.
 *  2. **This plugin's host-facing tools** ([bossHostMcpToolDefs] — `run_in_sidebar`
 *     and `cli`) — defined in `McpHostTools.kt`, which is now the single site both
 *     the MCP server and this class project from. Worth noting they are otherwise
 *     unreachable from a call: BossTerm builds the voice surface's private server
 *     with `includeEmbedderTools = false`, so `additionalTools` — where these two
 *     live — never runs for it.
 *  3. **Every other plugin's tools** — contributed as [McpToolDefinition]s to the
 *     host's [McpToolRegistry]. That is the definition site: the description and
 *     the `inputSchema` are the plugin author's own, and [parseSchema] projects
 *     them exactly as the MCP bridge does, so an agent on the MCP endpoint and the
 *     voice agent cannot be told different things about the same tool.
 *
 * Measured against a live instance: 120 tools on the server, 13 of them BossTerm's,
 * leaving **107 here** (2 host + 105 registry).
 *
 * ### Dynamic
 *
 * [tools] re-reads the registry's `StateFlow` on every call and never caches.
 * BossConsole loads, unloads and hot-reloads plugins while the app runs, so the
 * surface moves under a call; BossTerm re-enumerates before every tool call for
 * exactly this reason. Both reads here are `StateFlow.value` plus a map — no I/O,
 * no locks, no suspension — which is what the 2s enumeration deadline expects.
 */
internal class BossVoiceToolSource(
    private val registryProvider: () -> McpToolRegistry?,
    private val hostTools: List<HostMcpTool> = bossHostMcpToolDefs,
) : VoiceToolSource {

    /**
     * Built once: BossTerm calls `source.policy` on every enumeration and the
     * default getter would allocate a fresh [VoiceToolPolicy] each time.
     *
     * The two rules restate [BossVoiceToolSafety] rather than adding to it. That is
     * on purpose — the same classification already rides on each tool's
     * [ExternalVoiceTool.sensitive] / [ExternalVoiceTool.irreversible] flag, and
     * both routes are checked independently by
     * [VoiceToolPolicy.exclusionReason] / [VoiceToolPolicy.isIrreversible]. So a
     * bug that lost the flags (a mapping regression, a tool arriving from some
     * future third source) still cannot advertise a secret tool ungated. Belt and
     * braces on the one tier where the failure is a credential read out loud.
     */
    override val policy: VoiceToolPolicy = VoiceToolPolicy(
        excludeExtra = { BossVoiceToolSafety.isSecret(it.name) },
        irreversibleExtra = { BossVoiceToolSafety.isIrreversible(it.name) },
        maxExternalTools = MAX_EXTERNAL_TOOLS,
        // approve is deliberately not installed; see the class KDoc.
    )

    override fun tools(): List<ExternalVoiceTool> {
        val fromHost = hostTools.map { it.asVoiceTool() }
        val fromRegistry = registryProvider()?.tools?.value.orEmpty()
            // The same filter the MCP bridge applies (see McpDynamicTools.syncTools):
            // a plugin-contributed tool whose name is already BossTerm's is not on
            // the `boss` server, so it must not be on the voice surface either. The
            // merge's collision rule would drop it too — this keeps the two
            // surfaces identical for the same reason rather than by luck.
            .filterNot { it.definition.name in RESERVED_TOOL_NAMES }
            .map { it.definition.asVoiceTool() }
        // Ceiling order. Excluded tools are kept in the list rather than filtered
        // out: BossTerm records them as refusals, so the agent hears "that is
        // withheld" instead of "unknown tool", and they are dropped before the
        // ceiling is counted so they cost no budget.
        return (fromHost + fromRegistry).sortedWith(
            compareBy({ priorityOf(it.name) }, { it.name }),
        )
    }

    /**
     * Run one of this source's own tools.
     *
     * [name] is this source's name — BossTerm calls back with that, never the
     * advertised one — so routing is a lookup on the same two groups [tools]
     * projects. Runs on `Dispatchers.IO` already (the composite executor puts it
     * there), and the registry's own `invoke` is the guarded path: it answers with
     * an error result rather than throwing for an unknown tool or a failing
     * handler.
     */
    override suspend fun call(name: String, args: JsonObject): String {
        hostTools.firstOrNull { it.name == name }?.let { tool ->
            val result = tool.handler(args)
            val text = result.content.filterIsInstance<TextContent>()
                .joinToString("\n") { it.text.orEmpty() }
            return if (result.isError == true) errorJson(text.ifBlank { "That tool failed." })
            else asJson(text)
        }
        val registry = registryProvider()
            ?: return errorJson("BossConsole's tool registry is not available right now.")
        val result = registry.invoke(name, args.toString())
        return if (result.isError) errorJson(result.text.ifBlank { "That tool failed." })
        else asJson(result.text)
    }

    private fun HostMcpTool.asVoiceTool(): ExternalVoiceTool = ExternalVoiceTool(
        name = name,
        description = description,
        properties = schema.properties ?: JsonObject(emptyMap()),
        required = schema.required.orEmpty(),
        // Both drive the app rather than read it.
        write = true,
        irreversible = BossVoiceToolSafety.isIrreversible(name),
        sensitive = BossVoiceToolSafety.isSecret(name),
    )

    /**
     * A registry tool as the voice agent sees it.
     *
     * Description and schema are the plugin author's, projected through the same
     * [parseSchema] the MCP bridge uses. `write` is the author's `readOnly` hint
     * inverted — BossTerm's own read/write split, and the one thing the definition
     * already answers.
     *
     * [ExternalVoiceTool.sensitive] additionally consults `requiredPermissions`,
     * which is why the permission half of the rule lives here rather than in
     * [policy]: `excludeExtra` is handed an [ExternalVoiceTool], which carries no
     * permissions, and re-finding the definition by name inside a predicate that
     * runs once per tool per enumeration would turn a linear projection into a
     * quadratic one inside a deadline.
     */
    private fun McpToolDefinition.asVoiceTool(): ExternalVoiceTool {
        val schema = parseSchema(inputSchema)
        return ExternalVoiceTool(
            name = name,
            description = description,
            properties = schema.properties ?: JsonObject(emptyMap()),
            required = schema.required.orEmpty(),
            write = !readOnly,
            irreversible = BossVoiceToolSafety.isIrreversible(name),
            sensitive = BossVoiceToolSafety.isSecret(name, requiredPermissions),
        )
    }

    internal companion object {

        /**
         * Ceiling on the advertised external surface, raised from BossTerm's
         * default of 64.
         *
         * Measured on a live instance: 107 tools reach this source, 7 are excluded
         * as secret-bearing, and the remaining 100 render to 30,225 bytes of
         * OpenAI `tools` array (~7.6k tokens) against ~12k for BossTerm's own 13.
         * At BossTerm's default of 64 the array is 20,008 bytes — it saves ~2.6k
         * tokens per turn and costs 36 tools, chosen by this file's priority table
         * rather than by anything the user asked for. For a feature whose whole
         * point is reaching the `boss` surface, that is the wrong side of the
         * trade: the missing third is invisible until the agent says "that tool is
         * not available on this host" for something the user knows they have.
         *
         * 110 leaves headroom for ~10 newly registered tools before the ordering
         * starts deciding, and keeps the total advertised surface (110 + BossTerm's
         * 13 = 123) under the 128-function limit OpenAI documents for function
         * calling — stated explicitly for Chat Completions, and the Realtime
         * `tools` array is the same shape, so treating it as the bound is the
         * conservative reading. Lowering this line back to 64 is the whole change
         * if the token cost matters more than the coverage.
         */
        const val MAX_EXTERNAL_TOOLS = 110

        /**
         * Families in the order the ceiling should keep them, most useful first.
         *
         * Only load-bearing once the surface outgrows [MAX_EXTERNAL_TOOLS] — which
         * it will, since installing a plugin grows it — but it is the difference
         * between dropping the tools nobody asks a voice agent for and dropping
         * whichever tools happened to register last. An entry ending in `_` is a
         * prefix; anything else is an exact name. Unlisted families sort last (and
         * every drop is logged by name by the merge).
         *
         * Ranked by what someone talking to their own dev machine actually asks
         * for: drive the app, then version control, then code, then what is on
         * screen, then infrastructure, then automation, then housekeeping. RBAC is
         * deliberately last — it is admin-console work, the least plausible thing
         * to do by voice, and the most damaging to get wrong from a misheard
         * sentence.
         */
        private val FAMILY_PRIORITY: List<List<String>> = listOf(
            listOf("cli", "run_in_sidebar"),
            listOf("git_"),
            listOf("codebase_", "editor_"),
            listOf("console_"),
            listOf("run_config_"),
            listOf("tab_", "tabs_list", "close_panel", "browser_", "bookmark_", "bookmarks_list"),
            listOf("docker_"),
            listOf("flow_", "rpa_", "llmrpa_"),
            listOf("evolver_"),
            listOf("plugin_", "plugins_list", "prompt_", "performance_"),
            listOf("download_", "downloads_"),
            listOf("role_", "roles_list", "permission_", "permissions_list", "user_", "users_list"),
        )

        /** Rank of [name] in [FAMILY_PRIORITY]; unlisted names sort after every listed one. */
        internal fun priorityOf(name: String): Int {
            FAMILY_PRIORITY.forEachIndexed { rank, patterns ->
                for (pattern in patterns) {
                    val hit = if (pattern.endsWith("_")) name.startsWith(pattern) else name == pattern
                    if (hit) return rank
                }
            }
            return FAMILY_PRIORITY.size
        }

        private val resultJson = Json { ignoreUnknownKeys = true }

        /** `{"error": …}` — the shape the composite executor itself uses for a failure. */
        internal fun errorJson(message: String): String =
            buildJsonObject { put("error", message) }.toString()

        /**
         * [text] as the JSON string the seam requires.
         *
         * Most `boss` tools already return JSON, and that is passed through
         * untouched — re-wrapping it would bury the payload a level deeper for no
         * reason. The ones that return prose get wrapped, because "returns a JSON
         * string" is a contract and a bare sentence is not JSON. Blank becomes
         * `{}`, which is what BossTerm would substitute anyway.
         */
        internal fun asJson(text: String): String {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return "{}"
            val looksStructured = (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"))
            if (looksStructured && runCatching { resultJson.parseToJsonElement(trimmed) }.isSuccess) {
                return trimmed
            }
            return buildJsonObject { put("result", text) }.toString()
        }
    }
}

/**
 * Which `boss` tools must never reach a third-party realtime API, and which must
 * not run twice by accident.
 *
 * One classifier, two consumers: it fills in each [ExternalVoiceTool]'s
 * `sensitive` / `irreversible` flags AND backs [BossVoiceToolSource.policy]'s
 * `excludeExtra` / `irreversibleExtra`. Written once so the two cannot disagree.
 *
 * Everything here **widens** [VoiceToolPolicy]'s own floor and can never narrow
 * it: BossTerm decides on `tool.sensitive || returnsSecretMaterial(name)` before
 * it ever asks this. Names are normalised with [VoiceToolPolicy.segments] — the
 * same function the floor uses — rather than a second spelling of it.
 */
internal object BossVoiceToolSafety {

    /**
     * Tools that hand back credential material, by name.
     *
     * All seven are already caught by BossTerm's floor pattern. They are listed
     * anyway, because the floor is a heuristic over a registry BossTerm has never
     * seen and this file is where the registry is known: if the floor's pattern
     * ever loosens, or one of these is renamed to something the pattern misses,
     * the exclusion has to survive that.
     *
     * `secret_create` and `secret_delete` are here even though neither *returns* a
     * secret. `secret_create` takes the plaintext as an argument, which means the
     * agent has to transcribe a spoken credential through the realtime API to call
     * it — the same exfiltration, running the other way.
     */
    private val SECRET_TOOL_NAMES: Set<String> = setOf(
        "secret_get",
        "secret_search",
        "secret_create",
        "secret_delete",
        "secrets_list",
        "my_secret_get",
        "my_secrets_list",
    )

    /**
     * Segments the floor's own secret pattern does not carry.
     *
     * `env` and `dotenv` are the ones that matter today — a tool that reads an
     * environment or a `.env` file hands over every API key in it and says nothing
     * about secrets in its name. `cookie` is the browser equivalent: a session
     * cookie is a credential. The rest (vault, keystore, private keys, OTP codes)
     * are future-proofing against plugins not written yet. None of them match
     * anything on the 120-tool surface measured today, which is the point — this
     * widens the floor without withholding a capability that exists.
     */
    private val EXTRA_SECRET_SEGMENTS = Regex(
        "(^|_)(vault|keystore|privatekey|private_key|sshkey|ssh_key|cookie|cookies|" +
            "env|envs|envvar|envvars|dotenv|environment|otp|totp|mfa|recovery_code|" +
            "license_key|bearer)(_|\$)",
        RegexOption.IGNORE_CASE,
    )

    /** Permission names that mean "this tool is about credentials" — e.g. `secret.read`. */
    private val SECRET_PERMISSION_WORDS = listOf("secret", "credential", "password", "vault", "keychain")

    /**
     * Destructive tools whose names give nothing away.
     *
     * BossTerm's KDoc names the first three as the class of tool a substring match
     * cannot see. The rest were found by reading all 107 descriptions:
     *
     *  - `rpa_run` / `llmrpa_run` / `flow_run` / `run_config_run` — each sets an
     *    automation loose on the real UI or the shell. What it does is whatever it
     *    was recorded or generated to do, which the confirmation is the only chance
     *    to hear stated out loud first.
     *  - `evolver_evolve` writes a skill file into a plugin's source repo (cloning
     *    it if absent) and launches an AI CLI in it; `evolver_hot_reload` copies a
     *    jar over an installed plugin and live-loads it; `evolver_create_issue`
     *    files a public GitHub issue, which cannot be unfiled.
     *  - `browser_run_js` evaluates arbitrary JavaScript in a signed-in browser
     *    tab. It can submit a form or click a delete button as easily as read a
     *    value, and neither the name nor the argument says which.
     *  - `git_checkout` over a dirty tree, or of a path, throws uncommitted work
     *    away. `git_discard` is caught by the floor; this is the same loss wearing
     *    a name that sounds like navigation.
     *  - `docker_build` builds *and* runs a container, publishing a port.
     *  - `permission_create` / `role_create` / `role_grant_permission` /
     *    `user_role_assign` are privilege changes. Each is technically reversible,
     *    and gating them anyway is the cheap side of the trade: one spoken
     *    confirmation against a misheard sentence granting someone a role.
     *
     * Left ungated on purpose: `docker_stop` and `rpa_stop` (a matching start
     * exists), `git_cherry_pick` (recoverable with a reset), `plugin_enable`,
     * `browser_navigate` / `tab_open_url` (gating navigation would make the agent
     * tedious for no loss), and — the deliberate one — `run_in_sidebar` and
     * `cli`'s `open_terminal`, which run shell commands. BossTerm advertises its
     * own `run_command` to the same agent ungated; gating this plugin's two while
     * that stands would be a confirmation dialog in front of the unlocked door
     * next to it. "Run the tests" is also the single most likely thing anyone says
     * to this agent.
     */
    private val IRREVERSIBLE_TOOL_NAMES: Set<String> = setOf(
        "rpa_run",
        "llmrpa_run",
        "flow_run",
        "run_config_run",
        "evolver_evolve",
        "evolver_hot_reload",
        "evolver_create_issue",
        "browser_run_js",
        "git_checkout",
        "docker_build",
        "permission_create",
        "role_create",
        "role_grant_permission",
        "user_role_assign",
    )

    /**
     * Destructive segments the floor's own pattern lacks.
     *
     * `write` and `overwrite` are the significant omission: `codebase_write` and
     * `editor_write_file` replace a file's entire contents with no undo anywhere in
     * BOSS, and "write" appears in neither BossTerm's list nor anyone's intuition
     * about dangerous-sounding words. `rm` catches `docker_rm` (whose own
     * description says "Destructive"), where the floor only knows `remove`.
     * `close` catches `close_panel` — which documents itself as "kills whatever is
     * running there and cannot be undone" — and `tab_close`. `down` catches
     * `docker_compose_down`; note it does not touch `download_*`, since the
     * pattern is segment-anchored and `download` is one segment.
     */
    private val EXTRA_IRREVERSIBLE_SEGMENTS = Regex(
        "(^|_)(write|overwrite|rm|prune|truncate|evolve|upsert|replace|cancel|" +
            "terminate|abort|close|down|rollback|amend|squash)(_|\$)",
        RegexOption.IGNORE_CASE,
    )

    /** True when [name] (or its RBAC gate) says this tool hands back secret material. */
    fun isSecret(name: String, requiredPermissions: List<String> = emptyList()): Boolean {
        if (name in SECRET_TOOL_NAMES) return true
        val segments = VoiceToolPolicy.segments(name)
        if (EXTRA_SECRET_SEGMENTS.containsMatchIn(segments)) return true
        // The floor checks this too; repeated here so isSecret() is the whole
        // answer for callers that are not going through VoiceToolPolicy.
        if (VoiceToolPolicy.returnsSecretMaterial(name)) return true
        return requiredPermissions.any { permission ->
            val lower = permission.lowercase()
            SECRET_PERMISSION_WORDS.any { lower.contains(it) }
        }
    }

    /** True when [name] destroys something with no undo, including the cases the floor misses. */
    fun isIrreversible(name: String): Boolean {
        if (name in IRREVERSIBLE_TOOL_NAMES) return true
        val segments = VoiceToolPolicy.segments(name)
        if (EXTRA_IRREVERSIBLE_SEGMENTS.containsMatchIn(segments)) return true
        return VoiceToolPolicy.looksIrreversible(name)
    }
}
