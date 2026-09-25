package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/**
 * Hands `run_in_sidebar` a set of environment variables without putting their values on the
 * command line.
 *
 * Why not `KEY=value command`: the sidebar terminal is a real shell whose scrollback is readable
 * by any agent through `read_scrollback`, and the command text also lands in the host runner's
 * configuration and in this tool's own result. A value typed inline is therefore visible in three
 * places the moment the command runs.
 *
 * Instead the variables AND the command go into a file only the user can read, and the sidebar is
 * sent a short, fixed loader line naming that file. The file removes itself, sets the variables
 * for the command alone (they are gone from the sidebar shell once it finishes, so a later
 * command there cannot read them without a new approval), and runs the command. What the
 * scrollback and the runner entry show is the loader line: a path, never a value.
 *
 * Why the command goes in the file too: a line typed into a terminal before the shell's line
 * editor takes over is capped by the terminal driver (1024 bytes on macOS), so a long command
 * typed with its wrapper was cut off and never ran. The loader line has a fixed length whatever
 * the command.
 *
 * Combined with the host's `{{secret:<id>}}` references, that is how a secret reaches a shell
 * command: the agent writes `"env": {"TOKEN": "{{secret:<id>}}"}`, the host resolves the
 * reference after the operator approves, this plugin receives the value, and the value exists in
 * exactly two places - a 0600 file for the moment until the shell loads it, and the environment
 * of the command.
 *
 * The command never runs without its variables. If the file cannot be loaded (already used by an
 * earlier run, removed by a sweep, or blocked), the loader prints why and nothing runs.
 *
 * What this does not do: it is not a secure enclave. The command has the value, and so does every
 * process it starts; `printenv` inside the command prints it. The point is that the AGENT never
 * has to author the value, and nothing this tool controls echoes it back.
 *
 * The clean long-term shape is an `environment` parameter on BossTerm's tab creation, which
 * builds the PTY environment at spawn (`TabController` in bossterm-compose) - no file, no loader.
 * That needs a BossTerm release and is filed there; this is the version that works today.
 */
internal object SidebarEnvInjection {
    /**
     * The shell syntaxes a command file is written in. The sidebar runs the user's shell, and a
     * file has to be in that shell's language; a shell outside these gets `env` refused up front.
     */
    enum class ShellFamily { POSIX, FISH, POWERSHELL, UNSUPPORTED }

    /** POSIX environment variable names. A key that is not one is refused before anything is written. */
    private val nameRule = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Every env file this object writes starts with this, so a sweep touches nothing else. */
    private const val FILE_PREFIX = "env-"

    /**
     * How long an unconsumed env file may live. The shell normally loads and removes it within
     * milliseconds, or seconds when the sidebar panel is still opening. A file older than this
     * belongs to a command that never ran (typed into a busy terminal and swallowed, say), and it
     * holds the values in plaintext, so it goes. A loader that runs later finds no file and runs
     * nothing.
     */
    const val STALE_AFTER_MS: Long = 10 * 60 * 1000L

    /** What the loader prints instead of running a command whose file is gone. No quote characters, so it needs no escaping in any shell. */
    const val MISSING_ENV_MESSAGE: String =
        "run_in_sidebar: the environment for this command was already used or has expired, so it was not run. Run it again through the agent."

    /** The shared parent of every process's env directory. A var so tests never touch the real data root. */
    @Volatile
    internal var envBaseProvider: () -> File = { bossDataDir("run/env") }

    /**
     * The shell the sidebar terminal runs: BossTerm's own default, which is what the sidebar's
     * tabs get since they never pass a shell (`$SHELL` on Unix, the PowerShell/cmd setting on
     * Windows). A var so tests can pick one.
     */
    @Volatile
    internal var sidebarShellProvider: () -> String = {
        ShellCustomizationUtils.getValidShell(SettingsManager.instance.settings.value.windowsShell)
    }

    /**
     * This process's env directory, `run/env/<pid>`. Per process because two BOSS processes in the
     * same mode share the data root (a second launch, a restart while the old JVM exits), and one's
     * start or stop sweep must not delete a file the other's shell has not loaded yet.
     */
    fun envDir(): File = File(envBaseProvider(), ProcessHandle.current().pid().toString())

