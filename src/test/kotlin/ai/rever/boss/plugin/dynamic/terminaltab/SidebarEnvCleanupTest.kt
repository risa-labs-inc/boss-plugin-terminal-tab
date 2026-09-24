package ai.rever.boss.plugin.dynamic.terminaltab

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An env file holds `run_in_sidebar`'s values - resolved secrets included - in plaintext until a
 * shell sources and removes it. These pin that no path leaves one behind: a start that fails, a
 * start that queues nothing, and a command that never runs.
 */
class SidebarEnvCleanupTest {
    private val dir: File = Files.createTempDirectory("sidebar-env").toFile()
    private val productionDir = SidebarEnvInjection.envDirProvider
    private val productionStarter = sidebarTabStarter
    private val productionWindow = sidebarWindowLookup
    private var startedCommand: String? = null

    @BeforeTest
    fun seams() {
        SidebarEnvInjection.envDirProvider = { dir }
        sidebarWindowLookup = { "test-window" }
    }

    @AfterTest
    fun restore() {
        SidebarEnvInjection.envDirProvider = productionDir
        sidebarTabStarter = productionStarter
        sidebarWindowLookup = productionWindow
        dir.deleteRecursively()
    }

    private fun envFiles(): List<File> = dir.listFiles()?.filter { it.name.startsWith("env-") }.orEmpty()

    private fun runInSidebar(): CallToolResult = runBlocking {
        val args: JsonObject = buildJsonObject {
            put("command", "echo \"\$TOKEN\"")
            putJsonObject("env") { put("TOKEN", "s3cret") }
        }
        bossHostMcpToolDefs.single { it.name == "run_in_sidebar" }.handler(args)
    }

    @Test
    fun `a start that throws deletes the env file`() {
        sidebarTabStarter = { _, command, _, _, _ ->
            startedCommand = command
            error("sidebar unavailable")
        }

        val result = runInSidebar()

        assertEquals(true, result.isError)
        assertTrue(startedCommand.orEmpty().contains(dir.path), "the command should have sourced a file in $dir")
        assertEquals(emptyList(), envFiles(), "a failed start must not leave the values on disk")
    }

    @Test
    fun `a start that queues nothing deletes the env file`() {
        sidebarTabStarter = { _, command, _, _, _ ->
            startedCommand = command
            false
        }

        runInSidebar()

        assertEquals(emptyList(), envFiles(), "nothing will source the file, so it must go")
    }

    @Test
    fun `a start that queues the command leaves the file for the shell`() {
        sidebarTabStarter = { _, command, _, _, _ ->
            startedCommand = command
            true
        }

        runInSidebar()

        val files = envFiles()
        assertEquals(1, files.size, "the shell still has to source it: $files")
        assertTrue(startedCommand.orEmpty().contains(files.single().path))
    }

    @Test
    fun `a sweep removes stale env files and nothing else`() {
        val now = System.currentTimeMillis()
        // Stale one written last: writing a file sweeps older stale ones itself (next test).
        val fresh = SidebarEnvInjection.writeEnvFile(mapOf("B" to "2"), dir)
        val stale = SidebarEnvInjection.writeEnvFile(mapOf("A" to "1"), dir)
            .apply { setLastModified(now - SidebarEnvInjection.STALE_AFTER_MS - 1_000) }
        val unrelated = File(dir, "notes.txt").apply { writeText("keep") }

        assertEquals(1, SidebarEnvInjection.sweep(dir, SidebarEnvInjection.STALE_AFTER_MS, now))
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(unrelated.exists())

        assertEquals(1, SidebarEnvInjection.sweep(dir, olderThanMs = null))
        assertFalse(fresh.exists())
        assertTrue(unrelated.exists(), "a sweep only touches its own env files")
    }

    @Test
    fun `writing an env file sweeps one left by a command that never ran`() {
        val swallowed = SidebarEnvInjection.writeEnvFile(mapOf("A" to "1"), dir)
            .apply { setLastModified(System.currentTimeMillis() - SidebarEnvInjection.STALE_AFTER_MS - 1_000) }

        val next = SidebarEnvInjection.writeEnvFile(mapOf("B" to "2"), dir)

        assertFalse(swallowed.exists(), "a stale file must not outlive the next write")
        assertTrue(next.exists())
    }
}
