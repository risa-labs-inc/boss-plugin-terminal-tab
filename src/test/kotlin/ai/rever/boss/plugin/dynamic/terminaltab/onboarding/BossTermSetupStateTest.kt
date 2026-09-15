package ai.rever.boss.plugin.dynamic.terminaltab.onboarding

import ai.rever.bossterm.compose.ai.AIAssistants
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossTermSetupStateTest {
    @Test
    fun `progress follows completed tasks and preserves an active repair`() {
        val state = BossTermSetupState(
            sessionId = "setup-1",
            tasks = listOf(
                task("preferences", SetupTaskStatus.COMPLETE),
                task("prompt", SetupTaskStatus.REPAIRING),
                task("git", SetupTaskStatus.PENDING),
                task("github-cli", SetupTaskStatus.PENDING),
            ),
            fluckAvailable = true,
            supervisionChecked = true,
            isBackgrounded = true,
        )

        assertTrue(state.isRunning)
        assertTrue(state.isBackgrounded)
        assertEquals(1, state.completedTaskCount)
        assertEquals(0.25f, state.progress)
        assertEquals("prompt", state.activeTask?.id)
    }

    @Test
    fun `finished failure is no longer running`() {
        val state = BossTermSetupState(
            sessionId = "setup-2",
            tasks = listOf(task("git", SetupTaskStatus.NEEDS_ATTENTION)),
            finished = true,
            failureMessage = "Git needs attention",
        )

        assertFalse(state.isRunning)
        assertEquals(0f, state.progress)
        assertEquals(null, state.activeTask)
    }

    @Test
    fun `zsh exposes both framework customizations while fish does not`() {
        assertTrue(ShellCustomizationChoice.OH_MY_ZSH in availablePromptChoices(ShellChoice.ZSH))
        assertTrue(ShellCustomizationChoice.PREZTO in availablePromptChoices(ShellChoice.ZSH))
        assertFalse(ShellCustomizationChoice.OH_MY_ZSH in availablePromptChoices(ShellChoice.FISH))
        assertFalse(ShellCustomizationChoice.PREZTO in availablePromptChoices(ShellChoice.FISH))
    }

    @Test
    fun `command prompt does not offer PowerShell prompt integrations`() {
        val choices = availablePromptChoices(ShellChoice.CMD, TargetOs.WINDOWS)
        assertFalse(ShellCustomizationChoice.STARSHIP in choices)
        assertFalse(ShellCustomizationChoice.OH_MY_POSH in choices)
    }

    @Test
    fun `compact setup lists every registered onboarding AI tool`() {
        val tools = onboardingAiTools()

        assertTrue(tools.isNotEmpty())
        assertEquals(tools.size, tools.map { it.id }.distinct().size)
    }

    @Test
    fun `setup plans every selected AI assistant and verifies its executable`() {
        val selected = AIAssistants.BUILTIN.take(2)
        val plans = BossTermSetupController.buildTaskPlan(
            OnboardingSelections(
                shell = ShellChoice.KEEP_CURRENT,
                shellCustomization = ShellCustomizationChoice.KEEP_EXISTING,
                installGit = false,
                installGitHubCLI = false,
                aiAssistants = selected.map { it.id }.toSet(),
            ),
            InstalledTools(aiAssistants = selected.associate { it.id to false }),
        )

        val aiPlans = plans.filter { it.state.id.startsWith("ai-") }
        assertEquals(selected.map { "ai-${it.id}" }.toSet(), aiPlans.map { it.state.id }.toSet())
        assertTrue(aiPlans.all { it.verificationCommand != null })
    }

    @Test
    fun `AI install uses the shell selected for this setup`() {
        val assistant = AIAssistants.BUILTIN.first()
        val plan = BossTermSetupController.buildTaskPlan(
            OnboardingSelections(
                packageManager = PackageManagerChoice.NONE,
                shell = ShellChoice.ZSH,
                shellCustomization = ShellCustomizationChoice.KEEP_EXISTING,
                installGit = false,
                installGitHubCLI = false,
                aiAssistants = setOf(assistant.id),
            ),
            InstalledTools(aiAssistants = mapOf(assistant.id to false)),
            TargetOs.LINUX,
        ).first { it.state.id == "ai-${assistant.id}" }

        assertTrue(plan.command.startsWith("export SHELL=\"\$(command -v zsh)\"\n"), plan.command)
    }

    @Test
    fun `empty current shell falls back to a usable platform shell`() {
        assertEquals(
            ShellChoice.ZSH,
            BossTermSetupController.resolveConfiguredShell(ShellChoice.KEEP_CURRENT, TargetOs.LINUX, ""),
        )
        assertEquals(
            ShellChoice.POWERSHELL,
            BossTermSetupController.resolveConfiguredShell(ShellChoice.KEEP_CURRENT, TargetOs.WINDOWS, ""),
        )
    }

    @Test
    fun `verification is platform-specific and blank commands need none`() {
        assertEquals(null, BossTermSetupController.executableVerification("", TargetOs.MAC))
        val unixVerification = BossTermSetupController.executableVerification("git", TargetOs.LINUX)
        val windowsVerification = BossTermSetupController.executableVerification("gh", TargetOs.WINDOWS)
        assertTrue(unixVerification!!.contains("command -v 'git'"))
        assertTrue(windowsVerification!!.contains("Get-Command 'gh'"))
    }

    @Test
    fun `shell verification stops when executable smoke test fails`() {
        val plan = BossTermSetupController.buildTaskPlan(
            OnboardingSelections(
                packageManager = PackageManagerChoice.NONE,
                shell = ShellChoice.FISH,
                shellCustomization = ShellCustomizationChoice.NONE,
                installGit = false,
                installGitHubCLI = false,
                aiAssistants = emptySet(),
            ),
            InstalledTools(fish = true),
            TargetOs.LINUX,
        ).first { it.state.id == "preferences" }

        assertTrue(plan.verificationCommand.orEmpty().startsWith("#!/bin/bash\nset -e\n"))
    }

    @Test
    fun `PowerShell prompt verification checks the active host profile`() {
        val verification = BossTermSetupController.promptVerification(
            ShellCustomizationChoice.OH_MY_POSH,
            ShellChoice.POWERSHELL,
            TargetOs.WINDOWS,
        ).orEmpty()

        assertTrue(verification.contains("-LiteralPath \$PROFILE.CurrentUserCurrentHost"))
        assertFalse(verification.contains("Documents/PowerShell"))
    }

    @Test
    fun `an explicit package manager is a real setup task`() {
        val plans = BossTermSetupController.buildTaskPlan(
            OnboardingSelections(
                packageManager = PackageManagerChoice.HOMEBREW,
                shell = ShellChoice.KEEP_CURRENT,
                shellCustomization = ShellCustomizationChoice.KEEP_EXISTING,
                installGit = false,
                installGitHubCLI = false,
                aiAssistants = emptySet(),
            ),
            InstalledTools(homebrew = false),
        )

        assertEquals("package-manager", plans.first().state.id)
        assertEquals("Install Homebrew", plans.first().state.detail)
        assertTrue(plans.first().verificationCommand.orEmpty().contains("brew"))
    }

    @Test
    fun `verification rejects an executable whose version smoke test fails`() {
        withFakeExecutable(exitCode = 7) { executableDir ->
            val command = requireNotNull(BossTermSetupController.executableVerification("fake-tool", TargetOs.LINUX))
            assertEquals(7, runVerification(command, executableDir))
        }
    }

    @Test
    fun `verification accepts an executable whose version smoke test passes`() {
        withFakeExecutable(exitCode = 0) { executableDir ->
            val command = requireNotNull(BossTermSetupController.executableVerification("fake-tool", TargetOs.LINUX))
            assertEquals(0, runVerification(command, executableDir))
        }
    }

    @Test
    fun `prompt directory without shell activation is rejected`() {
        val fakeHome = Files.createTempDirectory("bossterm-prompt-verification-").toFile()
        try {
            fakeHome.resolve(".oh-my-zsh").mkdirs()
            val verification = requireNotNull(
                BossTermSetupController.promptVerification(
                    ShellCustomizationChoice.OH_MY_ZSH,
                    ShellChoice.ZSH,
                    TargetOs.LINUX,
                ),
            )
            assertTrue(runVerification(verification, home = fakeHome) != 0)
            fakeHome.resolve(".zshrc").writeText("source \$HOME/.oh-my-zsh/oh-my-zsh.sh\n")
            assertEquals(0, runVerification(verification, home = fakeHome))
        } finally {
            fakeHome.deleteRecursively()
        }
    }

    @Test
    fun `prompt activation cannot hide a failing executable smoke test`() {
        val fakeHome = Files.createTempDirectory("bossterm-prompt-home-").toFile()
        val executableDir = Files.createTempDirectory("bossterm-prompt-bin-").toFile()
        try {
            fakeHome.resolve(".zshrc").writeText("eval \"\$(starship init zsh)\"\n")
            executableDir.resolve("starship").apply {
                writeText("#!/bin/bash\nexit 9\n")
                setExecutable(true)
            }
            val verification = requireNotNull(
                BossTermSetupController.promptVerification(
                    ShellCustomizationChoice.STARSHIP,
                    ShellChoice.ZSH,
                    TargetOs.LINUX,
                ),
            )
            assertEquals(9, runVerification(verification, executableDir, fakeHome))
        } finally {
            fakeHome.deleteRecursively()
            executableDir.deleteRecursively()
        }
    }

    @Test
    fun `explicit Chocolatey selection overrides detected Winget for installs`() {
        val plans = BossTermSetupController.buildTaskPlan(
            OnboardingSelections(
                packageManager = PackageManagerChoice.CHOCOLATEY,
                shell = ShellChoice.KEEP_CURRENT,
                shellCustomization = ShellCustomizationChoice.KEEP_EXISTING,
                installGit = true,
                installGitHubCLI = false,
                aiAssistants = emptySet(),
            ),
            InstalledTools(winget = true, chocolatey = true, git = false),
            TargetOs.WINDOWS,
        )

        val gitCommand = plans.single { it.state.id == "git" }.command
        assertTrue("choco install git" in gitCommand)
        assertFalse("winget install Git.Git" in gitCommand)
    }

    private fun withFakeExecutable(exitCode: Int, assertion: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("bossterm-verifier-").toFile()
        try {
            directory.resolve("fake-tool").apply {
                writeText("#!/bin/bash\nexit $exitCode\n")
                setExecutable(true)
            }
            assertion(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `sentinel parser ignores echoed commands and accepts a split exact marker`() {
        val parser = BossTermSetupController.SetupTerminalSentinelParser("abc")
        assertEquals(null, parser.accept("printf '__BOSS_SETUP_abc__:0'\r\n"))
        assertEquals(null, parser.accept("__BOSS_SETUP_abc__"))
        assertEquals(17, parser.accept(":17\r\n"))
    }

    @Test
    fun `snapshot parser does not accumulate prior terminal frames`() {
        val parser = BossTermSetupController.SetupTerminalSentinelParser("frame")
        assertEquals(null, parser.acceptSnapshot("old prompt\npartial"))
        assertEquals(0, parser.acceptSnapshot("old prompt\n__BOSS_SETUP_frame__:0"))
    }

    @Test
    fun `soft wrapped sentinel is rejoined while echoed wrapper remains ignored`() {
        val text = BossTermSetupController.joinTerminalLines(
            listOf(
                "bash wrapper containing __BOSS_SETUP_wrap__:0" to false,
                "__BOSS_SETUP_" to true,
                "wrap__:23" to false,
            ),
        )
        val parser = BossTermSetupController.SetupTerminalSentinelParser("wrap")
        assertEquals(23, parser.acceptSnapshot(text))
    }

    @Test
    fun `success is withheld for failure and pending authentication`() {
        assertTrue(isVerifiedSetupSuccess(BossTermSetupState(sessionId = "ok", finished = true)))
        assertFalse(isVerifiedSetupSuccess(BossTermSetupState(sessionId = "bad", finished = true, failureMessage = "bad")))
        assertFalse(
            isVerifiedSetupSuccess(
                BossTermSetupState(sessionId = "auth", finished = true, awaitingGitHubAuthentication = true),
            ),
        )
    }

    private fun runVerification(
        script: String,
        executableDir: java.io.File? = null,
        home: java.io.File? = null,
    ): Int {
        val scriptFile = Files.createTempFile("bossterm-verification-", ".sh").toFile()
        return try {
            scriptFile.writeText(script)
            ProcessBuilder("/bin/bash", scriptFile.absolutePath).apply {
                executableDir?.let { environment()["PATH"] = "${it.absolutePath}:/usr/bin:/bin" }
                home?.let { environment()["HOME"] = it.absolutePath }
            }.start().waitFor()
        } finally {
            scriptFile.delete()
        }
    }

    private fun task(id: String, status: SetupTaskStatus) = SetupTaskState(
        id = id,
        title = id,
        detail = id,
        status = status,
    )
}
