package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.compose.voice.ExternalVoiceTool
import ai.rever.bossterm.compose.voice.VoiceToolPolicy
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which `boss` tools may reach a third-party realtime API, and which need an
 * interlock first.
 *
 * The interesting assertions are the negative ones. "`secret_get` is excluded" is
 * true of BossTerm's own floor pattern and proves nothing about this file; what
 * these pin is the gap between the floor and the surface — the tools whose names
 * say nothing about what they do.
 */
class BossVoiceToolSafetyTest {

    private fun tool(name: String) = ExternalVoiceTool(name, "does $name", JsonObject(emptyMap()))

    // ------------------------------------------------- tier 1: secret material

    @Test
    fun `every secret tool on the live surface is withheld`() {
        val withheld = LIVE_EXTERNAL_TOOL_NAMES.filter { BossVoiceToolSafety.isSecret(it) }

        assertEquals(
            listOf(
                "my_secret_get", "my_secrets_list", "secret_create", "secret_delete", "secret_get",
                "secret_search", "secrets_list",
            ),
            withheld.sorted(),
        )
    }

    @Test
    fun `nothing else on the live surface is withheld`() {
        // The safe direction is over-matching, but only up to the point where it
        // quietly removes a capability. This is the check that the widened pattern
        // has not eaten a benign tool: browser_get_url, prompt_get, permissions_list
        // and roles_list all read things that sound credential-adjacent.
        val kept = LIVE_EXTERNAL_TOOL_NAMES.filterNot { BossVoiceToolSafety.isSecret(it) }

        assertEquals(119, kept.size)
        listOf("browser_get_url", "prompt_get", "permissions_list", "roles_list", "codebase_read")
            .forEach { assertTrue(it in kept, "$it should stay available") }
    }

    @Test
    fun `withholds credential-bearing names the floor pattern misses`() {
        // None of these exist today; each is a plugin away. The floor catches
        // "secret"/"token"/"password" and none of the following.
        listOf(
            "env_get", "dotenv_read", "environment_dump", "browser_cookies", "cookie_get",
            "vault_read", "keystore_export", "ssh_key_get", "private_key_show", "totp_code",
            "mfa_enroll", "bearer_for_host",
        ).forEach {
            assertFalse(
                VoiceToolPolicy.returnsSecretMaterial(it),
                "$it is supposed to be a case the floor MISSES; if the floor now catches it " +
                    "this test has stopped testing anything",
            )
            assertTrue(BossVoiceToolSafety.isSecret(it), "$it must be withheld")
        }
    }

    @Test
    fun `withholds on the RBAC permission a tool declares, whatever it is called`() {
        // The data-driven half: `secret.read` is the real permission string the
        // secret-manager plugin's tools carry, so a benignly named future tool that
        // needs it is withheld without anyone editing a name list.
        assertTrue(BossVoiceToolSafety.isSecret("fetch_thing", listOf("secret.read")))
        assertTrue(BossVoiceToolSafety.isSecret("fetch_thing", listOf("vault.admin")))
        assertTrue(BossVoiceToolSafety.isSecret("fetch_thing", listOf("plugin.use", "credential.read")))
        assertFalse(BossVoiceToolSafety.isSecret("fetch_thing", listOf("git.write")))
        assertFalse(BossVoiceToolSafety.isSecret("fetch_thing"))
    }

    @Test
    fun `spelling does not get a secret tool past the check`() {
        listOf("secret-get", "secret.get", "getSecret", "my.secret.get").forEach {
            assertTrue(BossVoiceToolSafety.isSecret(it), "$it must be withheld")
        }
    }

    // ---------------------------------------------------- tier 2: confirmation

    @Test
    fun `gates the destructive tools whose names give nothing away`() {
        // The whole reason this file exists. Each of these is invisible to a
        // substring match over dangerous-sounding words, and each destroys
        // something or sets something loose. Asserted one by one rather than as a
        // set, so a removal names itself.
        listOf(
            // BossTerm's KDoc calls out these three specifically.
            "rpa_run", "llmrpa_run", "evolver_evolve",
            // Overwrite a file's whole contents; "write" is in nobody's danger list.
            "codebase_write", "editor_write_file",
            // Its own description says "Destructive"; the floor only knows `remove`.
            "docker_rm",
            // tab_close is a browser tab. BossTerm owns close_panel (see
            // bossTermOwnToolNames), so the `close` segment earns its place here on
            // tab_close alone — close_panel never reaches this source.
            "tab_close",
            "docker_compose_down", "docker_build",
            "flow_run", "run_config_run",
            "evolver_hot_reload", "evolver_create_issue",
            "browser_run_js", "git_checkout",
            "prompt_upsert", "download_cancel",
            "permission_create", "role_create", "role_grant_permission", "user_role_assign",
            // Mutate a live cluster. k8s_use_context mutates nothing and is gated
            // because it re-aims every later k8s call at a different cluster.
            "k8s_apply", "k8s_scale", "k8s_rollout_restart", "k8s_use_context",
        ).forEach {
            assertFalse(
                VoiceToolPolicy.looksIrreversible(it),
                "$it is supposed to be a case the floor MISSES; if the floor now catches it " +
                    "this test has stopped testing anything",
            )
            assertTrue(BossVoiceToolSafety.isIrreversible(it), "$it must be confirmation-gated")
        }
    }

