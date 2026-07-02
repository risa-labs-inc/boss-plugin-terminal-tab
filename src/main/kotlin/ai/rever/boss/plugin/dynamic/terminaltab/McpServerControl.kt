package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.McpAttachOutcome
import ai.rever.boss.plugin.api.McpAttachTargetInfo
import ai.rever.boss.plugin.api.McpServerController
import ai.rever.boss.plugin.api.McpServerState
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.bossterm.compose.mcp.McpAttachResult
import ai.rever.bossterm.compose.mcp.McpAttachTarget
import ai.rever.bossterm.compose.mcp.McpCliAttacher
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val serverControlLogger = BossLogger.forComponent("TerminalTabMcpServerControl")

/**
 * [McpServerController] implementation backed by the bundled BossTerm MCP stack:
 * enabled state lives in BossTerm's [SettingsManager] (`mcpEnabled` — the MCP
 * manager reconciles start/stop on change), running/port + attached targets come
 * from [McpTerminalRegistry], and attach shells out via [McpCliAttacher].
 *
 * Registered in [TerminalTabDynamicPlugin.register] via `registerPluginAPI`, so
 * management UIs (Plugin Manager's MCP tab) resolve it with
 * `getPluginAPI(McpServerController::class.java)`.
 */
internal class McpServerControllerImpl(scope: CoroutineScope) : McpServerController {

    private val settings = SettingsManager.instance

    override val state: StateFlow<McpServerState> =
        combine(settings.settings, McpTerminalRegistry.runningPort) { s, runningPort ->
            McpServerState(
                serverName = McpTerminalRegistry.mcpServerName,
                enabled = s.mcpEnabled,
                running = runningPort != null,
                port = runningPort ?: s.mcpPort,
            )
        }.stateIn(
            scope, SharingStarted.Eagerly,
            McpServerState(
                serverName = McpTerminalRegistry.mcpServerName,
                enabled = settings.settings.value.mcpEnabled,
                running = McpTerminalRegistry.runningPort.value != null,
                port = McpTerminalRegistry.runningPort.value ?: settings.settings.value.mcpPort,
            ),
        )

    override val attachTargets: StateFlow<List<McpAttachTargetInfo>> =
        McpTerminalRegistry.attachedTargets.map { attached ->
            McpAttachTarget.entries.map { t ->
                McpAttachTargetInfo(key = t.persistenceKey, displayName = t.displayName, attached = t in attached)
            }
        }.stateIn(
            scope, SharingStarted.Eagerly,
            McpAttachTarget.entries.map { t ->
                McpAttachTargetInfo(t.persistenceKey, t.displayName, t in McpTerminalRegistry.attachedTargets.value)
            },
        )

    override fun setEnabled(enabled: Boolean) {
        settings.updateSetting { copy(mcpEnabled = enabled) }
    }

    override suspend fun attach(targetKey: String): McpAttachOutcome {
        val target = McpAttachTarget.fromPersistenceKey(targetKey)
            ?: return McpAttachOutcome(false, "Unknown attach target: $targetKey")
        val port = McpTerminalRegistry.runningPort.value
            ?: return McpAttachOutcome(false, "MCP server is not running — turn it on first.")
        return when (val result = McpCliAttacher.attach(target, McpTerminalRegistry.mcpServerName, port)) {
            is McpAttachResult.Success -> {
                markAttached(target)
                McpAttachOutcome(true, "Attached ${target.displayName}.")
            }
            is McpAttachResult.CopiedToClipboard ->
                McpAttachOutcome(false, "${target.displayName}: ${result.reason}")
        }
    }

    /**
     * Record the attach in the registry so the ✓ badge and startup auto-reattach
     * work. `markAttached` is `internal` to bossterm-compose, so we reach it by
     * scanning declared methods (Kotlin mangles internal names with a module
     * suffix). Best-effort: attach itself already succeeded.
     */
    private fun markAttached(target: McpAttachTarget) {
        runCatching {
            McpTerminalRegistry::class.java.declaredMethods
                .first { it.name.startsWith("markAttached") }
                .apply { isAccessible = true }
                .invoke(McpTerminalRegistry, target)
        }.onFailure {
            serverControlLogger.warn(
                LogCategory.TERMINAL,
                "markAttached reflection failed (attach itself succeeded)",
                error = it,
            )
        }
    }
}
