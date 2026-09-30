package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.SplitViewOperations
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Terminal link clicks, which used to go to the host's `TerminalLinkEventBus` by reflection and
 * did nothing at all once the plugin classloader stopped resolving host classes (BOSS 9.5.25).
 */
class TerminalLinksTest {
    private val calls = mutableListOf<String>()

    private val operations =
        Proxy.newProxyInstance(
            SplitViewOperations::class.java.classLoader,
            arrayOf(SplitViewOperations::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "SplitViewOperations"
                else -> {
                    calls += "${method.name}(${args.orEmpty().joinToString()})"
                    null
                }
            }
        } as SplitViewOperations

    @Test
    fun `a web link opens in a browser tab`() {
        openTerminalLink(operations, TerminalLinkTarget.Web("https://example.com/x"))
        assertEquals(listOf("openUrlInActivePanel(https://example.com/x, https://example.com/x, false)"), calls)
    }

    @Test
    fun `a file link opens at its line and column, or plainly without one`() {
        openTerminalLink(operations, TerminalLinkTarget.File("/src/Foo.kt", 42, 7))
        openTerminalLink(operations, TerminalLinkTarget.File("/src/Foo.kt", 0, 0))
        assertEquals(
            listOf(
                "openFileAtPosition(/src/Foo.kt, Foo.kt, 42, 7)",
                "openFileInActivePanel(/src/Foo.kt, Foo.kt)",
            ),
            calls,
        )
    }

    @Test
    fun `a file link's line suffix is split off before the file is looked up`() {
        // The old fallback kept "Foo.kt:42" as the path, so every link with a line number failed.
        val looked = mutableListOf<String>()
        val target = resolveFileLink("file:///src/Foo.kt:42:7") { path -> looked += path; path }
        assertEquals(listOf("/src/Foo.kt"), looked)
        assertEquals(TerminalLinkTarget.File("/src/Foo.kt", 42, 7), target)
    }

    @Test
    fun `a file that does not exist is not opened`() {
        assertNull(resolveFileLink("file:///nope/missing.kt:3") { null })
    }

    @Test
    fun `file references parse like the host's`() {
        assertEquals(FileReference("/a/b.kt"), parseFileReference("/a/b.kt"))
        assertEquals(FileReference("/a/b.kt", 12), parseFileReference("/a/b.kt:12"))
        assertEquals(FileReference("/a/b.kt", 12, 3), parseFileReference("/a/b.kt:12:3"))
        assertEquals(FileReference("C:\\src\\b.kt", 12), parseFileReference("C:\\src\\b.kt:12"))
        assertEquals(FileReference("C:\\src\\b.kt"), parseFileReference("C:\\src\\b.kt"))
        assertEquals(FileReference("/a/my file.kt", 5), parseFileReference("/a/my%20file.kt:5"))
        assertEquals(FileReference("/a/v1:beta.kt"), parseFileReference("/a/v1:beta.kt"))
    }

    @Test
    fun `a plus in a path is a plus, not a space`() {
        assertEquals(FileReference("src/c++/main.cpp", 10), parseFileReference("src/c++/main.cpp:10"))
        assertEquals(FileReference("/opt/g++-13/x.h"), parseFileReference("/opt/g++-13/x.h"))
        assertEquals(FileReference("/a/b c+d.kt", 2), parseFileReference("/a/b%20c+d.kt:2"))
    }

    @Test
    fun `a Windows file URL keeps its drive letter`() {
        assertEquals("C:/src/x.kt:3", stripFilePrefix("file:///C:/src/x.kt:3"))
        assertEquals(FileReference("C:/src/x.kt", 3), parseFileReference(stripFilePrefix("file:///C:/src/x.kt:3")))
    }

    @Test
    fun `only web pages are opened as web links`() {
        assertEquals(TerminalLinkTarget.Web("https://example.com"), webLinkTarget("https://example.com"))
        assertEquals(TerminalLinkTarget.Web("http://localhost:8080/x"), webLinkTarget("http://localhost:8080/x"))
        // Anything else a terminal matched as a link is left to BossTerm's own handling.
        assertNull(webLinkTarget("boss://terminal?command=rm%20-rf%20~"))
        assertNull(webLinkTarget("javascript:alert(1)"))
        assertNull(webLinkTarget("ftp://example.com/x"))
        assertNull(webLinkTarget("not a url"))
    }

    @Test
    fun `every file URL form terminals print is stripped to a path`() {
        assertEquals("/a/b", stripFilePrefix("file:///a/b"))
        assertEquals("/a/b", stripFilePrefix("file:/a/b"))
        assertEquals("a/b", stripFilePrefix("file:a/b"))
        assertEquals("/a/b", stripFilePrefix("/a/b"))
    }
}