    private val ownerOnly: Set<PosixFilePermission> =
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    private val ownerOnlyDir: Set<PosixFilePermission> =
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)

    /** The syntax family of [shell], a path or executable name such as `/bin/zsh` or `powershell.exe`. */
    fun shellFamily(shell: String): ShellFamily =
        when (File(shell.trim()).name.lowercase().removeSuffix(".exe")) {
            "bash", "zsh", "sh", "dash", "ksh", "mksh" -> ShellFamily.POSIX
            "fish" -> ShellFamily.FISH
            "powershell", "pwsh" -> ShellFamily.POWERSHELL
            else -> ShellFamily.UNSUPPORTED
        }

    /** Why [env] cannot be injected, or null when every key is a valid variable name. */
    fun validationError(env: Map<String, String>): String? {
        val bad = env.keys.filterNot { nameRule.matches(it) }
        return if (bad.isEmpty()) null else "Invalid environment variable name(s): ${bad.joinToString()}"
    }

    /**
     * Write [env] and [command] as a [family] command file under [dir] and return it.
     *
     * The permissions are set at creation on a POSIX filesystem, not after, so there is no window
     * in which the file is world-readable; [dir] and, when it is the env base, its parent are
     * owner-only too, so other users cannot even list the file names. On a filesystem without
     * POSIX permissions (Windows) the file lives under the user's own data directory, which is
     * what protects it there.
     */
    fun writeEnvFile(
        env: Map<String, String>,
        command: String,
        dir: File,
        family: ShellFamily,
    ): File {
        require(family != ShellFamily.UNSUPPORTED) { "No command file format for this shell" }
        // The shared base too, but only when it IS the env base: never change the permissions of a
        // directory this does not own.
        val envBase = envBaseProvider()
        if (dir.absoluteFile.parentFile == envBase.absoluteFile) ensureOwnerOnlyDirectory(envBase)
        ensureOwnerOnlyDirectory(dir)
        sweep(dir, olderThanMs = STALE_AFTER_MS)
        val suffix = when (family) {
            ShellFamily.POWERSHELL -> ".env"
            ShellFamily.FISH -> ".fish"
            else -> ".sh"
        }
        val path =
            if (supportsPosix(dir)) {
                Files.createTempFile(dir.toPath(), FILE_PREFIX, suffix, PosixFilePermissions.asFileAttribute(ownerOnly))
            } else {
                Files.createTempFile(dir.toPath(), FILE_PREFIX, suffix)
            }
        val file = path.toFile()
        val content = when (family) {
            ShellFamily.POSIX -> posixScript(env, command, file)
            ShellFamily.FISH -> fishScript(env, command, file)
            else -> powershellData(env, command)
        }
        Files.writeString(path, content)
        return file
    }

    /**
     * The short line the sidebar is sent. It names [file] and nothing else, so its length does
     * not depend on the command; if the file cannot be loaded it prints [MISSING_ENV_MESSAGE]
     * and runs nothing.
     */
    fun loaderCommand(file: File, family: ShellFamily): String =
        when (family) {
            ShellFamily.POSIX -> {
                val path = quotePosix(file.path)
                "[ -r $path ] || echo ${quotePosix(MISSING_ENV_MESSAGE)} >&2; [ -r $path ] && . $path"
            }
            ShellFamily.FISH -> {
                val path = quoteFish(file.path)
                "[ -r $path ] || echo ${quoteFish(MISSING_ENV_MESSAGE)} >&2; [ -r $path ] && source $path"
            }
            ShellFamily.POWERSHELL -> powershellLoader(file)
            ShellFamily.UNSUPPORTED -> error("No loader for this shell")
        }

    /**
     * bash / zsh / sh: remove the file, then run the command in a subshell that alone has the
     * variables, so none of them outlives the command in the sidebar shell. The trade-off: a `cd`
     * or `export` inside the command does not carry over to the sidebar either. `eval` of a
     * single-quoted string keeps the command's own quoting intact.
     */
    fun posixScript(env: Map<String, String>, command: String, file: File): String = buildString {
        append("rm -f -- ").append(quotePosix(file.path)).append('\n')
        append("(\n")
        env.forEach { (k, v) -> append("export ").append(k).append('=').append(quotePosix(v)).append('\n') }
        append("eval ").append(quotePosix(command)).append('\n')
        append(")\n")
    }

    /**
     * fish: remove the file, then run the command in a `begin` block whose variables are local to
     * it (`set -lx`), so they are gone once the command finishes while a `cd` inside it still
     * carries over. Quoted for fish, where a single-quoted string treats `\\` and `\'` as escapes.
     */
    fun fishScript(env: Map<String, String>, command: String, file: File): String = buildString {
        append("rm -f -- ").append(quoteFish(file.path)).append('\n')
        append("begin\n")
        env.forEach { (k, v) -> append("set -lx ").append(k).append(' ').append(quoteFish(v)).append('\n') }
        append("eval ").append(quoteFish(command)).append('\n')
        append("end\n")
    }

    /**
     * PowerShell: data, never parsed as PowerShell source. One `NAME=<base64 of the UTF-8 value>`
     * line per variable and one `:<base64 of the command>` line (a variable name cannot start with
     * `:`). No quote character in a value (PowerShell also reads the curly quotes U+2018 to U+201B
     * as quotes) can end a string and run the rest as code, and no execution policy applies.
     */
    fun powershellData(env: Map<String, String>, command: String): String = buildString {
        env.forEach { (k, v) -> append(k).append('=').append(base64(v)).append('\n') }
        append(':').append(base64(command)).append('\n')
    }

    /**
     * Reads the file with plain .NET calls (the same in Windows PowerShell 5.1 and PowerShell 7,
     * and every failure throws into its catch) and deletes it at once. Then it sets the variables
     * and runs the command with `Invoke-Expression` (no script file, so no execution policy); a
     * `finally` puts every variable back as it was, including after a load that stopped partway.
     * Loading has its own catch, so an error thrown by the command itself surfaces as usual rather
     * than as the not-run message. The catches name only the exception type: a message could
     * quote a value.
     */
    private fun powershellLoader(file: File): String {
        val path = quotePowershell(file.path)
        val notRun = "Write-Host (${quotePowershell("$MISSING_ENV_MESSAGE (")} + \$_.Exception.GetType().Name + ')') -ForegroundColor Red"
        val decode = { expr: String -> "[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($expr))" }
        return "\$__bossLines = \$null; try { \$__bossLines = [IO.File]::ReadAllLines($path); [IO.File]::Delete($path) } catch { $notRun }; " +
            "if (\$null -ne \$__bossLines) { \$__bossSaved = @{}; \$__bossCommand = \$null; \$__bossLoaded = \$false; try { " +
            "try { foreach (\$__bossLine in \$__bossLines) { " +
            "if (\$__bossLine.Length -eq 0) { continue }; " +
            "if (\$__bossLine.StartsWith(':')) { \$__bossCommand = ${decode("\$__bossLine.Substring(1)")}; continue }; " +
            "\$__bossAt = \$__bossLine.IndexOf('='); \$__bossName = \$__bossLine.Substring(0, \$__bossAt); " +
            "\$__bossValue = ${decode("\$__bossLine.Substring(\$__bossAt + 1)")}; " +
            "\$__bossSaved[\$__bossName] = [Environment]::GetEnvironmentVariable(\$__bossName, 'Process'); " +
            "[Environment]::SetEnvironmentVariable(\$__bossName, \$__bossValue, 'Process') }; " +
            "\$__bossLoaded = \$true } catch { $notRun }; " +
            "if (\$__bossLoaded -and \$null -ne \$__bossCommand) { Invoke-Expression \$__bossCommand } " +
            "} finally { foreach (\$__bossName in \$__bossSaved.Keys) { " +
            "[Environment]::SetEnvironmentVariable(\$__bossName, \$__bossSaved[\$__bossName], 'Process') } } }"
    }

    /**
     * Delete env files in [dir]: all of them when [olderThanMs] is null, otherwise those last
     * modified more than that long ago. Nothing but this object's own files is touched.
     *
     * @return how many files were deleted.
     */
    fun sweep(dir: File, olderThanMs: Long?, now: Long = System.currentTimeMillis()): Int {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) } ?: return 0
        return files.count { f -> (olderThanMs == null || now - f.lastModified() > olderThanMs) && f.delete() }
    }

    /**
     * The plugin start and stop sweep. Every file of this process, and of any process that is no
     * longer running (a crash never ran its stop sweep), goes: every terminal that could load one
     * of this process's files belongs to this plugin instance, so at start and stop none is
     * pending. A live other process keeps its files unless they are stale, which also covers a
     * dead BOSS process whose pid was reused by something else. Files from the earlier flat
     * layout (`run/env/env-*`) are removed too.
     *
     * @return how many files were deleted.
     */
    fun sweepForLifecycle(
        base: File = envBaseProvider(),
        selfPid: Long = ProcessHandle.current().pid(),
        isAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
        now: Long = System.currentTimeMillis(),
    ): Int {
        var removed = sweep(base, olderThanMs = null)
        for (child in base.listFiles { f -> f.isDirectory }.orEmpty()) {
            val pid = child.name.toLongOrNull() ?: continue
            removed += if (pid != selfPid && isAlive(pid)) {
                sweep(child, STALE_AFTER_MS, now)
            } else {
                sweep(child, olderThanMs = null)
            }
            child.delete() // only succeeds once empty; a live process's pending file keeps it
        }
        return removed
    }

    /** POSIX single quoting: the only character a single-quoted string cannot hold is the quote itself. */
    internal fun quotePosix(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** fish single quoting: `\` and `'` are the two escapes inside single quotes. */
    internal fun quoteFish(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /**
     * A PowerShell single-quoted string. Every character PowerShell reads as a single quote is
     * doubled - ASCII `'` and U+2018 to U+201B - as its own
     * `CodeGeneration.EscapeSingleQuotedStringContent` does. Used for paths and fixed messages;
     * values and commands never go through it (see [powershellData]).
     */
    internal fun quotePowershell(s: String): String =
        "'" + s.replace(Regex("['\\u2018\\u2019\\u201A\\u201B]")) { it.value + it.value } + "'"

    private fun base64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun supportsPosix(dir: File): Boolean =
        dir.toPath().fileSystem.supportedFileAttributeViews().contains("posix")

    private fun ensureOwnerOnlyDirectory(dir: File) {
        Files.createDirectories(dir.toPath())
        if (supportsPosix(dir)) Files.setPosixFilePermissions(dir.toPath(), ownerOnlyDir)
    }
}
