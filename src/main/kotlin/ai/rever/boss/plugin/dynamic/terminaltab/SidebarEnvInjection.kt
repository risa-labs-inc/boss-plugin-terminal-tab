package ai.rever.boss.plugin.dynamic.terminaltab

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Hands `run_in_sidebar` a set of environment variables without putting their values on the
 * command line.
 *
 * Why not `KEY=value command`: the sidebar terminal is a real shell whose scrollback is readable
 * by any agent through `read_scrollback`, and the command text also lands in the host runner's
 * configuration and in this tool's own result. A value typed inline is therefore visible in three
 * places the moment the command runs. This writes the pairs to a file only the user can read,
 * sources it, deletes it, and runs the command; what the scrollback shows is the file's path.
 *
 * Combined with the host's `{{secret:<id>}}` references, that is how a secret reaches a shell
 * command: the agent writes `"env": {"TOKEN": "{{secret:<id>}}"}`, the host resolves the
 * reference after the operator approves, this plugin receives the value, and the value exists
 * in exactly two places - a 0600 file for the milliseconds until the shell sources it, and the
 * shell's environment.
 *
 * What this does not do: it is not a secure enclave. The shell has the value, and so does every
 * process the command starts; `env` typed by the user, or `printenv` typed by the agent, prints
 * it. The point is that the AGENT never has to author the value, and nothing this tool controls
 * echoes it back.
 *
 * The clean long-term shape is an `environment` parameter on BossTerm's tab creation, which
 * builds the PTY environment at spawn (`TabController` in bossterm-compose) - no file, no source
 * line. That needs a BossTerm release and is filed there; this is the version that works today.
 */
internal object SidebarEnvInjection {
    /** POSIX environment variable names. A key that is not one is refused before anything is written. */
    private val nameRule = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private val ownerOnly: Set<PosixFilePermission> =
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    /** Why [env] cannot be injected, or null when every key is a valid variable name. */
    fun validationError(env: Map<String, String>): String? {
        val bad = env.keys.filterNot { nameRule.matches(it) }
        return if (bad.isEmpty()) null else "Invalid environment variable name(s): ${bad.joinToString()}"
    }

    /**
     * Write [env] to a fresh owner-only file under [dir] and return it.
     *
     * The permissions are set at creation on a POSIX filesystem, not after, so there is no window
     * in which the file is world-readable. On a filesystem without POSIX permissions (Windows) the
     * file is created under the user's own data directory, which is what protects it there.
     */
    fun writeEnvFile(
        env: Map<String, String>,
        dir: File,
        windows: Boolean = isWindows,
    ): File {
        Files.createDirectories(dir.toPath())
        val suffix = if (windows) ".ps1" else ".sh"
        val posix = dir.toPath().fileSystem.supportedFileAttributeViews().contains("posix")
        val path =
            if (posix) {
                Files.createTempFile(dir.toPath(), "env-", suffix, PosixFilePermissions.asFileAttribute(ownerOnly))
            } else {
                Files.createTempFile(dir.toPath(), "env-", suffix)
            }
        Files.writeString(path, if (windows) powershellScript(env) else posixScript(env))
        return path.toFile()
    }

    /**
     * The command the shell runs: source the file, remove it, then [command] - joined with `&&`
     * so a command that expects its variables never runs without them. Removal happens before the
     * command, so a long-running command does not keep the file alive.
     */
    fun wrapCommand(
        command: String,
        file: File,
        windows: Boolean = isWindows,
    ): String =
        if (windows) {
            ". ${quotePowershell(file.path)}; Remove-Item -LiteralPath ${quotePowershell(file.path)} -Force; $command"
        } else {
            ". ${quotePosix(file.path)} && rm -f ${quotePosix(file.path)} && $command"
        }

    /** One `export` per pair, single-quoted; the only character a single-quoted POSIX string cannot hold is the quote itself. */
    fun posixScript(env: Map<String, String>): String =
        env.entries.joinToString("\n", postfix = "\n") { (k, v) -> "export $k=${quotePosix(v)}" }

    /** One `$env:` assignment per pair, single-quoted; PowerShell doubles a quote to embed it. */
    fun powershellScript(env: Map<String, String>): String =
        env.entries.joinToString("\n", postfix = "\n") { (k, v) -> "\$env:$k = ${quotePowershell(v)}" }

    private fun quotePosix(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun quotePowershell(s: String): String = "'" + s.replace("'", "''") + "'"
}