    @Test
    fun `leaves the reversible and the routine ungated`() {
        // Over-gating costs one spoken confirmation, so the bar is low — but not
        // zero: a gate on every navigation makes the agent unusable, and gating a
        // shell command while BossTerm advertises its own run_command ungated is
        // theatre. Both halves of that are deliberate.
        listOf(
            "cli", "run_in_sidebar",
            "git_status", "git_log", "git_stage", "git_stage_all", "git_unstage", "git_cherry_pick",
            "codebase_read", "codebase_tree", "codebase_open", "editor_read_file",
            "docker_ps", "docker_start", "docker_stop", "docker_restart", "docker_logs",
            "browser_navigate", "tab_open_url", "tab_focus", "tabs_list", "bookmark_add",
            "flow_list", "flow_status", "rpa_status", "rpa_stop", "rpa_record_toggle",
            "plugin_enable", "plugins_list", "prompt_get", "prompt_list",
            "performance_gc", "performance_snapshot", "download_pause", "download_resume",
            "roles_list", "permissions_list", "users_list", "user_search", "role_permissions",
            // Cluster reads, plus the two whose ungating is a judgement: k8s_exec
            // opens a shell for the user to type in, k8s_port_forward has a stop.
            "k8s_get", "k8s_pods", "k8s_logs", "k8s_describe", "k8s_yaml", "k8s_events",
            "k8s_contexts", "k8s_namespaces", "k8s_manifests", "k8s_forwards",
            "k8s_exec", "k8s_port_forward",
        ).forEach {
            assertFalse(BossVoiceToolSafety.isIrreversible(it), "$it should NOT be gated")
        }
    }

    @Test
    fun `download is not mistaken for down`() {
        // `down` is a segment in the widened pattern for docker_compose_down. The
        // pattern is segment-anchored, so `download_*` must be untouched by it —
        // download_cancel is gated for `cancel`, and download_open is not gated.
        assertFalse(BossVoiceToolSafety.isIrreversible("download_open"))
        assertFalse(BossVoiceToolSafety.isIrreversible("downloads_list"))
        assertTrue(BossVoiceToolSafety.isIrreversible("docker_compose_down"))
    }

    @Test
    fun `keeps everything the floor already catches`() {
        // Additive only: nothing here may un-gate a tool BossTerm decided about.
        val floorGated = LIVE_EXTERNAL_TOOL_NAMES.filter { VoiceToolPolicy.looksIrreversible(it) }

        assertTrue(floorGated.isNotEmpty(), "fixture should contain floor-gated tools")
        floorGated.forEach {
            assertTrue(BossVoiceToolSafety.isIrreversible(it), "$it was gated by the floor")
        }
    }

    @Test
    fun `the gated set on the live surface is the one this change claims`() {
        val gated = LIVE_EXTERNAL_TOOL_NAMES
            .filterNot { BossVoiceToolSafety.isSecret(it) }
            .filter { BossVoiceToolSafety.isIrreversible(it) }

        // 25 of these are gated only because of this file; the other 11 the floor
        // already caught. Pinned as a number so widening either rule has to come
        // with a deliberate edit here rather than sliding in.
        assertEquals(36, gated.size, "gated tools on the live surface: $gated")
        assertEquals(
            25,
            gated.count { !VoiceToolPolicy.looksIrreversible(it) },
            "tools gated only by this file's rules",
        )
    }

    // ----------------------------------------------------- widening, not narrowing

    @Test
    fun `the policy rules protect a tool whose own flags were lost`() {
        // The second half of the belt-and-braces claim in BossVoiceToolSource.policy,
        // and the only test that can see it: both rules are normally redundant with
        // the flags this plugin sets on each tool, so neutering them breaks nothing
        // measurable unless the flags are removed too. Here they are — a tool
        // arriving with sensitive/irreversible false and a name only THIS file
        // recognises, which is what a mapping regression or a future third source
        // would look like.
        val policy = BossVoiceToolSource(registryProvider = { null }).policy

        val unflaggedSecret = ExternalVoiceTool(
            name = "dotenv_read",
            description = "reads a .env file",
            properties = JsonObject(emptyMap()),
            sensitive = false,
        )
        assertFalse(
            VoiceToolPolicy.returnsSecretMaterial(unflaggedSecret.name),
            "the floor must MISS this name, or the test proves nothing about excludeExtra",
        )
        assertTrue(policy.isExcluded(unflaggedSecret))

        val unflaggedDestructive = ExternalVoiceTool(
            name = "codebase_write",
            description = "overwrites a file",
            properties = JsonObject(emptyMap()),
            irreversible = false,
        )
        assertFalse(VoiceToolPolicy.looksIrreversible(unflaggedDestructive.name))
        assertTrue(policy.isIrreversible(unflaggedDestructive))
    }

    @Test
    fun `the policy cannot narrow BossTerm's own floor`() {
        val policy = BossVoiceToolSource(registryProvider = { null }).policy

        // A tool the floor considers secret stays excluded even though this file's
        // own rules say nothing about it.
        val floorSecret = tool("api_key_for_host")
        assertTrue(VoiceToolPolicy.returnsSecretMaterial(floorSecret.name))
        assertTrue(policy.isExcluded(floorSecret))

        val floorIrreversible = tool("thing_purge")
        assertTrue(VoiceToolPolicy.looksIrreversible(floorIrreversible.name))
        assertTrue(policy.isIrreversible(floorIrreversible))
    }
}
