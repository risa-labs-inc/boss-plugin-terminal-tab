package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.bossterm.compose.hyperlinks.HyperlinkInfo
import ai.rever.bossterm.compose.hyperlinks.HyperlinkType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a click returns decides whether BossTerm falls back to its own handling, so the answer is
 * the security property: a web page with no window may fall back (the system browser), anything
 * that is not a web page is never handed to BOSS's dispatcher by this plugin, and a file with no
 * window is swallowed rather than given to the OS opener, which can run it.
 */
class TerminalLinkClickTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val productionHostContext = hostCallContext

    @BeforeTest
    fun reset() {
        HostWindows.resetForTest()
        hostCallContext = Dispatchers.Unconfined
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        HostWindows.resetForTest()
        hostCallContext = productionHostContext
    }

    private val calls = mutableListOf<String>()
    private var onCall: (() -> Unit)? = null

    /** Register a window whose split-view operations record what they are asked to open. */
    private fun registerWindow(window: String) {
        val ops = Proxy.newProxyInstance(SplitViewOperations::class.java.classLoader, arrayOf(SplitViewOperations::class.java)) { proxy, method, args ->
            when (method.name) {
                "getSupportsOpenTerminalLink" -> true
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "ops"
                else -> { calls += "${method.name}(${args.orEmpty().joinToString()})"; onCall?.invoke(); null }
            }
        } as SplitViewOperations
        val context = Proxy.newProxyInstance(PluginContext::class.java.classLoader, arrayOf(PluginContext::class.java)) { proxy, method, args ->
            when (method.name) {
                "getWindowId" -> window
                "getSplitViewOperations" -> ops
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "ctx"
                else -> null
            }
        } as PluginContext
        HostWindows.register(context)
    }

    private fun link(url: String, type: HyperlinkType) =
        HyperlinkInfo(
            url = url,
            type = type,
            patternId = "test",
            matchedText = url,
            isFile = type == HyperlinkType.FILE,
            isFolder = false,
            scheme = url.substringBefore(':', ""),
            isBuiltin = true,
        )

    @Test
    fun `a link that is not a web page is left to BossTerm, even with a window to open it in`() {
        registerWindow("w")
        assertTrue(handleTerminalLinkClick(link("https://example.com", HyperlinkType.HTTP), scope, "t", "w"))
        assertEquals(listOf("openTerminalLink(https://example.com, t)"), calls)
        calls.clear()

        assertFalse(handleTerminalLinkClick(link("boss://terminal?command=ls", HyperlinkType.HTTP), scope, "t", "w"))
        assertFalse(handleTerminalLinkClick(link("javascript:alert(1)", HyperlinkType.HTTP), scope, "t", "w"))
        assertTrue(calls.isEmpty(), "nothing may reach the host: $calls")
    }

    @Test
    fun `with no window a web page falls back and a file is swallowed`() {
        assertFalse(handleTerminalLinkClick(link("https://example.com", HyperlinkType.HTTP), scope, "t", "w"))
        assertTrue(handleTerminalLinkClick(link("file:///tmp/run-me.sh", HyperlinkType.FILE), scope, "t", "w"))
    }

    @Test
    fun `a file click carries its source terminal and location through the IO path`() = runBlocking {
        registerWindow("w")
        val file = java.nio.file.Files.createTempFile("terminal-link-", ".kt").toFile()
        try {
            val opened = CompletableDeferred<Unit>()
            onCall = { opened.complete(Unit) }
            assertTrue(handleTerminalLinkClick(
                link("${file.toURI().toASCIIString()}:42:7", HyperlinkType.FILE),
                scope, "file-terminal", "w",
            ))
            withTimeout(5000) { opened.await() }
            assertEquals(listOf("openTerminalLink(file:${file.canonicalPath}:42:7, file-terminal)"), calls)
        } finally {
            file.delete()
        }
    }
}
