package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.TabSplitMode
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The host operations this plugin needs go through the plugin API, never by reflecting into host
 * classes: since BOSS 9.5.25 the plugin classloader refuses those, which is what left `cli`,
 * `run_in_sidebar` and terminal link clicks dead. Each test here uses fakes of the API interfaces
 * only, the same surface a real host offers.
 */
class HostWindowsTest {
    /** Every [SplitViewOperations] call a fake window received, as "method(args)". */
    private val calls = mutableListOf<String>()

    private val productionWindowLookup = sidebarWindowLookup
    private val productionDispatcher = bossLinkDispatcher
    private val productionHostContext = hostCallContext

    @BeforeTest
    fun reset() {
        HostWindows.resetForTest()
        hostCallContext = kotlinx.coroutines.Dispatchers.Unconfined
    }

    @AfterTest
    fun restore() {
        HostWindows.resetForTest()
        sidebarWindowLookup = productionWindowLookup
        bossLinkDispatcher = productionDispatcher
        hostCallContext = productionHostContext
    }

    private fun operations(window: String): SplitViewOperations =
        Proxy.newProxyInstance(
            SplitViewOperations::class.java.classLoader,
            arrayOf(SplitViewOperations::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "SplitViewOperations($window)"
                else -> {
                    calls += "$window.${method.name}(${args.orEmpty().joinToString()})"
                    if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                }
            }
        } as SplitViewOperations

    private fun context(window: String?): PluginContext {
        val ops = operations(window ?: "unknown")
        return Proxy.newProxyInstance(
            PluginContext::class.java.classLoader,
            arrayOf(PluginContext::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getWindowId" -> window
                "getSplitViewOperations" -> ops
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "PluginContext($window)"
                else -> null
            }
        } as PluginContext
    }

    private fun cli(args: JsonObject): CallToolResult =
        runBlocking { bossHostMcpToolDefs.first { it.name == "cli" }.handler(args) }

    private fun CallToolResult.text() = content.joinToString { (it as? TextContent)?.text.orEmpty() }

    @Test
    fun `a boss link reaches the host dispatcher through openUrlInActivePanel`() {
        HostWindows.register(context("w1"))

        assertTrue(HostWindows.dispatchBossLink("boss://plugin?id=terminal"))
        assertEquals(listOf("w1.openUrlInActivePanel(boss://plugin?id=terminal, boss://plugin?id=terminal, false)"), calls)
    }

    @Test
    fun `with no window registered a boss link is not taken`() {
        assertFalse(HostWindows.dispatchBossLink("boss://plugin?id=terminal"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `the focused window wins, and a window that closed falls back to the newest one`() {
        val first = context("w1")
        HostWindows.register(first)
        HostWindows.register(context("w2"))
        assertEquals("w2", HostWindows.targetWindowId())

        HostWindows.noteFocused("w1")
        assertEquals("w1", HostWindows.targetWindowId())

        HostWindows.unregister(first)
        assertEquals("w2", HostWindows.targetWindowId())
    }

    @Test
    fun `a click is routed to the clicking window, not the focused one`() {
        HostWindows.register(context("w1"))
        HostWindows.register(context("w2"))
        HostWindows.noteFocused("w2")

        HostWindows.splitViewFor("w1")?.openUrlInActivePanel("https://example.com", "x")

        assertEquals(listOf("w1.openUrlInActivePanel(https://example.com, x, false)"), calls)
    }

    @Test
    fun `a context that names no window still serves requests`() {
        HostWindows.register(context(null))
        assertTrue(HostWindows.dispatchBossLink("boss://url?url=x"))
        assertEquals(1, calls.size)
    }

    @Test
    fun `cli dispatches its verbs as boss links`() {
        val dispatched = mutableListOf<String>()
        bossLinkDispatcher = { dispatched += it; true }

        assertEquals(false, cli(buildJsonObject { put("open_panel", true); put("panel_id", "codebase") }).isError)
        assertEquals(false, cli(buildJsonObject { put("split_window", true) }).isError)
        assertEquals(false, cli(buildJsonObject { put("open_url", true); put("url", "https://example.com/a b") }).isError)

        assertEquals(
            listOf(
                "boss://plugin?id=codebase",
                "boss://split?orientation=vertical",
                "boss://url?url=https%3A%2F%2Fexample.com%2Fa+b",
            ),
            dispatched,
        )
    }

    @Test
    fun `cli with no window to take the link says so instead of blaming the dispatcher`() {
        val result = cli(buildJsonObject { put("open_panel", true); put("panel_id", "codebase") })
        assertEquals(true, result.isError)
        assertTrue(result.text().contains("No BossConsole window"), result.text())
    }

    @Test
    fun `open_url with a split opens the page in a new pane of the target window`() {
        HostWindows.register(context("w1"))
        sidebarWindowLookup = { "w1" }

        val result = cli(buildJsonObject {
            put("open_url", true)
            put("url", "https://github.com/risa-labs-inc/BossConsole/pull/1767")
            put("split", "vertical")
        })

        assertEquals(false, result.isError, result.text())
        assertEquals(
            listOf(
                "w1.openUrlInSplit(https://github.com/risa-labs-inc/BossConsole/pull/1767, " +
                    "https://github.com/risa-labs-inc/BossConsole/pull/1767, ${TabSplitMode.VERTICAL_SPLIT})",
            ),
            calls,
        )
    }

    @Test
    fun `open_url with a split refuses anything but a web page, and an unknown split`() {
        HostWindows.register(context("w1"))
        sidebarWindowLookup = { "w1" }
        listOf(
            buildJsonObject { put("open_url", true); put("url", "file:///etc/passwd"); put("split", "vertical") },
            buildJsonObject { put("open_url", true); put("url", "javascript:alert(1)"); put("split", "vertical") },
            buildJsonObject { put("open_url", true); put("url", "boss://terminal?command=ls"); put("split", "vertical") },
            buildJsonObject { put("open_url", true); put("url", "https://example.com"); put("split", "diagonal") },
            buildJsonObject { put("open_url", true); put("split", "vertical") },
        ).forEach { args ->
            assertEquals(true, cli(args).isError, "should refuse $args")
        }
        assertTrue(calls.isEmpty(), "nothing may be opened: $calls")
    }

    @Test
    fun `a window that closed is never the target`() {
        val only = context("w1")
        HostWindows.register(only)
        HostWindows.noteFocused("w1")
        HostWindows.unregister(only)
        assertNull(HostWindows.targetWindowId())
    }

    @Test
    fun `split without open_url is an error, not silently ignored`() {
        val dispatched = mutableListOf<String>()
        bossLinkDispatcher = { dispatched += it; true }
        val result = cli(buildJsonObject { put("open_panel", true); put("panel_id", "codebase"); put("split", "vertical") })
        assertEquals(true, result.isError)
        assertTrue(result.text().contains("split"), result.text())
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `run_in_sidebar uses the tracked window, not a host class`() {
        assertNull(productionWindowLookup())
        HostWindows.register(context("w9"))
        assertEquals("w9", productionWindowLookup())
    }
}
