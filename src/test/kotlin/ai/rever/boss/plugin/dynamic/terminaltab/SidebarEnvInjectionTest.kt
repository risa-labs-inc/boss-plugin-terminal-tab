package ai.rever.boss.plugin.dynamic.terminaltab

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The env file is owner-only from the moment it exists and holds exactly the pairs it was given;
 * the shell reads them back verbatim, the command that reaches the shell names the file and never
 * a value, and a command whose file cannot be loaded does not run. The shell tests run the real
 * wrapper under every shell present on the machine, PowerShell included when it is installed
 * (Windows PowerShell 5.1 on the Windows CI job, pwsh on Linux).
 */
class SidebarEnvInjectionTest {
    private val secret = "hunter2!\"quoted\" 'single' \$dollar `tick` & spaced/\\slashed"

    /** Characters that would break or escape PowerShell quoting if a value were ever parsed. */
    private val hostile = "a\u2019b \u2018c\u201Ad\u201Be ' \$(Write-Output PWNED) `\"x`\" ; exit 3"

    private fun tempDir(): File = createTempDirectory("env-injection").toFile()

    private val posixShells: List<String> =
        listOf("/bin/sh", "/bin/bash", "/bin/zsh", "/usr/bin/zsh", "/usr/bin/fish", "/opt/homebrew/bin/fish", "/usr/local/bin/fish")
            .filter { File(it).canExecute() }

    private val powershell: String? =
        listOf("powershell.exe", "pwsh").firstOrNull { exe ->
            runCatching { ProcessBuilder(exe, "-NoProfile", "-Command", "exit 0").start().waitFor() == 0 }.getOrDefault(false)
        }

    private fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test
    fun `variable names are validated before anything is written`() {
        assertNull(SidebarEnvInjection.validationError(mapOf("TOKEN" to "x", "_x9" to "y")))
        val error = SidebarEnvInjection.validationError(mapOf("TOKEN" to "x", "bad name" to "y", "9start" to "z"))
        assertNotNull(error)
        assertTrue(error.contains("bad name") && error.contains("9start"), error)
        assertFalse(error.contains("x"), "a refusal names keys, never values")
    }

