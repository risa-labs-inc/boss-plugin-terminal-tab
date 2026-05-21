package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.runtime.RemotePluginContext
import org.slf4j.LoggerFactory

/**
 * Out-of-process terminal tab plugin.
 *
 * In the in-process world this plugin owned `bossterm-compose`'s
 * `EmbeddableTerminal` widget and ran inside the host's Compose tree.
 * In the OOP world (this version), the plugin:
 *
 *  1. Runs in its own JVM child process spawned by BossConsole's
 *     `OutOfProcessPluginSpawnerImpl`.
 *  2. Owns the PTY + bossterm-core engine (see [TerminalSession]).
 *  3. Hosts `TerminalService` (see [TerminalServiceImpl]) on its
 *     `processServer` so the host can stream cell-grid deltas and
 *     forward input events. The wire contract is IPC v1.1.0.
 *
 * The host renders the terminal using its own pure-Compose
 * `TerminalGridRenderer`; bossterm no longer leaks into the host
 * classpath.
 *
 * Entry points:
 *
 *  - [register] — invoked only if a legacy in-process loader still
 *    targets this plugin. Now a no-op; logs and returns. Pre-OOP hosts
 *    will see no terminal tab from this plugin version.
 *  - [registerRemote] — invoked by `PluginProcessMain` in the child
 *    JVM. Registers `TerminalServiceImpl` on the process server and
 *    announces the `terminal` tab type to the host.
 */
class TerminalTabDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.terminaltab"
    override val displayName: String = "Terminal Tab"
    override val version: String = "2.0.0"
    override val description: String =
        "Terminal tab using BossTerm engine (out-of-process). Cell grid + " +
            "cursor are streamed to the host via IPC v1.1.0; the host owns rendering."
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-terminal-tab"

    private val logger = LoggerFactory.getLogger(TerminalTabDynamicPlugin::class.java)
    private var serviceImpl: TerminalServiceImpl? = null

    override fun register(context: PluginContext) {
        // Pre-OOP in-process registration path. This plugin no longer
        // ships a Compose UI — pre-1.1.0 hosts loading this JAR get no
        // terminal tab. The host's `IpcVersion` gate should refuse to
        // load us against pre-1.1.0 hosts anyway, so reaching here
        // suggests a version-skew that needs surfacing.
        logger.warn(
            "TerminalTabDynamicPlugin.register(PluginContext) invoked — this build " +
                "is OOP-only and does not provide in-process rendering. " +
                "Host should call registerRemote(RemotePluginContext) via PluginProcessMain.",
        )
    }

    override fun dispose() {
        serviceImpl?.shutdownAll()
        serviceImpl = null
    }

    /**
     * OOP entry point invoked by `PluginProcessMain` once the child JVM
     * is connected to the kernel. Registers our gRPC service on the
     * process-server and tells the host we provide a `terminal` tab type.
     */
    fun registerRemote(ctx: RemotePluginContext) {
        logger.info("Initialising OOP TerminalTab plugin v{}", version)
        val impl = TerminalServiceImpl(pluginScope = ctx.pluginScope)
        ctx.addProcessService(impl)
        serviceImpl = impl
        ctx.registerTabType(
            surfaceId = TAB_TYPE_ID,
            displayName = "Terminal",
        )
    }

    companion object {
        const val TAB_TYPE_ID: String = "terminal"
    }
}
