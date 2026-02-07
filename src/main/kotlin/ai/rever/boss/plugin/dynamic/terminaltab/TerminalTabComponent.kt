package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.reflect.full.memberProperties

/**
 * Terminal tab component using BossTerm library for terminal emulation.
 *
 * This component renders a persistent terminal session using the host's
 * TerminalTabContentProvider. Each terminal tab has its own independent
 * session that persists across composition changes.
 */
class TerminalTabComponent(
    private val ctx: ComponentContext,
    override val config: TabInfo,
    private val context: PluginContext
) : TabComponentWithUI, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = TerminalTabType

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Extract initialCommand and workingDirectory - handles both TerminalTabData (from plugin)
    // and TerminalTabInfo (from host) using reflection
    private val initialCommand: String? = extractInitialCommand(config)
    private val workingDirectory: String? = extractWorkingDirectory(config)

    init {
        lifecycle.subscribe(
            callbacks = object : Lifecycle.Callbacks {
                override fun onDestroy() {
                    coroutineScope.cancel()
                }
            }
        )
    }

    /**
     * Extract initialCommand from config using reflection.
     * Handles both our TerminalTabData and host's TerminalTabInfo.
     */
    private fun extractInitialCommand(config: TabInfo): String? {
        // First try our own TerminalTabData
        if (config is TerminalTabData) {
            return config.initialCommand
        }

        // Try to get initialCommand via reflection (for TerminalTabInfo from host)
        return try {
            val property = config::class.memberProperties.find { it.name == "initialCommand" }
            property?.getter?.call(config) as? String
        } catch (e: Exception) {
            System.err.println("[TerminalTabComponent] Failed to extract initialCommand via reflection: ${e.message}")
            null
        }
    }

    /**
     * Extract workingDirectory from config using reflection.
     * Handles both our TerminalTabData and host's TerminalTabInfo.
     */
    private fun extractWorkingDirectory(config: TabInfo): String? {
        // First try our own TerminalTabData
        if (config is TerminalTabData) {
            return config.workingDirectory
        }

        // Try to get workingDirectory via reflection (for TerminalTabInfo from host)
        return try {
            val property = config::class.memberProperties.find { it.name == "workingDirectory" }
            property?.getter?.call(config) as? String
        } catch (e: Exception) {
            System.err.println("[TerminalTabComponent] Failed to extract workingDirectory via reflection: ${e.message}")
            null
        }
    }

    @Composable
    override fun Content() {
        // Get providers from context
        val terminalTabContentProvider = context.terminalTabContentProvider
        val tabUpdateProviderFactory = context.tabUpdateProviderFactory

        // Check if terminal provider is available
        if (terminalTabContentProvider == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Terminal provider not available",
                    color = Color(0xFFFF6B6B)
                )
            }
            return
        }

        // Get tab update provider for this tab
        val tabUpdateProvider = remember(config.id) {
            tabUpdateProviderFactory?.createProvider(config.id, TerminalTabType.typeId)
        }

        // Track current title for updates
        var currentTitle by remember { mutableStateOf(config.title) }

        // Cleanup when component is disposed
        DisposableEffect(config.id) {
            onDispose {
                // Terminal state cleanup is handled by the provider
            }
        }

        // Render terminal content using the provider
        terminalTabContentProvider.PersistentTabbedTerminalContent(
            terminalId = config.id,
            initialCommand = initialCommand,
            workingDirectory = workingDirectory,
            onExit = {
                // Terminal exited - the tab will be closed by the host
            },
            onTitleChange = { newTitle ->
                if (newTitle != currentTitle) {
                    currentTitle = newTitle
                    // Update tab title via TabUpdateProvider
                    tabUpdateProvider?.updateTitle(newTitle)
                }
            }
        )
    }
}
