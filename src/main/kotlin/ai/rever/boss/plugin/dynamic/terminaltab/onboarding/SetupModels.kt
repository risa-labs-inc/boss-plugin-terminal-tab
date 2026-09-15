package ai.rever.boss.plugin.dynamic.terminaltab.onboarding

import ai.rever.bossterm.compose.ai.AIAssistants

enum class ShellChoice(
    val id: String,
    val displayName: String,
    val description: String,
    val command: String,
    val isWindows: Boolean = false,
) {
    ZSH("zsh", "Zsh", "Modern shell with powerful features, tab completion, and plugin support", "zsh"),
    BASH("bash", "Bash", "Classic Unix shell, widely compatible across systems", "bash"),
    FISH("fish", "Fish", "User-friendly shell with autosuggestions and syntax highlighting", "fish"),
    POWERSHELL("powershell", "PowerShell", "Modern Windows shell with scripting and automation", "powershell.exe", true),
    CMD("cmd", "Command Prompt", "Classic Windows command line interpreter", "cmd.exe", true),
    KEEP_CURRENT("keep", "Keep Current", "Use your current default shell", ""),
}

enum class ShellCustomizationChoice(
    val id: String,
    val displayName: String,
    val description: String,
    val requiresZsh: Boolean = false,
    val isWindowsOnly: Boolean = false,
) {
    STARSHIP("starship", "Starship", "Fast, minimal, customizable prompt for any shell"),
    OH_MY_ZSH("oh-my-zsh", "Oh My Zsh", "Framework with plugins and themes", true),
    PREZTO("prezto", "Prezto", "Lightweight Zsh configuration framework", true),
    OH_MY_POSH("oh-my-posh", "Oh My Posh", "Prompt theme engine for PowerShell and CMD", isWindowsOnly = true),
    NONE("none", "None", "Keep the default shell prompt"),
    KEEP_EXISTING("keep", "Keep Existing", "Keep the customization already installed"),
}

enum class PackageManagerChoice(val displayName: String) {
    AUTO("Recommended for this computer"), HOMEBREW("Homebrew"), WINGET("winget"),
    CHOCOLATEY("Chocolatey"), NONE("Do not install a package manager"),
}

data class OnboardingSelections(
    val packageManager: PackageManagerChoice = PackageManagerChoice.AUTO,
    val shell: ShellChoice = ShellChoice.ZSH,
    val shellCustomization: ShellCustomizationChoice = ShellCustomizationChoice.STARSHIP,
    val installGit: Boolean = true,
    val installGitHubCLI: Boolean = true,
    val authenticateGitHub: Boolean = false,
    val aiAssistants: Set<String> = AIAssistants.DEFAULT_ONBOARDING_SELECTION,
)

data class InstalledTools(
    val zsh: Boolean = false, val bash: Boolean = false, val fish: Boolean = false,
    val powershell: Boolean = false, val cmd: Boolean = false,
    val winget: Boolean = false, val chocolatey: Boolean = false, val homebrew: Boolean = false,
    val starship: Boolean = false, val ohMyZsh: Boolean = false, val prezto: Boolean = false,
    val ohMyPosh: Boolean = false, val git: Boolean = false, val gh: Boolean = false,
    val aiAssistants: Map<String, Boolean> = emptyMap(),
) {
    fun isAiInstalled(id: String) = aiAssistants[id] == true
    val hasAnyShellCustomization get() = starship || ohMyZsh || prezto || ohMyPosh
    val hasWindowsPackageManager get() = winget || chocolatey
    val hasMacPackageManager get() = homebrew
    val hasPackageManager get() = winget || chocolatey || homebrew
}
