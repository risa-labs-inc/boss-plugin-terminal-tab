package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.DaemonService
import ai.rever.boss.plugin.api.DaemonServiceContext
import ai.rever.bossterm.compose.TerminalRuntimeLifecycle
import ai.rever.bossterm.compose.daemon.DaemonAttachProtocol
import ai.rever.bossterm.compose.daemon.HostedTerminalPool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Background entry point. It intentionally receives no PluginContext, windows or UI callbacks. */
class HostedTerminalDaemonService : DaemonService {
    @Volatile private var pool: HostedTerminalPool? = null

    override suspend fun start(context: DaemonServiceContext, configuration: Map<String, String>): Map<String, String> {
        val directory = requireNotNull(configuration["settingsDirectory"]) { "Missing terminal settings directory" }
        require(directory.isNotBlank()) { "Blank terminal settings directory" }
        // The host runs one daemon per profile; this property never crosses BOSS profiles.
        System.setProperty("bossterm.settings.dir", directory)
        TerminalRuntimeLifecycle.activateHostLifetime()
        // A hosted worker must never adopt a standalone BossTerm account from disk.
        ai.rever.bossterm.compose.share.AccountSessionSource.disconnect()
        neutralizeStalePty4jNativeFolder()
        pool = HostedTerminalPool { mapOf("BOSS_MCP_SERVER" to "boss") }
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
        finally {
            try { TerminalRuntimeLifecycle.shutdownForUnload() }
            finally { pool = null }
        }
    }
}
