package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.api.TabUpdateProvider
import ai.rever.boss.plugin.api.TabUpdateProviderFactory
import ai.rever.boss.plugin.api.TerminalTabContentProvider
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
    private val terminalTabContentProvider: TerminalTabContentProvider,
    private val tabUpdateProviderFactory: TabUpdateProviderFactory?
) : TabComponentWithUI, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = TerminalTabType

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        lifecycle.subscribe(
            callbacks = object : Lifecycle.Callbacks {
                override fun onDestroy() {
                    coroutineScope.cancel()
                }
            }
        )
    }

    @Composable
    override fun Content() {
        // Get tab update provider for this tab
        val tabUpdateProvider = remember(config.id) {
            tabUpdateProviderFactory?.createProvider(config.id, TerminalTabType.typeId)
        }

        // Get initial command and working directory from config if it's a TerminalTabInfo
        val terminalConfig = config as? TerminalTabInfo
        val initialCommand = terminalConfig?.initialCommand
        val workingDirectory = terminalConfig?.workingDirectory

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
