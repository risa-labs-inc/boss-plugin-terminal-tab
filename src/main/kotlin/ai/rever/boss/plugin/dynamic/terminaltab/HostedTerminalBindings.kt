package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.DaemonServiceConnection
import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.daemon.DaemonAttachProtocol
import ai.rever.bossterm.compose.daemon.DaemonBridgeCoordinator
import ai.rever.bossterm.compose.settings.SettingsManager
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*

/** Connections belong to retained state; dropping UI never stops its background terminal surface. */
internal object HostedTerminalBindings {
    enum class Status { CONNECTING, DAEMON, LOCAL, FAILED }
    private class Binding(val windowId: String, val identity: String, val cwd: String?, val command: String?) {
        val status = MutableStateFlow(Status.CONNECTING)
        var job: Job? = null
        @Volatile var worker: DaemonServiceConnection? = null
    }
    private val bindings = java.util.concurrent.ConcurrentHashMap<TabbedTerminalState, Binding>()

    fun bind(state: TabbedTerminalState, windowId: String, terminalId: String, cwd: String?, command: String?): MutableStateFlow<Status> {
        bindings[state]?.let { return it.status }
        val binding = Binding(windowId, HostWindows.terminalIdentity(windowId, terminalId), cwd, command)
        if (bindings.putIfAbsent(state, binding) != null) return bindings.getValue(state).status
        if (HostWindows.daemonFor(windowId) == null || !SettingsManager.instance.settings.value.daemonEnabled) {
            binding.status.value = Status.LOCAL
        } else start(state, binding)
        return binding.status
    }

    private fun start(state: TabbedTerminalState, binding: Binding) {
        binding.status.value = Status.CONNECTING
        TerminalStateLifetime.ifAccepting(binding.windowId) { scope ->
            // An unload barrier may join this job on the EDT; its cancellation must drain on IO.
            binding.job = scope.launch(Dispatchers.IO) {
                try {
                    val provider = checkNotNull(HostWindows.daemonFor(binding.windowId))
                    val worker = provider.connect("terminals", HostedTerminalDaemonService::class.java.name,
                        System.getProperty("bossterm.settings.dir")?.let { mapOf("settingsDirectory" to it) }.orEmpty())
                    binding.worker = worker
                    check(worker.endpoints["attachProtocol"] == DaemonAttachProtocol.PROTOCOL_VERSION.toString())
                    val endpoint = Json.parseToJsonElement(worker.request("attach", binding.identity)).jsonObject
                    check(endpoint.getValue("protocol").jsonPrimitive.int == DaemonAttachProtocol.PROTOCOL_VERSION)
                    synchronized(binding) {
                        if (bindings[state] === binding) {
                            DaemonBridgeCoordinator.registerHosted(state, scope,
                                endpoint.getValue("port").jsonPrimitive.int,
                                endpoint.getValue("token").jsonPrimitive.content, binding.cwd, binding.command)
                            binding.status.value = Status.DAEMON
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { binding.status.value = Status.FAILED }
            }
        } ?: run { binding.status.value = Status.FAILED }
    }

    fun retry(state: TabbedTerminalState) {
        bindings[state]?.let { binding ->
            synchronized(binding) {
                if (binding.status.value == Status.FAILED) start(state, binding)
            }
        }
    }

    /** Explicit terminal removal/reset stops PTYs; window closure and plugin reload only detach. */
    fun close(state: TabbedTerminalState) {
        val binding = bindings[state] ?: return
        binding.job?.cancel()
        runBlocking(Dispatchers.IO) {
            binding.job?.join()
            binding.worker?.request("close", binding.identity)
        }
    }

    fun detach(state: TabbedTerminalState, forUnload: Boolean) {
        val binding = bindings.remove(state) ?: return
        synchronized(binding) { binding.job?.cancel() }
        if (forUnload) runBlocking(Dispatchers.IO) { binding.job?.join() }
    }
}

/** A failed connection never falls back to a second shell that might repeat the queued command. */
@Composable
internal fun terminalDaemonMode(state: TabbedTerminalState, windowId: String, terminalId: String,
    cwd: String?, command: String?): Boolean? {
    val binding = remember(state) { HostedTerminalBindings.bind(state, windowId, terminalId, cwd, command) }
    val status by binding.collectAsState()
    return when (status) {
        HostedTerminalBindings.Status.DAEMON -> true
        HostedTerminalBindings.Status.LOCAL -> false
        else -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (status == HostedTerminalBindings.Status.CONNECTING) "Connecting to BOSS background terminals…"
                        else "Could not connect to BOSS background terminals.")
                    if (status == HostedTerminalBindings.Status.FAILED) {
                        TextButton(onClick = { HostedTerminalBindings.retry(state) }) { Text("Retry") }
                    }
                }
            }
            null
        }
    }
}
