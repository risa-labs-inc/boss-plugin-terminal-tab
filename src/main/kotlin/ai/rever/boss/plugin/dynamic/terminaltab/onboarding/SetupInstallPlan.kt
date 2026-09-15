package ai.rever.boss.plugin.dynamic.terminaltab.onboarding

import ai.rever.bossterm.compose.ai.AIAssistants
import ai.rever.bossterm.compose.ai.ResolvedInstall
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils

fun buildInstallCommand(
    selections: OnboardingSelections,
    installed: InstalledTools,
    targetOs: TargetOs = TargetOs.current(),
    currentShell: String = System.getenv("SHELL").orEmpty().substringAfterLast('/'),
): String {
    return try {
        buildInstallCommandInternal(selections, installed, targetOs, currentShell)
    } catch (e: Exception) {
        "echo 'Error building installation command: ${e.message?.replace("'", "\\'")}' && exit 1"
    }
}

/** The platform whose install-script branch [buildInstallCommand] should emit. */
enum class TargetOs {
    MAC, WINDOWS, LINUX;

    val isMac: Boolean get() = this == MAC
    val isWindows: Boolean get() = this == WINDOWS

    companion object {
        fun current(): TargetOs {
            val osName = (System.getProperty("os.name") ?: "unknown").lowercase()
            return when {
                osName.contains("mac") -> MAC
                osName.contains("windows") -> WINDOWS
                else -> LINUX
            }
        }
    }
}

/** Shell variable the Unix script accumulates failed per-CLI installer names into. */
private const val FAILED_INSTALLS_VAR = "BOSSTERM_FAILED_INSTALLS"

/**
 * Wrap a single tool's installer so a non-zero exit can't abort the rest of the script.
 *
 * The Unix script runs under `set -e`, and the per-CLI installers are emitted before the npm batch
 * and the PATH post-install step — so without this, one bad installer (a moved URL, a network blip)
 * leaves a half-configured shell with no PATH fixup and no summary of what actually failed. The name
 * is recorded in [FAILED_INSTALLS_VAR] and reported at the end.
 *
 * Windows is left alone: `&&`-joined PowerShell short-circuits rather than aborting on a failed
 * exit code, and every AI entry resolves to npm (batched) or winget there anyway, so no per-CLI
 * script command reaches this path on Windows.
 */
private fun nonFatalInstall(displayName: String, command: String, isWindows: Boolean): String {
    if (isWindows) return command
    // Single-quoted for the shell; a display name with an apostrophe would otherwise break the line.
    val safeName = displayName.replace("'", "")
    return "{ $command; } || $FAILED_INSTALLS_VAR=\"\$$FAILED_INSTALLS_VAR '$safeName'\""
}

