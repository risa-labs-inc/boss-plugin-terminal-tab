package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.RegisteredMcpTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Doubles for the two things [BossVoiceToolSource] sits between: the host's MCP
 * tool registry, and a tool's declared schema.
 *
 * No MCP server and no live host. Everything worth pinning here — the projection
 * off a definition, the safety classification, the name that comes back through
 * `call`, what an empty or malformed registry does — is reachable without one, and
 * a real server would only make it slower and less deterministic.
 */

/** A `properties` schema string with one string property per name. */
internal fun schemaOf(vararg names: String, required: List<String> = emptyList()): String {
    val props = names.joinToString(",") { """"$it":{"type":"string"}""" }
    val req = required.joinToString(",") { "\"$it\"" }
    return """{"type":"object","properties":{$props},"required":[$req]}"""
}

internal fun toolDef(
    name: String,
    description: String = "does $name",
    inputSchema: String = schemaOf("arg"),
    readOnly: Boolean = true,
    requiredPermissions: List<String> = emptyList(),
    result: McpToolResult = McpToolResult("""{"ok":true}"""),
): McpToolDefinition = McpToolDefinition.withRbac(
    name = name,
    description = description,
    inputSchema = inputSchema,
    readOnly = readOnly,
    requiredPermissions = requiredPermissions,
    handler = McpToolHandler { result },
)

/** The host registry, with a settable tool list so a test can register mid-call. */
internal class FakeToolRegistry(
    initial: List<McpToolDefinition> = emptyList(),
    private val invokeResult: (String, String) -> McpToolResult = { name, _ ->
        McpToolResult("""{"invoked":"$name"}""")
    },
) : McpToolRegistry {

    val invocations = CopyOnWriteArrayList<Pair<String, String>>()

    private val flow = MutableStateFlow(initial.map { RegisteredMcpTool("test", it) })

    fun setTools(defs: List<McpToolDefinition>) {
        flow.value = defs.map { RegisteredMcpTool("test", it) }
    }

    override val tools: StateFlow<List<RegisteredMcpTool>> get() = flow
    override val allTools: StateFlow<List<RegisteredMcpTool>> get() = flow
    override val disabledToolNames: StateFlow<Set<String>> = MutableStateFlow(emptySet())

    override fun setToolEnabled(toolName: String, enabled: Boolean) = Unit

    override suspend fun invoke(toolName: String, arguments: String): McpToolResult {
        invocations += toolName to arguments
        return invokeResult(toolName, arguments)
    }
}

/**
 * Every tool name on the `boss` MCP server, read off a live instance on
 * 2026-07-28 (`tools/list` over the streamable endpoint): **140 tools**, of which
 * 13 are BossTerm's own.
 *
 * Checked in as a fixture because the safety classification is a claim about THIS
 * surface, and a claim about a surface nobody wrote down is untestable.
 *
 * It goes stale, and it did so inside a single afternoon: the first measurement
 * behind this change saw 120 tools, and a Kubernetes plugin arriving mid-work took
 * it to 140. That is the argument for the tests that read it. They assert
 * properties of the RULES — nothing benign withheld, every tool whose description
 * says it destroys something gated, and an ordering that decides the casualties —
 * rather than a headline count, so a name added here either satisfies them or has
 * found a real gap. The one number worth pinning is the ceiling arithmetic, and
 * that is pinned against the limit rather than against this list.
 */
internal val LIVE_BOSS_TOOL_NAMES: List<String> = listOf(
    "bookmark_add", "bookmark_remove", "bookmarks_list", "browser_get_url", "browser_navigate",
    "browser_run_js", "cli", "close_panel", "codebase_open", "codebase_projects", "codebase_read",
    "codebase_select_project", "codebase_tree", "codebase_write", "console_clear", "console_search",
    "console_tail", "docker_build", "docker_compose_down", "docker_compose_ls", "docker_compose_up",
    "docker_images", "docker_logs", "docker_open_service", "docker_project_files", "docker_ps",
    "docker_restart", "docker_rm", "docker_start", "docker_stop", "download_cancel",
    "download_open", "download_pause", "download_resume", "downloads_clear_completed",
    "downloads_list", "editor_detect_language", "editor_read_file", "editor_write_file",
    "evolver_create_issue", "evolver_evolve", "evolver_hot_reload", "evolver_list_tools",
    "evolver_open", "evolver_probe", "flow_add_node", "flow_connect", "flow_create", "flow_get",
    "flow_list", "flow_result", "flow_run", "flow_status", "get_active_tab", "get_last_command",
    "git_checkout", "git_cherry_pick", "git_discard", "git_log", "git_revert", "git_stage",
    "git_stage_all", "git_status", "git_unstage", "git_unstage_all", "k8s_api_resources",
    "k8s_apply", "k8s_contexts", "k8s_delete", "k8s_describe", "k8s_events", "k8s_exec",
    "k8s_forwards", "k8s_get", "k8s_logs", "k8s_manifests", "k8s_namespaces", "k8s_open_resource",
    "k8s_pods", "k8s_port_forward", "k8s_port_forward_stop", "k8s_rollout_restart", "k8s_scale",
    "k8s_use_context", "k8s_yaml", "list_panes", "list_tabs", "llmrpa_run", "llmrpa_status",
    "manage_tools", "my_secret_get", "my_secrets_list", "performance_gc", "performance_network",
    "performance_plugin_memory", "performance_snapshot", "permission_create", "permissions_list",
    "plugin_disable", "plugin_enable", "plugins_list", "prompt_get", "prompt_list", "prompt_upsert",
    "read_debug_console", "read_scrollback", "role_create", "role_delete", "role_grant_permission",
    "role_permissions", "role_revoke_permission", "roles_list", "rpa_record_clear",
    "rpa_record_status", "rpa_record_toggle", "rpa_run", "rpa_status", "rpa_stop", "run_command",
    "run_config_list", "run_config_run", "run_in_panel", "run_in_sidebar", "search_output",
    "secret_create", "secret_delete", "secret_get", "secret_search", "secrets_list", "send_input",
    "send_signal", "show_image", "tab_close", "tab_focus", "tab_open_url", "tabs_list",
    "user_role_assign", "user_role_remove", "user_search", "users_list",
)

/** BossTerm's own thirteen, which its voice executor advertises from its own registry. */
internal val BOSSTERM_OWN_TOOL_NAMES: Set<String> = setOf(
    "list_tabs", "get_active_tab", "list_panes", "read_scrollback", "search_output",
    "get_last_command", "read_debug_console", "send_input", "send_signal", "run_in_panel",
    "run_command", "show_image", "manage_tools",
)

/** The 107 that reach [BossVoiceToolSource]: everything on the server that is not BossTerm's. */
internal val LIVE_EXTERNAL_TOOL_NAMES: List<String> =
    LIVE_BOSS_TOOL_NAMES.filterNot { it in BOSSTERM_OWN_TOOL_NAMES }
