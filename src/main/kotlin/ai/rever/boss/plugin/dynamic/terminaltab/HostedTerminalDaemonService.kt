package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.DaemonService
import ai.rever.boss.plugin.api.DaemonServiceContext
import ai.rever.bossterm.compose.TerminalRuntimeLifecycle
import ai.rever.bossterm.compose.daemon.DaemonAttachProtocol
import ai.rever.bossterm.compose.daemon.HostedTerminalPool
import kotlinx.serialization.json.*

/** Background entry point. It intentionally receives no PluginContext, windows or UI callbacks. */
class HostedTerminalDaemonService : DaemonService {
    private var pool: HostedTerminalPool? = null
    private var environment: Map<String, String> = emptyMap()

    override suspend fun start(context: DaemonServiceContext, configuration: Map<String, String>): Map<String, String> {
        System.setProperty("bossterm.settings.dir", requireNotNull(configuration["settingsDirectory"]))
        TerminalRuntimeLifecycle.activateHostLifetime()
        // A hosted worker must never adopt a standalone BossTerm account from disk.
        ai.rever.bossterm.compose.share.AccountSessionSource.disconnect()
        neutralizeStalePty4jNativeFolder()
        environment = mapOf("BOSS_MCP_SERVER" to "boss")
        pool = HostedTerminalPool { environment }
        return mapOf("attachProtocol" to DaemonAttachProtocol.PROTOCOL_VERSION.toString())
    }

    override suspend fun request(method: String, payload: String): String {
        require(method == "attach" || method == "close") { "Unknown terminal service request" }
        require(payload.length in 1..512) { "Invalid terminal identity" }
        if (method == "close") {
            checkNotNull(pool).closeSurface(payload)
            return ""
        }
        val endpoint = checkNotNull(pool).attach(payload)
        return buildJsonObject {
            put("port", endpoint.port)
            put("token", endpoint.token)
            put("protocol", endpoint.protocol)
        }.toString()
    }

    override suspend fun stop() {
        try { pool?.close() }
        finally { TerminalRuntimeLifecycle.shutdownForUnload() }
        pool = null
    }
}
