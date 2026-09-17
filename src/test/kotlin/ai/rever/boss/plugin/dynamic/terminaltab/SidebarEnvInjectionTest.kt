package ai.rever.boss.plugin.dynamic.terminaltab

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The env file is owner-only from the moment it exists, holds exactly the pairs it was given in
 * a form the shell reads back verbatim, and the command that reaches the shell names the file
 * and never a value.
 */
class SidebarEnvInjectionTest {
    private val secret = "hunter2!\"quoted\" 'single' \$dollar `tick` & spaced/\\slashed"

    private fun tempDir(): File = createTempDirectory("env-injection").toFile()

    @Test
    fun `variable names are validated before anything is written`() {
        assertNull(SidebarEnvInjection.validationError(mapOf("TOKEN" to "x", "_x9" to "y")))
        val error = SidebarEnvInjection.validationError(mapOf("TOKEN" to "x", "bad name" to "y", "9start" to "z"))
        assertNotNull(error)
        assertTrue(error.contains("bad name") && error.contains("9start"), error)
        assertFalse(error.contains("x"), "a refusal names keys, never values")
    }

    @Test
    fun `the file is created owner-only on a posix filesystem`() {
        val dir = tempDir()
        val file = SidebarEnvInjection.writeEnvFile(mapOf("TOKEN" to secret), dir, windows = false)
        assertTrue(file.exists())
        assertTrue(file.name.startsWith("env-") && file.name.endsWith(".sh"))
        if (Files.getFileStore(dir.toPath()).supportsFileAttributeView("posix")) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.toPath()),
            )
        }
    }

    @Test
    fun `the posix script exports each pair and a real shell reads the value back verbatim`() {
        val dir = tempDir()
        val file = SidebarEnvInjection.writeEnvFile(mapOf("TOKEN" to secret, "USER_NAME" to "deploy-bot"), dir, windows = false)
        val script = file.readText()
        assertTrue(script.startsWith("export TOKEN='"), script)
        assertTrue(script.contains("export USER_NAME='deploy-bot'"), script)
        val sh = listOf("/bin/sh", "/usr/bin/sh").map(::File).firstOrNull { it.canExecute() } ?: return
        val probe = File(dir, "probe.sh").apply { writeText("printf '%s' \"\$TOKEN\"\n") }
        val wrapped = SidebarEnvInjection.wrapCommand("sh " + probe.path, file, windows = false)
        val process = ProcessBuilder(sh.path, "-c", wrapped).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        assertEquals(secret, output)
        assertFalse(file.exists(), "the file is removed before the command runs")
    }

    @Test
    fun `the wrapped command carries the file path and never a value`() {
        val file = File("/home/user/.boss/run/env/env-123.sh")
        val wrapped = SidebarEnvInjection.wrapCommand("curl -H \"Authorization: Bearer \$TOKEN\" https://x", file, windows = false)
        assertEquals(
            ". '/home/user/.boss/run/env/env-123.sh' && rm -f '/home/user/.boss/run/env/env-123.sh' && " +
                "curl -H \"Authorization: Bearer \$TOKEN\" https://x",
            wrapped,
        )
    }

    @Test
    fun `the powershell script and wrapper double single quotes`() {
        val script = SidebarEnvInjection.powershellScript(mapOf("TOKEN" to "it's"))
        assertEquals("\$env:TOKEN = 'it''s'\n", script)
        val wrapped = SidebarEnvInjection.wrapCommand("dir", File("C:\\Users\\me\\.boss\\run\\env\\env-1.ps1"), windows = true)
        assertTrue(wrapped.startsWith(". 'C:\\Users\\me\\.boss\\run\\env\\env-1.ps1'; Remove-Item -LiteralPath "), wrapped)
        assertTrue(wrapped.endsWith("-Force; dir"), wrapped)
    }

    @Test
    fun `a value with a newline survives single quoting`() {
        val script = SidebarEnvInjection.posixScript(mapOf("PEM" to "line1\nline2"))
        assertEquals("export PEM='line1\nline2'\n", script)
    }
}