    @Test
    fun `the file and its directories are owner-only on a posix filesystem`() {
        val base = tempDir()
        val dir = File(base, "12345")
        val productionBase = SidebarEnvInjection.envBaseProvider
        SidebarEnvInjection.envBaseProvider = { base }
        val file = try {
            SidebarEnvInjection.writeEnvFile(mapOf("TOKEN" to secret), dir, windows = false)
        } finally {
            SidebarEnvInjection.envBaseProvider = productionBase
        }
        assertTrue(file.exists())
        assertTrue(file.name.startsWith("env-") && file.name.endsWith(".sh"))
        if (Files.getFileStore(base.toPath()).supportsFileAttributeView("posix")) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.toPath()),
            )
            val ownerOnlyDir = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            // Other users cannot even list the file names or watch their timestamps.
            assertEquals(ownerOnlyDir, Files.getPosixFilePermissions(dir.toPath()))
            assertEquals(ownerOnlyDir, Files.getPosixFilePermissions(base.toPath()))
        }
    }

    @Test
    fun `every posix shell reads the value back verbatim and the file is gone before the command`() {
        for (shell in posixShells) {
            val dir = tempDir()
            val file = SidebarEnvInjection.writeEnvFile(mapOf("TOKEN" to secret, "USER_NAME" to "deploy-bot"), dir, windows = false)
            val probe = File(dir, "probe.sh").apply { writeText("[ -e \"\$1\" ] && echo STILL_THERE; printf '%s' \"\$TOKEN\"\n") }
            val wrapped = SidebarEnvInjection.wrapCommand("sh '${probe.path}' '${file.path}'", file, windows = false)

            val (exit, output) = run(shell, "-c", wrapped)

            assertEquals(0, exit, "$shell: $output")
            assertEquals(secret, output, "$shell read the value back differently")
            assertFalse(file.exists(), "$shell: the file is removed")
        }
    }

    @Test
    fun `with its file gone a command does not run, compound commands included`() {
        for (shell in posixShells) {
            val missing = File(tempDir(), "env-gone.sh")
            // Typed bare, `. f && rm -f f && echo FIRST; echo SECOND` would still print SECOND.
            val wrapped = SidebarEnvInjection.wrapCommand("echo FIRST; echo SECOND", missing, windows = false)

            val (exit, output) = run(shell, "-c", wrapped)

            assertTrue(exit != 0, "$shell: a command without its environment must fail: $output")
            assertFalse("FIRST" in output || "SECOND" in output, "$shell ran part of the command: $output")
            assertTrue(SidebarEnvInjection.MISSING_ENV_MESSAGE in output, "$shell should say why: $output")
        }
    }

    @Test
    fun `a compound command runs whole once its file loads`() {
        for (shell in posixShells) {
            val file = SidebarEnvInjection.writeEnvFile(mapOf("TOKEN" to "t"), tempDir(), windows = false)
            val wrapped = SidebarEnvInjection.wrapCommand("echo \"FIRST\$TOKEN\"; echo SECOND", file, windows = false)

            val (exit, output) = run(shell, "-c", wrapped)

            assertEquals(0, exit, "$shell: $output")
            assertEquals(listOf("FIRSTt", "SECOND"), output.trim().lines(), shell)
        }
    }

    @Test
    fun `the posix wrapper carries the file path and never a value`() {
        val file = File("/home/user/.boss/run/env/1/env-123.sh")
        val wrapped = SidebarEnvInjection.wrapCommand("curl -H \"Authorization: Bearer \$TOKEN\" https://x", file, windows = false)
        assertTrue(wrapped.contains(". '/home/user/.boss/run/env/1/env-123.sh' && rm -f '/home/user/.boss/run/env/1/env-123.sh' && eval "), wrapped)
        assertTrue(wrapped.endsWith("eval 'curl -H \"Authorization: Bearer \$TOKEN\" https://x'"), wrapped)
    }

    @Test
    fun `the powershell file is data, and no value reaches the parser`() {
        val data = SidebarEnvInjection.powershellData(mapOf("TOKEN" to hostile, "EMPTY" to ""))
        assertEquals(
            "TOKEN=" + Base64.getEncoder().encodeToString(hostile.toByteArray(Charsets.UTF_8)) + "\nEMPTY=\n",
            data,
        )
        val file = File("C:\\Users\\me\\.boss\\run\\env\\1\\env-1.env")
        val wrapped = SidebarEnvInjection.wrapCommand("dir", file, windows = true)
        assertFalse(wrapped.contains(". '"), "the file must never be dot-sourced as a script: $wrapped")
        assertTrue(wrapped.contains("Get-Content -LiteralPath 'C:\\Users\\me\\.boss\\run\\env\\1\\env-1.env'"), wrapped)
        assertTrue(wrapped.endsWith("if (\$__bossEnvOk) { dir }"), wrapped)
    }

    @Test
    fun `powershell quoting doubles every character powershell reads as a single quote`() {
        assertEquals("'it''s'", SidebarEnvInjection.quotePowershell("it's"))
        assertEquals("'a\u2018\u2018b\u2019\u2019c\u201A\u201Ad\u201B\u201Be'", SidebarEnvInjection.quotePowershell("a\u2018b\u2019c\u201Ad\u201Be"))
    }

    @Test
    fun `real powershell loads a hostile value verbatim under the Restricted policy`() {
        val exe = powershell ?: return
        val dir = tempDir()
        val file = SidebarEnvInjection.writeEnvFile(mapOf("TOKEN" to hostile, "EMPTY" to ""), dir, windows = true)
        // Restricted blocks running any .ps1, which is what broke dot-sourcing the old script.
        val wrapped = SidebarEnvInjection.wrapCommand("Write-Output (\"[\" + \$env:TOKEN + \"]\")", file, windows = true)

        val (exit, output) = run(exe, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Restricted", "-Command", wrapped)

        assertEquals(0, exit, output)
        assertEquals("[$hostile]", output.trim(), "the value was altered or executed")
        assertFalse("PWNED" in output.replace("\$(Write-Output PWNED)", ""), "part of the value ran as code: $output")
        assertFalse(file.exists(), "the file is removed")
    }

    @Test
    fun `real powershell does not run a command whose file is gone`() {
        val exe = powershell ?: return
        val missing = File(tempDir(), "env-gone.env")
        val wrapped = SidebarEnvInjection.wrapCommand("Write-Output FIRST; Write-Output SECOND", missing, windows = true)

        val (_, output) = run(exe, "-NoProfile", "-NonInteractive", "-Command", wrapped)

        assertFalse("FIRST" in output || "SECOND" in output, "ran without its environment: $output")
        assertTrue(SidebarEnvInjection.MISSING_ENV_MESSAGE in output.replace(Regex("\\s+"), " "), "should say why: $output")
    }

    @Test
    fun `a value with a newline survives single quoting`() {
        val script = SidebarEnvInjection.posixScript(mapOf("PEM" to "line1\nline2"))
        assertEquals("export PEM='line1\nline2'\n", script)
    }
}