private fun buildInstallCommandInternal(
    selections: OnboardingSelections,
    installed: InstalledTools,
    targetOs: TargetOs,
    currentShell: String,
): String {
    val sudoCommands = mutableListOf<String>()
    val userCommands = mutableListOf<String>()
    val postInstallCommands = mutableListOf<String>()

    val isMac = targetOs.isMac
    val isWindows = targetOs.isWindows

    // Helper to get Linux install command
    fun getLinuxInstall(pkg: String): String {
        return "{ command -v apt >/dev/null 2>&1 && sudo apt install -y $pkg; } || " +
               "{ command -v dnf >/dev/null 2>&1 && sudo dnf install -y $pkg; } || " +
               "{ command -v pacman >/dev/null 2>&1 && sudo pacman -S --noconfirm $pkg; }"
    }

    // Helper to get Windows install command with winget/chocolatey fallback
    // wingetId: winget package ID, chocoName: chocolatey package name
    fun getWindowsInstall(wingetId: String, chocoName: String): String {
        return when {
            installed.winget -> "winget install $wingetId --accept-source-agreements --accept-package-agreements"
            installed.chocolatey -> "choco install $chocoName -y"
            else -> "echo 'No package manager available. Please install winget or Chocolatey first.' && exit 1"
        }
    }

    // Helper for Windows uninstall with winget/chocolatey fallback
    fun getWindowsUninstall(wingetId: String, chocoName: String): String {
        return when {
            installed.winget -> "winget uninstall $wingetId --silent"
            installed.chocolatey -> "choco uninstall $chocoName -y"
            else -> "echo 'Skipping uninstall - no package manager available'"
        }
    }

    // Shell installation and set as default
    if (selections.shell != ShellChoice.KEEP_CURRENT) {
        val shellInstalled = when (selections.shell) {
            ShellChoice.ZSH -> installed.zsh
            ShellChoice.BASH -> installed.bash
            ShellChoice.FISH -> installed.fish
            ShellChoice.POWERSHELL -> installed.powershell
            ShellChoice.CMD -> installed.cmd
            ShellChoice.KEEP_CURRENT -> true
        }
        val shellCmd = selections.shell.command
        if (!shellInstalled && !isWindows) {
            // Only install shells on Unix (Windows shells are built-in)
            when {
                isMac -> userCommands.add("brew install $shellCmd")
                else -> sudoCommands.add(getLinuxInstall(shellCmd))
            }
        }
        // Set the selected shell as default even when it was already installed. The old
        // `!shellInstalled` condition made choosing an existing Bash/Fish entry a silent no-op.
        if (!isWindows && currentShell != shellCmd) {
            sudoCommands.add("sudo chsh -s \$(which $shellCmd) \$USER && echo '✓ Default shell changed to $shellCmd'")
        }
    }

    // Shell customization (with conflict removal)
    // Use shared uninstall commands from ShellCustomizationUtils (Unix only)
    val uninstallOhMyZsh = ShellCustomizationUtils.getOhMyZshUninstallCommand()
    val uninstallPrezto = ShellCustomizationUtils.getPreztoUninstallCommand()
    val uninstallStarship = ShellCustomizationUtils.getStarshipUninstallCommand()

    // Handle NONE option - uninstall all existing customizations
    if (selections.shellCustomization == ShellCustomizationChoice.NONE) {
        if (installed.starship) {
            if (isWindows) {
                userCommands.add(getWindowsUninstall("Starship.Starship", "starship"))
            } else {
                userCommands.add(uninstallStarship)
            }
        }
        if (installed.ohMyZsh && !isWindows) {
            userCommands.add(uninstallOhMyZsh)
        }
        if (installed.prezto && !isWindows) {
            userCommands.add(uninstallPrezto)
        }
        if (installed.ohMyPosh && isWindows) {
            userCommands.add(getWindowsUninstall("JanDeDobbeleer.OhMyPosh", "oh-my-posh"))
        }
    } else if (selections.shellCustomization != ShellCustomizationChoice.KEEP_EXISTING) {
        val customInstalled = when (selections.shellCustomization) {
            ShellCustomizationChoice.STARSHIP -> installed.starship
            ShellCustomizationChoice.OH_MY_ZSH -> installed.ohMyZsh
            ShellCustomizationChoice.PREZTO -> installed.prezto
            ShellCustomizationChoice.OH_MY_POSH -> installed.ohMyPosh
            else -> true
        }

        if (!customInstalled) {
            when (selections.shellCustomization) {
                ShellCustomizationChoice.STARSHIP -> {
                    if (isWindows) {
                        // Windows: Install Starship and configure PowerShell profile
                        if (installed.ohMyPosh) {
                            userCommands.add(getWindowsUninstall("JanDeDobbeleer.OhMyPosh", "oh-my-posh"))
                        }
                        userCommands.add(getWindowsInstall("Starship.Starship", "starship"))
                        // Configure PowerShell profile
                        postInstallCommands.add(
                            "powershell -Command \"" +
                            "\$profilePath = \\\"\$env:USERPROFILE\\\\Documents\\\\PowerShell\\\\Microsoft.PowerShell_profile.ps1\\\"; " +
                            "if (!(Test-Path (Split-Path \\\$profilePath))) { New-Item -ItemType Directory -Path (Split-Path \\\$profilePath) -Force | Out-Null }; " +
                            "if (!(Test-Path \\\$profilePath)) { New-Item -ItemType File -Path \\\$profilePath -Force | Out-Null }; " +
                            "if (!(Select-String -Path \\\$profilePath -Pattern 'starship init' -Quiet -ErrorAction SilentlyContinue)) { " +
                            "Add-Content -Path \\\$profilePath -Value 'Invoke-Expression (&starship init powershell)' }; " +
                            "Write-Host 'Starship configured for PowerShell'\""
                        )
                    } else {
                        // Unix: Uninstall Oh My Zsh and Prezto first (they conflict with Starship on Zsh)
                        if (installed.ohMyZsh) {
                            userCommands.add(uninstallOhMyZsh)
                        }
                        if (installed.prezto) {
                            userCommands.add(uninstallPrezto)
                        }
                        // Starship install script + shell config + PATH setup.
                        // Ensure /usr/local/bin exists first — Starship's installer defaults
                        // its bin-dir there and aborts if it's missing, which it is by default
                        // on Apple Silicon Macs (Homebrew uses /opt/homebrew). sudo is already
                        // authenticated upfront (needsSudo includes starship), so the mkdir is
                        // non-interactive; the guard skips it when the dir already exists.
                        userCommands.add("{ [ -d /usr/local/bin ] || sudo mkdir -p /usr/local/bin; } && curl -sS https://starship.rs/install.sh | sh -s -- -y")
                        postInstallCommands.add(
                            "SHELL_NAME=\$(basename \"\$SHELL\") && " +
                            "if [ \"\$SHELL_NAME\" = \"zsh\" ]; then " +
                            "  grep -q '/usr/local/bin' ~/.zshrc 2>/dev/null || grep -q '/usr/local/bin' ~/.zprofile 2>/dev/null || echo 'export PATH=\"/usr/local/bin:\$PATH\"' >> ~/.zprofile; " +
                            "  grep -q 'starship init zsh' ~/.zshrc 2>/dev/null || echo 'eval \"\$(starship init zsh)\"' >> ~/.zshrc; " +
                            "elif [ \"\$SHELL_NAME\" = \"bash\" ]; then " +
                            "  grep -q '/usr/local/bin' ~/.bashrc 2>/dev/null || grep -q '/usr/local/bin' ~/.bash_profile 2>/dev/null || echo 'export PATH=\"/usr/local/bin:\$PATH\"' >> ~/.bash_profile; " +
                            "  grep -q 'starship init bash' ~/.bashrc 2>/dev/null || echo 'eval \"\$(starship init bash)\"' >> ~/.bashrc; " +
                            "elif [ \"\$SHELL_NAME\" = \"fish\" ]; then " +
                            "  fish -c 'contains /usr/local/bin \$fish_user_paths' 2>/dev/null || fish -c 'set -U fish_user_paths /usr/local/bin \$fish_user_paths' 2>/dev/null; " +
                            "  mkdir -p ~/.config/fish && grep -q 'starship init fish' ~/.config/fish/config.fish 2>/dev/null || echo 'starship init fish | source' >> ~/.config/fish/config.fish; " +
                            "fi && echo '✓ Starship installed and PATH configured'"
                        )
                    }
                }
                ShellCustomizationChoice.OH_MY_POSH -> {
                    // Windows only: Install Oh My Posh
                    if (installed.starship) {
                        userCommands.add(getWindowsUninstall("Starship.Starship", "starship"))
                    }
                    userCommands.add(getWindowsInstall("JanDeDobbeleer.OhMyPosh", "oh-my-posh"))
                    // Configure PowerShell profile
                    postInstallCommands.add(
                        "powershell -Command \"" +
                        "\$profilePath = \\\"\$env:USERPROFILE\\\\Documents\\\\PowerShell\\\\Microsoft.PowerShell_profile.ps1\\\"; " +
                        "if (!(Test-Path (Split-Path \\\$profilePath))) { New-Item -ItemType Directory -Path (Split-Path \\\$profilePath) -Force | Out-Null }; " +
                        "if (!(Test-Path \\\$profilePath)) { New-Item -ItemType File -Path \\\$profilePath -Force | Out-Null }; " +
                        "if (!(Select-String -Path \\\$profilePath -Pattern 'oh-my-posh' -Quiet -ErrorAction SilentlyContinue)) { " +
                        "Add-Content -Path \\\$profilePath -Value 'oh-my-posh init pwsh | Invoke-Expression' }; " +
                        "Write-Host 'Oh My Posh configured for PowerShell'\""
                    )
                }
                ShellCustomizationChoice.OH_MY_ZSH -> {
                    // Unix only: Uninstall Prezto and Starship first
                    if (installed.prezto) {
                        userCommands.add(uninstallPrezto)
                    }
                    if (installed.starship) {
                        userCommands.add(uninstallStarship)
                    }
                    userCommands.add("sh -c \"\$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)\" \"\" --unattended")
                }
                ShellCustomizationChoice.PREZTO -> {
                    // Unix only: Uninstall Oh My Zsh and Starship first
                    if (installed.ohMyZsh) {
                        userCommands.add(uninstallOhMyZsh)
                    }
                    if (installed.starship) {
                        userCommands.add(uninstallStarship)
                    }
                    userCommands.add(
                        "git clone --recursive https://github.com/sorin-ionescu/prezto.git \"\${ZDOTDIR:-\$HOME}/.zprezto\" && " +
                        "setopt EXTENDED_GLOB 2>/dev/null; " +
                        "for rcfile in \"\${ZDOTDIR:-\$HOME}\"/.zprezto/runcoms/^README.md(.N); do " +
                        "  ln -sf \"\$rcfile\" \"\${ZDOTDIR:-\$HOME}/.\${rcfile:t}\" 2>/dev/null; " +
                        "done && echo '✓ Prezto installed'"
                    )
                }
                else -> {}
            }
        }
    }

    // Git tools
    if (selections.installGit && !installed.git) {
        when {
            isMac -> userCommands.add("brew install git")
            isWindows -> userCommands.add(getWindowsInstall("Git.Git", "git"))
            else -> sudoCommands.add(getLinuxInstall("git"))
        }
    }
    if (selections.installGitHubCLI && !installed.gh) {
        when {
            isMac -> userCommands.add("brew install gh")
            isWindows -> userCommands.add(getWindowsInstall("GitHub.cli", "gh"))
            else -> sudoCommands.add(getLinuxInstall("gh"))
        }
    }

    // AI Assistants. The install method is declared by the registry ([InstallKind]) rather than
    // sniffed off the command string, and `resolveInstall` applies the Windows fallback: a
    // `curl … | bash` installer cannot run in the PowerShell chain this function emits on Windows,
    // so those entries install from npm there instead.
    val missingAi = selections.aiAssistants
        .filterNot { installed.isAiInstalled(it) }
        .mapNotNull { AIAssistants.findById(it) }
        .map { it to it.resolveInstall(isWindows) }

    // npm-installable ones batch into a single `npm install -g` so the node check, the ENOTEMPTY
    // cleanup, and the sudo decision happen once for all of them.
    val aiToInstall = missingAi.mapNotNull { (assistant, resolved) ->
        (resolved as? ResolvedInstall.Npm)?.let { assistant.id to it.packageName }
    }

    // Everything else runs its own installer. Each one is wrapped so a single failing installer
    // can't abort the script: the whole thing runs under `set -e`, and these land BEFORE the npm
    // batch and the PATH post-install step, so an unguarded non-zero exit from one CLI used to take
    // starship's PATH fixup and every later step down with it.
    missingAi.forEach { (assistant, resolved) ->
        if (resolved is ResolvedInstall.Command) {
            userCommands.add(nonFatalInstall(assistant.displayName, resolved.command, isWindows))
        }
    }

    // A CLI with no install path on this platform (Hermes on native Windows: shell-script installer,
    // no npm package) is reported rather than silently dropped from the selection.
    missingAi.filter { it.second == null }.forEach { (assistant, _) ->
        val message = "${assistant.displayName} has no automatic installer for this platform - " +
            "see ${assistant.websiteUrl}"
        userCommands.add(if (isWindows) "Write-Host '$message'" else "echo '$message'")
    }

    if (aiToInstall.isNotEmpty()) {
        val npmPackages = aiToInstall.joinToString(" ") { it.second }
        // Build cleanup command to remove any corrupted/partial npm installations (fixes ENOTEMPTY error)
        val packagesToClean = aiToInstall.map { it.second }
        // $NPM_SUDO (set per-branch below) elevates only when npm's global dir isn't
        // user-writable, so cleanup + install match the install's privilege level.
        val unixCleanup = packagesToClean.joinToString("; ") { pkg ->
            "\$NPM_SUDO rm -rf \"\$(npm prefix -g 2>/dev/null)/lib/node_modules/$pkg\" 2>/dev/null || true"
        }
        val nodeCheckAndInstall = when {
            isMac -> {
                "{ command -v npm >/dev/null 2>&1 || { echo 'Installing Node.js via Homebrew...' && brew install node; }; } && " +
                // npm's global modules dir is root-owned when Node was installed via the
                // official .pkg (prefix /usr/local) rather than Homebrew — `npm install -g`
                // then EACCES'es. Use sudo ONLY when it isn't user-writable (creds are
                // pre-authed; see needsSudo below). Homebrew prefixes stay sudo-free.
                "NPM_MODULES=\"\$(npm prefix -g 2>/dev/null)/lib/node_modules\" && " +
                "if [ -w \"\$NPM_MODULES\" ] || { [ ! -e \"\$NPM_MODULES\" ] && [ -w \"\$(dirname \"\$NPM_MODULES\")\" ]; }; then NPM_SUDO=''; else NPM_SUDO='sudo'; fi && " +
                // Resolve npm's absolute path: `sudo npm` would otherwise fail with
                // command-not-found, since macOS sudo's env_reset can drop /usr/local/bin
                // from PATH — and that's exactly where the official .pkg's npm lives.
                "NPM_BIN=\"\$(command -v npm)\" && " +
                "echo 'Cleaning up any partial installations...' && $unixCleanup && " +
                "\$NPM_SUDO \"\$NPM_BIN\" install -g $npmPackages"
            }
            isWindows -> {
                // Use winget or Chocolatey to install Node.js if npm is not available
                val nodeInstallCmd = when {
                    installed.winget -> "winget install OpenJS.NodeJS.LTS --accept-source-agreements --accept-package-agreements"
                    installed.chocolatey -> "choco install nodejs-lts -y"
                    else -> "echo 'No package manager available. Please install Node.js manually.' && exit 1"
                }
                // Windows cleanup: remove partial npm installations
                val windowsCleanup = packagesToClean.joinToString("; ") { pkg ->
                    "Remove-Item -Recurse -Force \"\$(npm prefix -g)/node_modules/$pkg\" -ErrorAction SilentlyContinue"
                }
                "powershell -Command \"if (!(Get-Command npm -ErrorAction SilentlyContinue)) { $nodeInstallCmd } ; " +
                "Write-Host 'Cleaning up any partial installations...'; $windowsCleanup; " +
                "npm install -g $npmPackages\""
            }
            else -> {
                // Linux: use nvm to install Node.js and npm if not available
                // If npm is missing (even with node installed), reinstall node to get npm back
                "export NVM_DIR=\"\$HOME/.nvm\" && " +
                "if [ ! -s \"\$NVM_DIR/nvm.sh\" ]; then " +
                "echo 'Installing nvm...' && " +
                "curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/master/install.sh | bash; " +
                "fi && " +
                ". \"\$NVM_DIR/nvm.sh\" && " +
                "if ! command -v npm >/dev/null 2>&1; then " +
                "echo 'Installing Node.js...' && " +
                "NODE_VER=\$(nvm current 2>/dev/null) && " +
                "if [ \"\$NODE_VER\" != \"none\" ] && [ \"\$NODE_VER\" != \"system\" ]; then nvm uninstall \"\$NODE_VER\" 2>/dev/null; fi && " +
                "nvm install --lts && nvm alias default node; " +
                "fi && " +
                "nvm use default && NPM_SUDO='' && " + // nvm's prefix (~/.nvm) is user-owned → no sudo
                "NPM_BIN=\"\$(command -v npm)\" && " +
                "echo 'Cleaning up any partial installations...' && $unixCleanup && " +
                "\$NPM_SUDO \"\$NPM_BIN\" install -g $npmPackages"
            }
        }
        userCommands.add(nodeCheckAndInstall)

        // Add npm global bin to PATH if not already present
        if (!isWindows) {
            postInstallCommands.add(
                // `npm bin -g` was removed in npm v9 (returns empty) — derive the global bin
                // from the prefix so the just-installed CLIs actually get onto PATH.
                "NPM_BIN=\"\$(npm prefix -g 2>/dev/null)/bin\" && " +
                "if [ -d \"\$NPM_BIN\" ]; then " +
                "  SHELL_NAME=\$(basename \"\$SHELL\") && " +
                "  if [ \"\$SHELL_NAME\" = \"zsh\" ]; then " +
                "    grep -q \"\$NPM_BIN\" ~/.zshrc 2>/dev/null || grep -q \"\$NPM_BIN\" ~/.zprofile 2>/dev/null || " +
                "    echo \"export PATH=\\\"\$NPM_BIN:\\\$PATH\\\"\" >> ~/.zprofile; " +
                "  elif [ \"\$SHELL_NAME\" = \"bash\" ]; then " +
                "    grep -q \"\$NPM_BIN\" ~/.bashrc 2>/dev/null || grep -q \"\$NPM_BIN\" ~/.bash_profile 2>/dev/null || " +
                "    echo \"export PATH=\\\"\$NPM_BIN:\\\$PATH\\\"\" >> ~/.bash_profile; " +
                "  elif [ \"\$SHELL_NAME\" = \"fish\" ]; then " +
                "    fish -c \"contains \$NPM_BIN \\\$fish_user_paths\" 2>/dev/null || " +
                "    fish -c \"set -U fish_user_paths \$NPM_BIN \\\$fish_user_paths\" 2>/dev/null; " +
                "  fi && " +
                "  echo '✓ npm global bin added to PATH'; " +
                "fi"
            )
        }
    }

    // Build final command list
    val allCommands = mutableListOf<String>()

    // Per-CLI installers append to this instead of aborting; reported just before the end.
    val hasGuardedInstalls = !isWindows && userCommands.any { it.contains(FAILED_INSTALLS_VAR) }
    if (hasGuardedInstalls) {
        allCommands.add("$FAILED_INSTALLS_VAR=\"\"")
    }

    // Authenticate sudo upfront for Unix (Starship and other tools internally use sudo)
    // Pre-auth sudo when any step may need it: explicit sudo commands, Starship (creates
    // /usr/local/bin), or mac AI installs (a root-owned npm global prefix → sudo npm).
    val needsSudo = !isWindows && (sudoCommands.isNotEmpty() || userCommands.any { it.contains("starship") } ||
        (isMac && aiToInstall.isNotEmpty()))
    if (needsSudo) {
        allCommands.add("echo '🔐 Authenticating administrator access...'")
        allCommands.add("sudo -v")
    }

    // Add sudo commands
    allCommands.addAll(sudoCommands)

    // Add user commands (includes Starship install which internally uses sudo)
    allCommands.addAll(userCommands)

    // Add post-install commands
    allCommands.addAll(postInstallCommands)

    // Report anything that failed without taking the rest of the run down with it.
    if (hasGuardedInstalls) {
        allCommands.add(
            "if [ -n \"\$$FAILED_INSTALLS_VAR\" ]; then " +
                "echo ''; " +
                "echo \"⚠ These did not install:\$$FAILED_INSTALLS_VAR\"; " +
                "echo '  Everything else finished. Retry them from Settings ▸ AI Assistants.'; " +
                "fi"
        )
    }

    // Add completion message
    allCommands.add("echo ''")
    allCommands.add("echo '✓ Installation complete!'")

    // For Windows, join with && (PowerShell handles long commands better)
    // For Unix, return bash script content (caller will write to file)
    return if (isWindows) {
        allCommands.joinToString(" && ")
    } else {
        // Return script content - caller will write to file and run it
        buildString {
            appendLine("#!/bin/bash")
            appendLine("set -e")  // Exit on first error
            allCommands.forEach { cmd ->
                appendLine(cmd)
            }
        }
    }
}
