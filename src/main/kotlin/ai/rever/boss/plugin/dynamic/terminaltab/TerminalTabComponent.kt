package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.LocalWindowIdProvider
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.api.TerminalTabInfoInterface
import ai.rever.boss.plugin.api.TerminalTabPluginAPI
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.bossterm.compose.TabbedTerminalState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Terminal tab component using BossTerm library for terminal emulation.
 *
 * This component renders a persistent terminal session using the plugin's own
 * TerminalTabPluginAPI. Each terminal tab has its own independent session that
 * persists across composition changes.
 *
 * The host tab title is kept in sync from [ensureTitleSync], which runs in the
 * component's own scope so background (unselected) tabs keep updating too.
 */
class TerminalTabComponent(
    private val ctx: ComponentContext,
    override val config: TabInfo,
    private val context: PluginContext
) : TabComponentWithUI, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = TerminalTabType

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Extract initialCommand and workingDirectory via TerminalTabInfoInterface
    private val terminalConfig = config as? TerminalTabInfoInterface
    private val initialCommand: String? = terminalConfig?.initialCommand
    private val workingDirectory: String? = terminalConfig?.workingDirectory

    private val tabUpdateProvider by lazy {
        context.tabUpdateProviderFactory?.createProvider(config.id, TerminalTabType.typeId)
    }

    // The three fields below are Main-thread confined: ensureTitleSync is called
    // from a LaunchedEffect and the collector runs on coroutineScope, both
    // Dispatchers.Main. That confinement is what makes the unguarded mutation
    // safe — don't move coroutineScope off Main without adding synchronization.
    private var titleSyncJob: Job? = null
    private var titleSyncState: TabbedTerminalState? = null

    // Not redundant with the collector's distinctUntilChanged(): that only dedups
    // within one collector, and ensureTitleSync restarts the collector on re-attach
    // (terminal reset), where distinctUntilChanged starts empty. This survives
    // restarts so a re-attach doesn't re-push the unchanged title to the host.
    private var lastPushedTitle: String = config.title

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
     * Push the active inner tab's title to the host tab strip.
     *
     * The host composes only the SELECTED tab's Content(), so any collector
     * wired inside composition goes silent the moment the tab is deselected —
     * background sessions would keep changing their title without the host
     * ever hearing about it. This collector therefore runs in the component's
     * scope (alive until the host tab closes) on [TabbedTerminalState.tabsFlow],
     * which BossTerm feeds outside composition for exactly this kind of
     * consumer. `TerminalTabInfo.title` is the same value BossTerm's own left
     * tab bar shows (cwd label / OSC icon title / rename), so the host tab
     * mirrors it one-to-one.
     *
     * Started lazily from Content() because the state is created on first
     * composition (a never-viewed tab has no session, hence no titles).
     */
    private fun ensureTitleSync(state: TabbedTerminalState) {
        if (titleSyncState === state && titleSyncJob?.isActive == true) return
        titleSyncJob?.cancel()
        titleSyncState = state
        titleSyncJob = coroutineScope.launch {
            combine(state.tabsFlow, state.activeTabIndexFlow) { tabs, activeIndex ->
                (tabs.getOrNull(activeIndex) ?: tabs.firstOrNull())?.title
            }
                .filterNotNull()
                // Deliberately keep the last non-empty title instead of blanking
                // the host tab on a title clear — same policy as the focused-pane
                // path this replaces (TabbedTerminal drops empty window titles).
                .filter { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect { title ->
                    if (title != lastPushedTitle) {
                        lastPushedTitle = title
                        tabUpdateProvider?.updateTitle(title)
                    }
                }
        }
    }

    @Composable
    override fun Content() {
        // Keep the terminal's colors in sync with the active BOSS host theme.
        ApplyHostThemeToTerminal()

        // Get the terminal API from the plugin system (self-referencing since we register it)
        val terminalApi = context.getPluginAPI(TerminalTabPluginAPI::class.java)

        if (terminalApi == null) {
            BossTheme {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Terminal provider not available",
                        color = BossThemeColors.ErrorColor
                    )
                }
            }
            return
        }

        // (Re)attach the component-scoped title sync once the persistent state
        // exists. The effect body runs after composition applies, i.e. after
        // PersistentTabbedTerminalContent below has getOrCreate'd the state —
        // get() must not create it here, or the isNew/initialCommand logic in
        // PersistentTabbedTerminalContentImpl would see a stale registry hit.
        val windowIdProvider = LocalWindowIdProvider.current
        val resetGeneration by TabbedTerminalStateRegistry.resetGeneration.collectAsState()
        LaunchedEffect(windowIdProvider, resetGeneration) {
            val windowId = windowIdProvider?.getWindowId() ?: return@LaunchedEffect
            TabbedTerminalStateRegistry.get(windowId, config.id)?.let(::ensureTitleSync)
        }

        // Cleanup when component is disposed
        DisposableEffect(config.id) {
            onDispose { }
        }

        // Render terminal content using our own API
        terminalApi.PersistentTabbedTerminalContent(
            terminalId = config.id,
            initialCommand = initialCommand,
            workingDirectory = workingDirectory,
            onExit = { },
            onShowSettings = {
                val windowId = context.windowId
                if (windowId != null) {
                    context.settingsProvider?.openSettings(windowId, "TERMINAL")
                }
            },
            // Title updates flow through ensureTitleSync above; wiring them here
            // too would create a second writer with different semantics (focused
            // pane's OSC window title) that only runs while the tab is selected.
            onTitleChange = null
        )
    }
}
