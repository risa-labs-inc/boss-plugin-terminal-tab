package ai.rever.boss.plugin.dynamic.terminaltab

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An env file holds `run_in_sidebar`'s values - resolved secrets included - in plaintext until a
 * shell loads and removes it. These pin that no path leaves one behind (a start that fails, a
 * start that queues nothing, a command that never runs, a plugin that stops or crashed), that a
 * sweep never takes a live other process's pending file, and that the runner entry and the tool
 * result carry no value.
 */
class SidebarEnvCleanupTest {
    private val base: File = Files.createTempDirectory("sidebar-env").toFile()
    private val productionBase = SidebarEnvInjection.envBaseProvider
    private val productionStarter = sidebarTabStarter
    private val productionWindow = sidebarWindowLookup
    private val productionRunner = sidebarRunnerRegistrar
    private val productionShell = SidebarEnvInjection.sidebarShellProvider
    private var startedCommand: String? = null
    private var runnerCommand: String? = null
    private val secretValue = "s3cret-value"

    @BeforeTest
    fun seams() {
        SidebarEnvInjection.envBaseProvider = { base }
        SidebarEnvInjection.sidebarShellProvider = { "/bin/zsh" }
        sidebarWindowLookup = { "test-window" }
        sidebarRunnerRegistrar = { _, _, command, _, _ ->
            runnerCommand = command
            true
        }
    }

    @AfterTest
    fun restore() {
        SidebarEnvInjection.envBaseProvider = productionBase
        SidebarEnvInjection.sidebarShellProvider = productionShell
        sidebarTabStarter = productionStarter
        sidebarWindowLookup = productionWindow
        sidebarRunnerRegistrar = productionRunner
        base.deleteRecursively()
    }

    private fun envFiles(): List<File> =
        base.walkTopDown().filter { it.isFile && it.name.startsWith("env-") }.toList()

