package ai.rever.boss.plugin.dynamic.terminaltab.onboarding

import ai.rever.bossterm.compose.ai.AIAssistantIds
import ai.rever.bossterm.compose.ai.AIAssistants
import ai.rever.bossterm.compose.ai.ResolvedInstall
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the onboarding wizard's generated tool-install script (the in-app "Welcome to BossTerm"
 * flow). [buildInstallCommand] is platform-sensitive (it reads `os.name`), so on each CI OS this
 * exercises that OS's branch with the wizard's default selections (Zsh + Starship + git + gh +
 * the fully-open AI assistants) and nothing pre-installed — the realistic "accept defaults" install.
 *
 * Note that the defaults are all *script*-installed CLIs now, so the npm-hardening guard gets its
 * own scenario that explicitly selects an npm-installed assistant.
 *
 * Catches the class of bugs we've actually shipped: malformed shell (the bash that won't parse),
 * the missing-`/usr/local/bin` Starship abort, and the AI-CLI `npm install -g` EACCES when npm's
 * global prefix is root-owned. The companion CI job (.github/workflows/test-install.yml) actually
 * RUNS the emitted script on Ubuntu; this test is the fast, deterministic, every-PR guard.
 */
class OnboardingInstallScriptTest {

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun defaultScript(): String =
        buildInstallCommand(OnboardingSelections(), InstalledTools())

    @Test
    fun `choosing an already installed shell still changes the default`() {
        val script = buildInstallCommand(
            OnboardingSelections(
                shell = ShellChoice.FISH,
                shellCustomization = ShellCustomizationChoice.KEEP_EXISTING,
                installGit = false,
                installGitHubCLI = false,
                aiAssistants = emptySet(),
            ),
            InstalledTools(fish = true),
            TargetOs.MAC,
            currentShell = "zsh",
        )

        assertTrue(script.contains("sudo chsh -s \$(which fish) \$USER"), script)
    }

    @Test
    fun `fresh macOS shell is installed before it becomes the default`() {
        val script = buildInstallCommand(
            OnboardingSelections(
                shell = ShellChoice.FISH,
                shellCustomization = ShellCustomizationChoice.KEEP_EXISTING,
                installGit = false,
                installGitHubCLI = false,
                aiAssistants = emptySet(),
            ),
            InstalledTools(fish = false),
            TargetOs.MAC,
            currentShell = "zsh",
        )

        val authentication = script.indexOf("sudo -v")
        val install = script.indexOf("brew install fish")
        val makeDefault = script.indexOf("sudo chsh -s \$(which fish) \$USER")
        assertTrue(authentication in 0 until install, script)
        assertTrue(install in 0 until makeDefault, script)
    }

    @Test
    fun `Unix install-plan errors safely quote apostrophes`() {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows, "Executes the Unix error script with a native Unix shell")
        val expected = "Error building installation command: installer isn't available"
        val script = installPlanErrorCommand("installer isn't available", TargetOs.MAC)
        val proc = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText().trim()

