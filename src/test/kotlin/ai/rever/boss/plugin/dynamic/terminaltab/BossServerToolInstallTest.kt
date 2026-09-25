package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.compose.mcp.BossTermMcpConfig
import ai.rever.bossterm.compose.mcp.BossTermMcpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which host tools reach an agent, and by which door, in each host shape. Runs the plugin's own
 * [installBossServerTools] against a real BossTerm MCP server, so the branch the plugin takes at
 * runtime is the one under test.
 */
class BossServerToolInstallTest {
    private val productionConfig = TerminalMcpConfigHolder.config
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val setupNames = setupTerminalMcpToolDefs.map { it.name }.toSet()
    private val hostNames = bossHostMcpToolDefs.map { it.name }.toSet()

    @BeforeTest
    fun configure() {
        resumeDynamicPluginTools()
        TerminalMcpConfigHolder.config = BossTermMcpConfig()
    }

    @AfterTest
    fun restore() {
        stopDynamicPluginTools()
        scope.cancel()
        TerminalMcpConfigHolder.config = productionConfig
    }

    private fun serverTools(registry: FakeToolRegistry?, viaRegistry: Boolean): Set<String> =
        BossTermMcpServer(
            config = BossTermMcpConfig(additionalTools = { installBossServerTools(it, registry, viaRegistry, scope) }),
        ).createServer().tools.keys

    @Test
    fun `with host tools in the registry the setup tools stay on the server`() {
        val names = serverTools(FakeToolRegistry(), viaRegistry = true)

        // Debug with Fluck needs these; the registry provider does not carry them.
        assertTrue(names.containsAll(setupNames), "setup tools missing from the server: $names")
        // run_in_sidebar and cli come through the registry bridge instead, not a second copy here.
        assertTrue(names.intersect(hostNames).isEmpty(), "host tools registered twice: $names")
        // Together the two doors cover every host tool exactly once.
        val viaProvider = HostMcpToolProvider().tools().map { it.name }.toSet()
        assertEquals(hostNames, viaProvider)
        assertTrue(viaProvider.intersect(setupNames).isEmpty(), "setup tools must not go through the approval gate")
    }

    @Test
    fun `without the provider every host tool is on the server`() {
        for (registry in listOf(FakeToolRegistry(), null)) {
            val names = serverTools(registry, viaRegistry = false)
            assertTrue(names.containsAll(hostNames + setupNames), "registry=${registry != null}: $names")
        }
    }
}