    private fun runInSidebar(): CallToolResult = runBlocking {
        val args: JsonObject = buildJsonObject {
            put("command", "echo \"\$TOKEN\"")
            putJsonObject("env") { put("TOKEN", secretValue) }
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
        assertTrue(startedCommand.orEmpty().contains(SidebarEnvInjection.envDir().path), "the command should have loaded a file")
        assertEquals(emptyList(), envFiles(), "a failed start must not leave the values on disk")
    }

    @Test
    fun `a start that queues nothing deletes the env file`() {
        sidebarTabStarter = { _, command, _, _, _ ->
            startedCommand = command
            false
        }

        val result = runInSidebar()

        assertEquals(emptyList(), envFiles(), "nothing will load the file, so it must go")
        assertEquals(true, result.isError, "an agent must not read a queued-nothing result as the command having run")
    }

    @Test
    fun `a shell without a file format refuses env before anything is written or run`() {
        SidebarEnvInjection.sidebarShellProvider = { "C:\\Windows\\System32\\cmd.exe" }
        sidebarTabStarter = { _, command, _, _, _ ->
            startedCommand = command
            true
        }

        val result = runInSidebar()

        assertEquals(true, result.isError)
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertTrue(text.contains("cmd.exe") && text.contains("Nothing was run"), text)
        assertEquals(null, startedCommand, "nothing may be sent to a shell that cannot parse it")
        assertEquals(emptyList(), envFiles(), "nothing may be written for it either")
    }

    @Test
    fun `a start that queues the command leaves the file for the shell`() {
        sidebarTabStarter = { _, command, _, _, _ ->
            startedCommand = command
            true
        }

        runInSidebar()

        val files = envFiles()
        assertEquals(1, files.size, "the shell still has to load it: $files")
        assertEquals(SidebarEnvInjection.envDir(), files.single().parentFile, "files live in this process's directory")
        assertTrue(startedCommand.orEmpty().contains(files.single().path))
    }

    @Test
    fun `the runner entry fails closed on re-run and neither it nor the result holds a value`() {
        sidebarTabStarter = { _, _, _, _, _ -> true }

        val result = runInSidebar()

        val entry = assertNotNull(runnerCommand)
        // The loader: its file is gone after the first run, so a runner re-run prints why and runs
        // nothing, rather than running the command without its variables.
        assertEquals(startedCommand ?: entry, entry)
        assertTrue(entry.contains(SidebarEnvInjection.envDir().path), entry)
        // The command itself is in the file, so the typed line stays short whatever its length.
        assertFalse(entry.contains("\$TOKEN"), "the command belongs in the file, not the typed line: $entry")
        assertFalse(entry.contains(secretValue), "the runner entry must never hold a value: $entry")
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertFalse(text.contains(secretValue), "the result must never hold a value: $text")
        assertFalse(text.contains(SidebarEnvInjection.envDir().path), "the result carries the caller's command, not the env path: $text")
        assertTrue(text.contains("TOKEN"), "the result names the variables: $text")
    }

    @Test
    fun `a sweep removes stale env files and nothing else`() {
        val dir = File(base, "1")
        val now = System.currentTimeMillis()
        // Stale one written last: writing a file sweeps older stale ones itself (next test).
        val fresh = SidebarEnvInjection.writeEnvFile(mapOf("B" to "2"), "true", dir, SidebarEnvInjection.ShellFamily.POSIX)
        val stale = SidebarEnvInjection.writeEnvFile(mapOf("A" to "1"), "true", dir, SidebarEnvInjection.ShellFamily.POSIX)
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
        val dir = File(base, "1")
        val swallowed = SidebarEnvInjection.writeEnvFile(mapOf("A" to "1"), "true", dir, SidebarEnvInjection.ShellFamily.POSIX)
            .apply { setLastModified(System.currentTimeMillis() - SidebarEnvInjection.STALE_AFTER_MS - 1_000) }

        val next = SidebarEnvInjection.writeEnvFile(mapOf("B" to "2"), "true", dir, SidebarEnvInjection.ShellFamily.POSIX)

        assertFalse(swallowed.exists(), "a stale file must not outlive the next write")
        assertTrue(next.exists())
    }

    @Test
    fun `the lifecycle sweep takes this and dead processes' files but not a live one's`() {
        val self = 100L
        val liveOther = 200L
        val dead = 300L
        val own = SidebarEnvInjection.writeEnvFile(mapOf("A" to "1"), "true", File(base, "$self"), SidebarEnvInjection.ShellFamily.POSIX)
        val pendingElsewhere = SidebarEnvInjection.writeEnvFile(mapOf("B" to "2"), "true", File(base, "$liveOther"), SidebarEnvInjection.ShellFamily.POSIX)
        val crashed = SidebarEnvInjection.writeEnvFile(mapOf("C" to "3"), "true", File(base, "$dead"), SidebarEnvInjection.ShellFamily.POSIX)
        val legacyFlat = File(base, "env-legacy.sh").apply { writeText("export D='4'\n") }
        // A live pid whose file is stale: a dead BOSS whose pid was reused by something else.
        val reusedPid = File(base, "$liveOther").let { SidebarEnvInjection.writeEnvFile(mapOf("E" to "5"), "true", it, SidebarEnvInjection.ShellFamily.POSIX) }
            .apply { setLastModified(System.currentTimeMillis() - SidebarEnvInjection.STALE_AFTER_MS - 1_000) }

        val removed = SidebarEnvInjection.sweepForLifecycle(base, selfPid = self, isAlive = { it == liveOther })

        assertEquals(4, removed)
        assertFalse(reusedPid.exists(), "a stale file goes even when its pid is alive")
        assertFalse(own.exists(), "this process's terminals are gone at start and stop")
        assertFalse(crashed.exists(), "a dead process never ran its stop sweep")
        assertFalse(legacyFlat.exists(), "files from the earlier flat layout go too")
        assertTrue(pendingElsewhere.exists(), "another live BOSS process's shell may still need its file")
    }
}
