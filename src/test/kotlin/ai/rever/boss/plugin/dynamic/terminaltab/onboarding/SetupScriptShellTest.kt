package ai.rever.boss.plugin.dynamic.terminaltab.onboarding

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Setup runs every Unix task as `bash <file>`, so each generated script must be bash. These run
 * the real scripts rather than matching strings: a zsh-only Prezto loop passed every string test
 * and failed the whole step on every machine.
 */
class SetupScriptShellTest {
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val home: File = Files.createTempDirectory("setup-script-home").toFile()

    @AfterTest
    fun cleanup() {
        home.deleteRecursively()
    }

    @Test
    fun `every generated Unix install script parses as bash`() {
        if (isWindows) return
        val failures = mutableListOf<String>()
        for (os in listOf(TargetOs.MAC, TargetOs.LINUX)) {
            for (customization in ShellCustomizationChoice.values()) {
                for (installed in listOf(InstalledTools(zsh = true), everythingInstalled())) {
                    val script = buildInstallCommand(
                        OnboardingSelections(
                            shell = ShellChoice.ZSH,
                            shellCustomization = customization,
                            aiAssistants = emptySet(),
                        ),
                        installed,
                        os,
                        currentShell = "zsh",
                    )
                    val (exit, output) = bash("-n", script)
                    if (exit != 0) failures += "$os $customization installed=${installed != InstalledTools(zsh = true)}: $output"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `every setup task and verifier script parses as bash`() {
        if (isWindows) return
        // The task plan is what setup actually runs: each install command wrapped with the shell
        // environment and prompt activation, plus its verifier.
        val failures = mutableListOf<String>()
        for (os in listOf(TargetOs.MAC, TargetOs.LINUX)) {
            for (shell in listOf(ShellChoice.ZSH, ShellChoice.BASH, ShellChoice.FISH)) {
                for (customization in ShellCustomizationChoice.values()) {
                    val selections = OnboardingSelections(shell = shell, shellCustomization = customization)
                    if (!BossTermSetupController.supportsSelections(selections, os)) continue
                    for (installed in listOf(InstalledTools(), everythingInstalled())) {
                        for (plan in BossTermSetupController.buildTaskPlan(selections, installed, os)) {
                            for ((kind, script) in listOfNotNull("task" to plan.command, plan.verificationCommand?.let { "verifier" to it })) {
                                val (exit, output) = bash("-n", script)
                                if (exit != 0) failures += "$os $shell $customization ${plan.state.id} $kind: $output"
                            }
                        }
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the Prezto step links every runcom except README`() {
        if (isWindows) return
        val runcoms = File(home, ".zprezto/runcoms").apply { mkdirs() }
        for (name in listOf("zlogin", "zlogout", "zpreztorc", "zprofile", "zshenv", "zshrc", "README.md")) {
            File(runcoms, name).writeText("# $name\n")
        }
        val script = buildInstallCommand(
            OnboardingSelections(
                shell = ShellChoice.ZSH,
                shellCustomization = ShellCustomizationChoice.PREZTO,
                installGit = false,
                installGitHubCLI = false,
                aiAssistants = emptySet(),
            ),
            InstalledTools(zsh = true),
            TargetOs.MAC,
            currentShell = "zsh",
        )

        val (exit, output) = bash(script = script, fakeGit = true)

        assertEquals(0, exit, output)
        for (name in listOf("zlogin", "zlogout", "zpreztorc", "zprofile", "zshenv", "zshrc")) {
            val link = File(home, ".$name").toPath()
            assertTrue(Files.isSymbolicLink(link), "~/.$name should link to the runcom:\n$output")
            assertEquals(File(runcoms, name).canonicalPath, link.toRealPath().toString())
        }
        assertFalse(File(home, ".README.md").exists(), "README.md is not a runcom")
    }

    @Test
    fun `Oh My Zsh activation is not added twice to the installer's zshrc`() {
        if (isWindows) return
        // What the Oh My Zsh installer writes: `$ZSH`, not the literal path.
        val zshrc = File(home, ".zshrc").apply {
            writeText("export ZSH=\"\$HOME/.oh-my-zsh\"\nsource \$ZSH/oh-my-zsh.sh\n")
        }

        runActivation(ShellCustomizationChoice.OH_MY_ZSH)

        assertEquals(1, zshrc.readLines().count { "oh-my-zsh.sh" in it }, zshrc.readText())
    }

    @Test
    fun `Prezto activation leaves the symlinked runcom in its checkout alone`() {
        if (isWindows) return
        val runcom = File(home, ".zprezto/runcoms/zshrc").apply {
            parentFile.mkdirs()
            writeText("source \"\${ZDOTDIR:-\$HOME}/.zprezto/init.zsh\"\n")
        }
        Files.createSymbolicLink(File(home, ".zshrc").toPath(), runcom.toPath())
        val before = runcom.readText()

        runActivation(ShellCustomizationChoice.PREZTO)

        assertEquals(before, runcom.readText(), "activation must not write into the Prezto git checkout")
    }

    @Test
    fun `activation is added once to a zshrc that lacks it`() {
        if (isWindows) return
        val zshrc = File(home, ".zshrc").apply { writeText("# empty\n") }

        runActivation(ShellCustomizationChoice.OH_MY_ZSH)
        runActivation(ShellCustomizationChoice.OH_MY_ZSH)

        assertEquals(1, zshrc.readLines().count { "oh-my-zsh.sh" in it }, zshrc.readText())
    }

    private fun runActivation(choice: ShellCustomizationChoice) {
        val snippet = BossTermSetupController.ensurePromptActivationCommand(choice, ShellChoice.ZSH, TargetOs.MAC)
        assertTrue(snippet.isNotBlank())
        val (exit, output) = bash(script = "#!/bin/bash\nset -e\n$snippet")
        assertEquals(0, exit, output)
    }

    private fun everythingInstalled() = InstalledTools(
        zsh = true, bash = true, fish = true,
        starship = true, ohMyZsh = true, prezto = true, ohMyPosh = true,
    )

    /** Runs [script] under bash with HOME set to [home]; [fakeGit] makes `git` a no-op. */
    private fun bash(flag: String? = null, script: String, fakeGit: Boolean = false): Pair<Int, String> {
        val file = File.createTempFile("setup-script-", ".sh").apply { writeText(script) }
        try {
            val command = listOfNotNull("/bin/bash", flag, file.absolutePath)
            val process = ProcessBuilder(command).redirectErrorStream(true).apply {
                environment()["HOME"] = home.absolutePath
                environment().remove("ZDOTDIR")
                if (fakeGit) {
                    val bin = File(home, ".fake-bin").apply { mkdirs() }
                    File(bin, "git").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
                    environment()["PATH"] = "${bin.absolutePath}:${environment()["PATH"]}"
                }
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            return process.waitFor() to output
        } finally {
            file.delete()
        }
    }
}
