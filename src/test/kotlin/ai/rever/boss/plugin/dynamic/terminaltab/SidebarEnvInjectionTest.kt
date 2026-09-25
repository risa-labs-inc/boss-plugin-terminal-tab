package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.dynamic.terminaltab.SidebarEnvInjection.ShellFamily
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
 * The command file is owner-only from the moment it exists and holds exactly the variables and
 * command it was given. The shell reads the values back verbatim, the variables do not outlive the
 * command, the loader line names the file and never a value or the command, and a loader whose
 * file cannot be loaded runs nothing. The shell tests run the real loader under every shell on the
 * machine, fish and PowerShell included when installed (Windows PowerShell 5.1 on the Windows CI
 * job, pwsh on Linux).
 */
class SidebarEnvInjectionTest {
    /** Every character that breaks some shell's quoting: POSIX, fish (`\\`, `\'`, trailing `\`) and PowerShell (curly quotes). */
    private val hostile = "a'b\"c \$HOME `tick` \$(echo PWNED) \\\\double \\back\nline2 \u2019curly\u2018 end\\"

    private fun tempDir(): File = createTempDirectory("env-injection").toFile()

    private fun installed(vararg paths: String) = paths.filter { File(it).canExecute() }

    private val posixShells = installed("/bin/sh", "/bin/bash", "/bin/zsh", "/usr/bin/zsh")
    private val fishShells = installed("/usr/bin/fish", "/opt/homebrew/bin/fish", "/usr/local/bin/fish")

    private val powershell: String? =
        listOf("powershell.exe", "pwsh").firstOrNull { exe ->
            runCatching { ProcessBuilder(exe, "-NoProfile", "-Command", "exit 0").start().waitFor() == 0 }.getOrDefault(false)
        }

    private fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    /** (shell path, family) for every Unix shell present. */
    private fun unixShells(): List<Pair<String, ShellFamily>> =
        posixShells.map { it to ShellFamily.POSIX } + fishShells.map { it to ShellFamily.FISH }

    /** Prints whether TOKEN is still set, after the loader, in [family]'s syntax. */
    private fun afterCheck(family: ShellFamily): String =
        if (family == ShellFamily.FISH) {
            "set -q TOKEN; and echo AFTER=set; or echo AFTER=unset"
        } else {
            "if [ -n \"\${TOKEN+x}\" ]; then echo AFTER=set; else echo AFTER=unset; fi"
        }

