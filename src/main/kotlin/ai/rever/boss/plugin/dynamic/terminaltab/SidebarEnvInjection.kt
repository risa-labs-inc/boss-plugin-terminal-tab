package ai.rever.boss.plugin.dynamic.terminaltab

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
 * places the moment the command runs. This writes the pairs to a file only the user can read, the
 * shell loads and deletes it, and only then runs the command; what the scrollback shows is the
 * file's path.
 *
 * Combined with the host's `{{secret:<id>}}` references, that is how a secret reaches a shell
 * command: the agent writes `"env": {"TOKEN": "{{secret:<id>}}"}`, the host resolves the
 * reference after the operator approves, this plugin receives the value, and the value exists
 * in exactly two places - a 0600 file for the milliseconds until the shell loads it, and the
 * shell's environment.
 *
 * The command never runs without its variables. If the file cannot be loaded (already used by an
 * earlier run, removed by a sweep, or blocked), the shell prints why and the command does not run,
 * on POSIX shells and on Windows PowerShell alike, compound commands included.
 *
 * What this does not do: it is not a secure enclave. The shell has the value, and so does every
 * process the command starts; `env` typed by the user, or `printenv` typed by the agent, prints
 * it. The point is that the AGENT never has to author the value, and nothing this tool controls
 * echoes it back.
 *
 * The clean long-term shape is an `environment` parameter on BossTerm's tab creation, which
 * builds the PTY environment at spawn (`TabController` in bossterm-compose) - no file, no load
 * line. That needs a BossTerm release and is filed there; this is the version that works today.
 */
internal object SidebarEnvInjection {
    /** POSIX environment variable names. A key that is not one is refused before anything is written. */
    private val nameRule = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Every env file this object writes starts with this, so a sweep touches nothing else. */
    private const val FILE_PREFIX = "env-"

    /**
     * How long an unconsumed env file may live. The shell normally loads and removes it within
     * milliseconds, or seconds when the sidebar panel is still opening. A file older than this
     * belongs to a command that never ran (typed into a busy terminal and swallowed, say), and it
     * holds the values in plaintext, so it goes. A command that does run later finds no file and
     * does not run.
     */
    const val STALE_AFTER_MS: Long = 10 * 60 * 1000L

    /** What the shell prints instead of running a command whose env file is gone. */
    const val MISSING_ENV_MESSAGE: String =
        "run_in_sidebar: this command's environment was already used or has expired, so it was not run. Run it again through the agent."

    /** The shared parent of every process's env directory. A var so tests never touch the real data root. */
    @Volatile
    internal var envBaseProvider: () -> File = { bossDataDir("run/env") }

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

    /** Why [env] cannot be injected, or null when every key is a valid variable name. */
    fun validationError(env: Map<String, String>): String? {
        val bad = env.keys.filterNot { nameRule.matches(it) }
        return if (bad.isEmpty()) null else "Invalid environment variable name(s): ${bad.joinToString()}"
    }