        assertEquals(1, proc.waitFor(), script)
        assertEquals(expected, output)
    }

    /** The default script plus Codex, whose registry entry installs via `npm install -g`. */
    private fun scriptWithNpmAssistant(): String =
        buildInstallCommand(
            OnboardingSelections(
                aiAssistants = AIAssistants.DEFAULT_ONBOARDING_SELECTION + AIAssistantIds.CODEX
            ),
            InstalledTools()
        )

    /**
     * The default selection as emitted for WINDOWS, from any host. Windows has winget available in
     * this fixture so the package-manager branch is the realistic one.
     */
    private fun windowsDefaultScript(): String =
        buildInstallCommand(
            OnboardingSelections(),
            InstalledTools(winget = true),
            TargetOs.WINDOWS
        )

    /**
     * Windows can't run the `curl … | bash` installers the open-source CLIs ship, and the Windows
     * branch emits a PowerShell script, so a leaked shell installer would take git/gh/starship down
     * with it. Every entry must resolve to npm or a winget/native command there.
     *
     * This is the case that shipped broken because all the Windows assertions early-returned.
     */
    @Test
    fun `windows install script never emits a shell-script installer`() {
        val script = windowsDefaultScript()
        assertFalse(
            script.contains("| bash") || script.contains("|bash") || script.contains("curl -fsSL"),
            "Windows script must not contain a POSIX shell installer:\n$script"
        )
        assertFalse(
            script.contains("#!/bin/bash"),
            "Windows script should be a PowerShell script, not a bash script:\n$script"
        )
    }

    @Test
    fun `windows install script is compatible with Windows PowerShell 5`() {
        val script = windowsDefaultScript()

        assertTrue(script.startsWith("\$ErrorActionPreference = 'Stop'"), script)
        assertFalse(script.contains("&&"), "Windows PowerShell 5 does not support &&:\n$script")
        assertFalse(script.contains("||"), "Windows PowerShell 5 does not support ||:\n$script")
        assertTrue(script.contains("\$PROFILE.CurrentUserCurrentHost"), script)
        assertFalse(script.contains("Documents\\PowerShell"), "Script must configure the active PowerShell profile:\n$script")
        assertTrue(script.contains("if (-not \$bossStepSucceeded)"), script)
        assertTrue(script.contains("if (\$bossStepExit -ne 0) { exit \$bossStepExit }"), script)
    }

    @Test
    fun `windows native install failure cannot fall through to success`() {
        val script = buildInstallCommand(
            OnboardingSelections(
                shellCustomization = ShellCustomizationChoice.KEEP_EXISTING,
                installGit = true,
                installGitHubCLI = false,
                aiAssistants = emptySet(),
            ),
            InstalledTools(winget = true),
            TargetOs.WINDOWS,
        )

        val install = script.indexOf("winget install Git.Git")
        val exitGuard = script.indexOf("if (\$bossStepExit -ne 0) { exit \$bossStepExit }", install)
        val success = script.indexOf("Installation complete!", install)
        assertTrue(install >= 0, script)
        assertTrue(exitGuard in (install + 1) until success, "winget failure must be checked before success:\n$script")
    }

    @Test
    fun `installed Windows prompt tools still configure the active profile`() {
        val starship = buildInstallCommand(
            OnboardingSelections(
                shellCustomization = ShellCustomizationChoice.STARSHIP,
                installGit = false,
                installGitHubCLI = false,
                aiAssistants = emptySet(),
            ),
            InstalledTools(starship = true, winget = true),
            TargetOs.WINDOWS,
        )
        assertTrue(starship.contains("\$PROFILE.CurrentUserCurrentHost"), starship)
        assertTrue(starship.contains("Invoke-Expression (&starship init powershell)"), starship)
        assertFalse(starship.contains("winget install Starship.Starship"), starship)

        val ohMyPosh = buildInstallCommand(
            OnboardingSelections(
                shellCustomization = ShellCustomizationChoice.OH_MY_POSH,
                installGit = false,
                installGitHubCLI = false,
                aiAssistants = emptySet(),
            ),
            InstalledTools(ohMyPosh = true, winget = true),
            TargetOs.WINDOWS,
        )
        assertTrue(ohMyPosh.contains("\$PROFILE.CurrentUserCurrentHost"), ohMyPosh)
        assertTrue(ohMyPosh.contains("oh-my-posh init pwsh | Invoke-Expression"), ohMyPosh)
        assertFalse(ohMyPosh.contains("winget install JanDeDobbeleer.OhMyPosh"), ohMyPosh)
    }

    @Test
    fun `PowerShell prompt activation is idempotent in a temporary profile`() {
        if (!isWindows) return
        val profile = File.createTempFile("onboarding-profile", ".ps1")
        try {
            profile.writeText("")
            val profileExpression = "'${profile.absolutePath.replace("'", "''")}'"
            val activation = windowsPromptActivationCommand(
                marker = "boss-test-activation",
                activation = "boss-test-activation",
                displayName = "Test prompt",
                profileExpression = profileExpression,
            )
            val script = buildPowerShellInstallScript(listOf(activation, activation))
            val runner = File.createTempFile("onboarding-activation", ".ps1")
            try {
                runner.writeText(script)
                val proc = ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    runner.absolutePath,
                ).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                assertEquals(0, proc.waitFor(), "PowerShell rejected profile activation:\n$out\n---\n$script")
                assertEquals(1, profile.readLines().count { it == "boss-test-activation" }, profile.readText())
            } finally {
                runner.delete()
            }
        } finally {
            profile.delete()
        }
    }

    @Test
    fun `PowerShell runner preserves a failing native exit code`() {
        if (!isWindows) return
        val script = buildPowerShellInstallScript(
            listOf(
                "Write-Host 'before'",
                "cmd.exe /c exit 7",
                "Write-Host 'must-not-run'",
            ),
        )
        val tmp = File.createTempFile("onboarding-install", ".ps1")
        try {
            tmp.writeText(script)
            val proc = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                tmp.absolutePath,
            )
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            assertEquals(7, proc.waitFor(), "PowerShell did not preserve the native failure:\n$out\n---\n$script")
            assertFalse(out.contains("must-not-run"), "PowerShell continued after a failed step:\n$out")
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `windows install script installs the default assistants via npm`() {
        val script = windowsDefaultScript()
        // Every default (fully-open) assistant that has an npm package must appear in the batch;
        // Hermes has none and is reported as unsupported instead.
        val defaults = OnboardingSelections().aiAssistants.mapNotNull { AIAssistants.findById(it) }
        val expectedNpm = defaults.mapNotNull { (it.resolveInstall(isWindows = true) as? ResolvedInstall.Npm)?.packageName }
        assertTrue(expectedNpm.isNotEmpty(), "expected at least one npm-installable default assistant")
        expectedNpm.forEach { pkg ->
            assertTrue(script.contains(pkg), "Windows script is missing npm package $pkg:\n$script")
        }

        defaults.filter { it.resolveInstall(isWindows = true) == null }.forEach { unsupported ->
            assertTrue(
                script.contains(unsupported.displayName),
                "${unsupported.id} has no Windows install path and must be reported, not dropped:\n$script"
            )
        }
    }

    @Test
    fun `claude code still installs from npm on windows`() {
        // It used to be hardcoded to the npm package on every platform. When the registry took over
        // it started emitting its curl installer, which is a Windows regression — pin the fallback.
        val script = buildInstallCommand(
            OnboardingSelections(aiAssistants = setOf(AIAssistantIds.CLAUDE_CODE)),
            InstalledTools(winget = true),
            TargetOs.WINDOWS
        )
        assertTrue(
            script.contains("@anthropic-ai/claude-code"),
            "Claude Code should fall back to its npm package on Windows:\n$script"
        )
    }

    @Test
    fun `default install script is syntactically valid`() {
        val script = defaultScript()
        assertTrue(script.isNotBlank(), "generated install script must not be empty")
        if (isWindows) return // Windows emits a PowerShell script, not bash.

        assertTrue(script.startsWith("#!/bin/bash"), "unix script should start with a shebang:\n$script")
        // `bash -n` parses without executing — the real guard against malformed commands.
        val tmp = File.createTempFile("onboarding-install", ".sh")
        try {
            tmp.writeText(script)
            val proc = ProcessBuilder("bash", "-n", tmp.absolutePath).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            assertEquals(0, proc.waitFor(), "bash -n rejected the generated script:\n$out\n---\n$script")
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `default install script keeps the known-bug guards`() {
        if (isWindows) return
        val script = defaultScript()
        // Starship's installer aborts if /usr/local/bin is missing (Apple Silicon) — must be guarded.
        assertTrue(
            script.contains("[ -d /usr/local/bin ]"),
            "Starship install must guard /usr/local/bin existence:\n$script"
        )
        // Defaults include Starship → sudo is pre-authed so the guarded sudo calls run unattended.
        assertTrue(
            script.contains("Authenticating administrator access"),
            "script should authenticate sudo upfront when it contains sudo steps:\n$script"
        )
        assertFalse(script.contains("sudo -n true"), "setup must not keep sudo credentials alive:\n$script")
        assertFalse(script.contains("SUDO_KEEPALIVE_PID"), "setup must not spawn a sudo keepalive:\n$script")
    }

    /**
     * The Unix script runs under `set -e` and the per-CLI installers land before the npm batch and
     * the PATH post-install step. An unguarded non-zero exit from any one of them therefore skipped
     * starship's PATH fixup and every later step — with no indication of which one failed.
     */
    @Test
    fun `each per-CLI installer is non-fatal and failures are summarised`() {
        if (isWindows) return
        val script = defaultScript()
        val scriptInstallers = OnboardingSelections().aiAssistants
            .mapNotNull { AIAssistants.findById(it) }
            .mapNotNull { it.resolveInstall(isWindows = false) as? ResolvedInstall.Command }

        assertTrue(scriptInstallers.isNotEmpty(), "expected the default selection to use shell installers")
        scriptInstallers.forEach { installer ->
            assertTrue(
                script.contains("{ ${installer.command}; } ||"),
                "installer must be wrapped so set -e can't abort on it: ${installer.command}\n$script"
            )
        }
        assertTrue(
            script.contains("These did not install:"),
            "script should summarise which installers failed:\n$script"
        )
        // The summary is only useful if the variable is initialised before the first installer runs.
        assertTrue(
            script.indexOf("BOSSTERM_FAILED_INSTALLS=\"\"") in 0 until script.indexOf("} ||"),
            "the failure accumulator must be initialised before the first guarded installer:\n$script"
        )
    }

    @Test
    fun `npm-installed assistants keep the EACCES hardening`() {
        if (isWindows) return
        val script = scriptWithNpmAssistant()
        // AI-CLI npm global install must go through the writability-gated $NPM_SUDO with npm
        // resolved to an absolute path ($NPM_BIN) — a bare `npm install -g` EACCES'es on a
        // root-owned prefix, and a bare `sudo npm` can hit command-not-found under env_reset.
        assertTrue(
            script.contains("\$NPM_SUDO \"\$NPM_BIN\" install -g"),
            "AI-CLI npm install must use \$NPM_SUDO + resolved \$NPM_BIN:\n$script"
        )
    }

    @Test
    fun `script-installed assistants use their own installers`() {
        if (isWindows) return
        val script = defaultScript()
        // The single-binary CLIs (Kimi Code, Hermes) are not npm packages — Hermes has no npm
        // package at all — so onboarding must run their installer scripts rather than silently
        // dropping them from the batch, which is what the old npm-only path did.
        AIAssistants.AI_ASSISTANTS
            .filter { it.id in OnboardingSelections().aiAssistants }
            .forEach { assistant ->
                assertTrue(
                    script.contains(assistant.installCommand),
                    "${assistant.id} was selected by default but its install command is missing:\n$script"
                )
            }
    }

    /**
     * Not an assertion — when `BOSSTERM_EMIT_INSTALL_SCRIPT` is set, writes the generated
     * default install script to that path so the CI live-install job can execute it. A no-op
     * (passes) otherwise, so it's harmless in the normal test run.
     *
     * Alongside it, writes `<path>.expected-commands`: the command name of every tool the default
     * selection installs, one per line. The live job asserts each of those resolves afterwards.
     * Emitting the list from the registry rather than hardcoding a CLI in the workflow is the point
     * — the previous version asserted `command -v claude`, which silently became wrong the moment
     * the default selection changed, and the workflow is not somewhere that drift gets noticed.
     */
    @Test
    fun `emit install script for CI when requested`() {
        val target = System.getenv("BOSSTERM_EMIT_INSTALL_SCRIPT") ?: return
        File(target).writeText(defaultScript())
        assertTrue(File(target).length() > 0)

        val selection = OnboardingSelections().aiAssistants
        val commands = (AIAssistants.AI_ASSISTANTS + AIAssistants.LOCAL_MODEL_RUNTIMES)
            .filter { it.id in selection }
            .map { it.command }
        File("$target.expected-commands").writeText(commands.joinToString("\n", postfix = "\n"))
        assertTrue(commands.isNotEmpty(), "default onboarding selection installs no AI tools")
    }
}