    /**
     * The shell tests skip a shell that is not installed, so a green run proves nothing about a
     * missing one. On CI the runners are set up to have them (test.yml installs fish and zsh on
     * Linux; pwsh is preinstalled there, Windows PowerShell on Windows), so a runner that lost one
     * fails here instead of passing silently.
     */
    @Test
    fun `on CI every shell the loader supports is actually exercised`() {
        if (System.getenv("CI") != "true") return
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (os.contains("linux")) {
            assertTrue(fishShells.isNotEmpty(), "fish is missing on the Linux runner")
            assertTrue(posixShells.any { it.endsWith("zsh") }, "zsh is missing on the Linux runner")
            assertNotNull(powershell, "pwsh is missing on the Linux runner")
        }
        if (os.contains("windows")) assertNotNull(powershell, "PowerShell is missing on the Windows runner")
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
    fun `keys that differ only in case are refused, naming keys only`() {
        val error = assertNotNull(SidebarEnvInjection.validationError(mapOf("Path" to "secret-1", "PATH" to "secret-2", "OK" to "x")))
        assertTrue(error.contains("PATH") && error.contains("Path"), error)
        assertFalse(error.contains("secret-"), "a refusal names keys, never values: $error")
    }

    @Test
    fun `unresolved secret references and sensitive names are found by key`() {
        val env = mapOf("A" to "{{secret:abc}}", "B" to "x {{ secret : id }} y", "C" to "plain {secret:x}", "LD_PRELOAD" to "v", "DYLD_INSERT_LIBRARIES" to "v", "Path" to "v")
        assertEquals(listOf("A", "B"), SidebarEnvInjection.unresolvedSecretKeys(env))
        assertEquals(listOf("DYLD_INSERT_LIBRARIES", "LD_PRELOAD", "Path"), SidebarEnvInjection.sensitiveKeys(env))
    }

    @Test
    fun `the shell family comes from the shell's name`() {
        assertEquals(ShellFamily.POSIX, SidebarEnvInjection.shellFamily("/bin/zsh"))
        assertEquals(ShellFamily.POSIX, SidebarEnvInjection.shellFamily("/usr/local/bin/bash"))
        assertEquals(ShellFamily.POSIX, SidebarEnvInjection.shellFamily("/bin/sh"))
        assertEquals(ShellFamily.FISH, SidebarEnvInjection.shellFamily("/opt/homebrew/bin/fish"))
        assertEquals(ShellFamily.POWERSHELL, SidebarEnvInjection.shellFamily("powershell.exe"))
        assertEquals(ShellFamily.POWERSHELL, SidebarEnvInjection.shellFamily("pwsh"))
        assertEquals(ShellFamily.UNSUPPORTED, SidebarEnvInjection.shellFamily("cmd.exe"))
        assertEquals(ShellFamily.UNSUPPORTED, SidebarEnvInjection.shellFamily("/usr/bin/nu"))
        assertEquals(ShellFamily.UNSUPPORTED, SidebarEnvInjection.shellFamily("/bin/tcsh"))
    }

    @Test
    fun `the file and its directories are owner-only on a posix filesystem`() {
        val base = tempDir()
        val dir = File(base, "12345")
        val productionBase = SidebarEnvInjection.envBaseProvider
        SidebarEnvInjection.envBaseProvider = { base }
        val file = try {
            SidebarEnvInjection.writeEnvFile(mapOf("TOKEN" to hostile), "true", dir, ShellFamily.POSIX)
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
    fun `every unix shell gets the value verbatim, for the command alone, from a removed file`() {
        for ((shell, family) in unixShells()) {
            val dir = tempDir()
            val out = File(dir, "out.txt")
            // A pipeline, so it reads the same in sh, bash, zsh and fish.
            val command = "ls '${dir.path}' | grep -q '^env-' && echo STILL_THERE; printf '%s' \"\$TOKEN\" > '${out.path}'"
            val file = SidebarEnvInjection.writeEnvFile(mapOf("TOKEN" to hostile), command, dir, family)
            val loader = SidebarEnvInjection.loaderCommand(file, family)

            val (exit, output) = run(shell, "-c", "$loader; ${afterCheck(family)}")

            assertEquals(0, exit, "$shell: $output")
            assertEquals(hostile, out.readText(), "$shell read the value back differently")
            assertFalse(file.exists(), "$shell: the file is removed")
            assertFalse("STILL_THERE" in output, "$shell: the file was still there while the command ran")
            // The variables must not outlive the approved command in the sidebar shell.
            assertTrue("AFTER=unset" in output, "$shell: TOKEN is still set after the command: $output")
            assertFalse("PWNED" in output, "$shell: part of the value ran as code: $output")
        }
    }

    @Test
    fun `a long command runs through a fixed-length loader that names only the file`() {
        val steps = (1..400).joinToString(" && ") { "echo step-$it" }
        assertTrue(steps.length > 4_000, "the command must be well past the 1024-byte terminal input limit")
        for ((shell, family) in unixShells()) {
            val file = SidebarEnvInjection.writeEnvFile(mapOf("TOKEN" to "t"), steps, tempDir(), family)
            val loader = SidebarEnvInjection.loaderCommand(file, family)

            assertTrue(loader.length < 600, "$shell: loader is ${loader.length} chars: $loader")
            assertFalse("step-1" in loader, "the command belongs in the file, not the typed line: $loader")

            val (exit, output) = run(shell, "-c", loader)
            assertEquals(0, exit, "$shell: $output")
            assertEquals((1..400).map { "step-$it" }, output.trim().lines(), shell)
        }
    }

    @Test
    fun `the command's exit status comes through, and a compound command runs whole`() {
        for ((shell, family) in unixShells()) {
            val file = SidebarEnvInjection.writeEnvFile(mapOf("TOKEN" to "t"), "echo FIRST; echo SECOND; exit 7", tempDir(), family)

            val (exit, output) = run(shell, "-c", SidebarEnvInjection.loaderCommand(file, family))

            assertEquals(7, exit, "$shell: $output")
            assertEquals(listOf("FIRST", "SECOND"), output.trim().lines(), shell)
        }
    }

    @Test
    fun `with its file gone a loader runs nothing and says why`() {
        for ((shell, family) in unixShells()) {
            val missing = File(tempDir(), "env-gone.sh")

            val (exit, output) = run(shell, "-c", SidebarEnvInjection.loaderCommand(missing, family))

            assertTrue(exit != 0, "$shell: a command without its environment must fail: $output")
            assertTrue(SidebarEnvInjection.MISSING_ENV_MESSAGE in output, "$shell should say why: $output")
        }
    }

    @Test
    fun `the posix and fish loaders carry the path and nothing else`() {
        val file = File("/home/user/.boss/run/env/1/env-123.sh")
        val path = file.path // on Windows the JVM renders this with backslashes
        for (family in listOf(ShellFamily.POSIX, ShellFamily.FISH)) {
            val loader = SidebarEnvInjection.loaderCommand(file, family)
            val quoted = if (family == ShellFamily.FISH) SidebarEnvInjection.quoteFish(path) else SidebarEnvInjection.quotePosix(path)
            assertTrue(loader.endsWith(if (family == ShellFamily.FISH) "&& source $quoted" else "&& . $quoted"), loader)
        }
    }

    @Test
    fun `fish quoting escapes backslashes and quotes, posix quoting leaves backslashes alone`() {
        assertEquals("'a\\\\b\\'c\\\\'", SidebarEnvInjection.quoteFish("a\\b'c\\"))
        assertEquals("'a\\b'\\''c\\'", SidebarEnvInjection.quotePosix("a\\b'c\\"))
    }

    @Test
    fun `the powershell file is data, and neither a value nor the command reaches the parser`() {
        val command = "Write-Output 'hi'"
        val data = SidebarEnvInjection.powershellData(mapOf("TOKEN" to hostile, "EMPTY" to ""), command)
        val b64 = { s: String -> Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8)) }
        assertEquals("TOKEN=${b64(hostile)}\nEMPTY=\n:${b64(command)}\n", data)
        val file = File("C:\\Users\\me\\.boss\\run\\env\\1\\env-1.env")
        val loader = SidebarEnvInjection.loaderCommand(file, ShellFamily.POWERSHELL)
        assertFalse(loader.contains(". '"), "the file must never be dot-sourced as a script: $loader")
        assertFalse(loader.contains("Write-Output 'hi'"), "the command belongs in the file: $loader")
        assertTrue(loader.contains("[IO.File]::ReadAllLines('${file.path}')"), loader)
        assertTrue(loader.contains("Invoke-Expression"), loader)
    }

    @Test
    fun `powershell quoting doubles every character powershell reads as a single quote`() {
        assertEquals("'it''s'", SidebarEnvInjection.quotePowershell("it's"))
        assertEquals("'a\u2018\u2018b\u2019\u2019c\u201A\u201Ad\u201B\u201Be'", SidebarEnvInjection.quotePowershell("a\u2018b\u2019c\u201Ad\u201Be"))
    }

    @Test
    fun `real powershell runs the command with a hostile value, then restores the variables`() {
        val exe = powershell ?: return
        // The value comes back as base64 of its UTF-8 bytes: Windows PowerShell 5.1 writes the
        // console in the OEM code page, which would mangle the curly quotes on the way out.
        val command = "Write-Output ('B64=' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(\$env:TOKEN)))"
        val file = SidebarEnvInjection.writeEnvFile(mapOf("TOKEN" to hostile, "KEEP" to "new"), command, tempDir(), ShellFamily.POWERSHELL)
        val loader = SidebarEnvInjection.loaderCommand(file, ShellFamily.POWERSHELL)
        // Restricted blocks every .ps1, which is what broke dot-sourcing an env script.
        val script = "\$env:KEEP = 'old'; $loader; Write-Output ('AFTER_TOKEN=[' + \$env:TOKEN + ']'); Write-Output ('AFTER_KEEP=' + \$env:KEEP); $LEFTOVERS"

        val (exit, output) = run(exe, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Restricted", "-Command", script)

        assertEquals(0, exit, output)
        assertFalse("PWNED" in output, "part of the value ran as code: $output")
        val encoded = assertNotNull(Regex("B64=([A-Za-z0-9+/=]*)").find(output), output).groupValues[1]
        assertEquals(hostile, String(Base64.getDecoder().decode(encoded), Charsets.UTF_8), "the value was altered: $output")
        assertTrue("AFTER_TOKEN=[]" in output, "TOKEN outlived the command: $output")
        assertTrue("AFTER_KEEP=old" in output, "a variable set before must be put back: $output")
        // The loader runs at the prompt, in the session's global scope: none of its working
        // variables (the decoded values among them) may outlive it for a later send_input to read.
        assertTrue("LEFTOVERS=0" in output, "loader variables outlived the command: $output")
        assertFalse(file.exists(), "the file is removed")
    }

    @Test
    fun `real powershell restores a variable given twice in different case`() {
        val exe = powershell ?: return
        // Straight to writeEnvFile, past validationError, which refuses this pair: the loader must
        // still restore the original rather than the value it set a moment before.
        val file = SidebarEnvInjection.writeEnvFile(mapOf("Boss_Case_Var" to "first", "BOSS_CASE_VAR" to "second"), "Write-Output 'RAN'", tempDir(), ShellFamily.POWERSHELL)
        val script = "\$env:BOSS_CASE_VAR = 'original'; ${SidebarEnvInjection.loaderCommand(file, ShellFamily.POWERSHELL)}; " +
            "Write-Output ('AFTER=' + \$env:BOSS_CASE_VAR)"

        val (_, output) = run(exe, "-NoProfile", "-NonInteractive", "-Command", script)

        assertTrue("RAN" in output, output)
        // On Windows the two spellings are one variable; on Linux pwsh they are two, and each is restored.
        assertTrue("AFTER=original" in output, "the injected value stayed in the session: $output")
    }

    @Test
    fun `real powershell reports the command's own error as itself, not as not-run`() {
        val exe = powershell ?: return
        val file = SidebarEnvInjection.writeEnvFile(mapOf("TOKEN" to "t"), "throw 'COMMAND_FAILED'", tempDir(), ShellFamily.POWERSHELL)

        val (_, output) = run(exe, "-NoProfile", "-NonInteractive", "-Command", SidebarEnvInjection.loaderCommand(file, ShellFamily.POWERSHELL))

        assertTrue("COMMAND_FAILED" in output, output)
        assertFalse(
            SidebarEnvInjection.MISSING_ENV_MESSAGE in output.replace(Regex("\\s+"), " "),
            "a failing command is not a missing file: $output",
        )
    }

    @Test
    fun `real powershell runs nothing when the file is gone, and leaves no variables`() {
        val exe = powershell ?: return
        val missing = File(tempDir(), "env-gone.env")
        val script = SidebarEnvInjection.loaderCommand(missing, ShellFamily.POWERSHELL) + "; " + LEFTOVERS

        val (_, output) = run(exe, "-NoProfile", "-NonInteractive", "-Command", script)

        assertTrue(SidebarEnvInjection.MISSING_ENV_MESSAGE in output.replace(Regex("\\s+"), " "), "should say why: $output")
        assertTrue("LEFTOVERS=0" in output, "loader variables outlived it: $output")
    }

    private companion object {
        /** Prints how many of the loader's working variables are still in the session. */
        val LEFTOVERS = "Write-Output ('LEFTOVERS=' + @(Get-Variable -Name " +
            SidebarEnvInjection.POWERSHELL_WORKING_VARIABLES.joinToString(",") +
            " -ErrorAction SilentlyContinue).Count)"
    }
}