    /**
     * Write [env] to a fresh owner-only file under [dir] and return it.
     *
     * The permissions are set at creation on a POSIX filesystem, not after, so there is no window
     * in which the file is world-readable; [dir] and its parent are owner-only too, so other users
     * cannot even list the file names. On a filesystem without POSIX permissions (Windows) the file
     * lives under the user's own data directory, which is what protects it there.
     */
    fun writeEnvFile(
        env: Map<String, String>,
        dir: File,
        windows: Boolean = isWindows,
    ): File {
        // The shared base too, so file names in other processes' directories are private; but only
        // when it IS the env base: never change the permissions of a directory this does not own.
        val envBase = envBaseProvider()
        if (dir.absoluteFile.parentFile == envBase.absoluteFile) ensureOwnerOnlyDirectory(envBase)
        ensureOwnerOnlyDirectory(dir)
        sweep(dir, olderThanMs = STALE_AFTER_MS)
        val suffix = if (windows) ".env" else ".sh"
        val path =
            if (supportsPosix(dir)) {
                Files.createTempFile(dir.toPath(), FILE_PREFIX, suffix, PosixFilePermissions.asFileAttribute(ownerOnly))
            } else {
                Files.createTempFile(dir.toPath(), FILE_PREFIX, suffix)
            }
        Files.writeString(path, if (windows) powershellData(env) else posixScript(env))
        return path.toFile()
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
     * The plugin start and stop sweep: this process's env files, and those of any process that is
     * no longer running (a crash never ran its stop sweep). Every terminal that could load one of
     * this process's files belongs to this plugin instance, so at start and stop none is pending.
     * A live other process's directory is left alone. Files from the earlier flat layout
     * (`run/env/env-*`) are removed too.
     *
     * @return how many files were deleted.
     */
    fun sweepForLifecycle(
        base: File = envBaseProvider(),
        selfPid: Long = ProcessHandle.current().pid(),
        isAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
    ): Int {
        var removed = sweep(base, olderThanMs = null)
        for (child in base.listFiles { f -> f.isDirectory }.orEmpty()) {
            val pid = child.name.toLongOrNull() ?: continue
            if (pid != selfPid && isAlive(pid)) continue
            removed += sweep(child, olderThanMs = null)
            child.delete() // only succeeds once empty; a stray file keeps the directory, harmlessly
        }
        return removed
    }

    /**
     * The command the shell runs: load the file, remove it, then [command], which runs only if
     * both succeeded.
     *
     * POSIX shells (bash, zsh, fish): `. file && rm -f file && eval '<command>'`. `eval` keeps a
     * compound command (`a; b`, `a || b`) wholly behind the `&&`; typed bare, `. f && rm && a; b`
     * would still run `b` when the file is missing. The readable check in front only prints why.
     *
     * Windows PowerShell: the file is data, not a script (see [powershellData]), so the execution
     * policy cannot block it and no value reaches the parser; the command runs only when loading
     * set `$__bossEnvOk`. Windows PowerShell 5.1 has no `&&`, hence the flag.
     */
    fun wrapCommand(
        command: String,
        file: File,
        windows: Boolean = isWindows,
    ): String =
        if (windows) {
            val path = quotePowershell(file.path)
            "\$__bossEnvOk = \$false; try { " +
                "Get-Content -LiteralPath $path -ErrorAction Stop | ForEach-Object { " +
                "\$__bossPair = \$_ -split '=', 2; " +
                "Set-Item -LiteralPath (\"env:\" + \$__bossPair[0]) -Value " +
                "([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(\$__bossPair[1]))) }; " +
                "Remove-Item -LiteralPath $path -Force -ErrorAction Stop; \$__bossEnvOk = \$true " +
                "} catch { Write-Host ${quotePowershell(MISSING_ENV_MESSAGE)} -ForegroundColor Red }; " +
                "if (\$__bossEnvOk) { $command }"
        } else {
            val path = quotePosix(file.path)
            "[ -r $path ] || echo ${quotePosix(MISSING_ENV_MESSAGE)} >&2; " +
                ". $path && rm -f $path && eval ${quotePosix(command)}"
        }

    /** One `export` per pair, single-quoted; the only character a single-quoted POSIX string cannot hold is the quote itself. */
    fun posixScript(env: Map<String, String>): String =
        env.entries.joinToString("\n", postfix = "\n") { (k, v) -> "export $k=${quotePosix(v)}" }

    /**
     * One `NAME=<base64 of the UTF-8 value>` line per pair. Data the wrapper decodes, never
     * PowerShell source: a value is never parsed, so no quote character in it (PowerShell also
     * treats the curly quotes U+2018-U+201B as quotes) can end a string and run the rest as code.
     */
    fun powershellData(env: Map<String, String>): String =
        env.entries.joinToString("\n", postfix = "\n") { (k, v) ->
            "$k=" + Base64.getEncoder().encodeToString(v.toByteArray(Charsets.UTF_8))
        }

    private fun quotePosix(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * A PowerShell single-quoted string. Every character PowerShell reads as a single quote is
     * doubled - ASCII `'` and U+2018 to U+201B - as its own
     * `CodeGeneration.EscapeSingleQuotedStringContent` does. Used for paths and fixed messages;
     * values never go through it (see [powershellData]).
     */
    internal fun quotePowershell(s: String): String =
        "'" + s.replace(Regex("['\u2018\u2019\u201A\u201B]")) { it.value + it.value } + "'"

    private fun supportsPosix(dir: File): Boolean =
        dir.toPath().fileSystem.supportedFileAttributeViews().contains("posix")

    private fun ensureOwnerOnlyDirectory(dir: File) {
        Files.createDirectories(dir.toPath())
        if (supportsPosix(dir)) Files.setPosixFilePermissions(dir.toPath(), ownerOnlyDir)
    }
}
