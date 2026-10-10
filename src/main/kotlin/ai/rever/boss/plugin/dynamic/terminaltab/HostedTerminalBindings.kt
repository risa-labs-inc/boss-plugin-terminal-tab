package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.DaemonServiceConnection
import ai.rever.boss.plugin.api.DaemonServiceProvider
import ai.rever.boss.plugin.api.PluginUnloadDeferredException
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.daemon.DaemonAttachProtocol
import ai.rever.bossterm.compose.daemon.DaemonBridgeCoordinator
import ai.rever.bossterm.compose.settings.SettingsManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** Connections belong to retained state; dropping UI never stops its background terminal surface. */
internal object HostedTerminalBindings {
    enum class Status { CONNECTING, DAEMON, LOCAL, FAILED }
    private class Binding(val windowId: String, val identity: String, val cwd: String?, val command: String?,
        val provider: DaemonServiceProvider?, val usesDaemon: Boolean) {
        val status = MutableStateFlow(Status.CONNECTING)
        @Volatile var job: Job? = null
        @Volatile var closeJob: Job? = null
        @Volatile var worker: DaemonServiceConnection? = null
        var retired = false
    }
    private val logger = BossLogger.forComponent("HostedTerminalBindings")
    private val bindings = ConcurrentHashMap<TabbedTerminalState, Binding>()
    private val closing = ConcurrentHashMap<String, Binding>()
    // Fixed stripes avoid retaining one lock per anonymous terminal forever. Always acquire
    // the lifetime lock before a binding monitor; never hold a binding monitor across IPC.
    private val identityLocks = Array(64) { Mutex() }
    private fun identityLock(identity: String) = identityLocks[(identity.hashCode() and Int.MAX_VALUE) % identityLocks.size]
    private val retirementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retirements = ConcurrentHashMap<Job, String>()

    fun bind(state: TabbedTerminalState, windowId: String, terminalId: String, cwd: String?, command: String?,
        provider: DaemonServiceProvider? = HostWindows.daemonFor(windowId),
        daemonEnabled: Boolean = SettingsManager.instance.settings.value.daemonEnabled): MutableStateFlow<Status> {
        bindings[state]?.let { return it.status }
        val binding = Binding(windowId, HostWindows.terminalIdentity(windowId, terminalId), cwd, command, provider, provider != null && daemonEnabled)
        bindings.putIfAbsent(state, binding)?.let { return it.status }
        if (!binding.usesDaemon) binding.status.value = Status.LOCAL
        else start(state, binding)
        return binding.status
    }

    private fun start(state: TabbedTerminalState, binding: Binding) {
        val accepted = TerminalStateLifetime.ifAccepting(binding.windowId) { scope ->
            synchronized(binding) {
                if (binding.retired) return@synchronized
                binding.status.value = Status.CONNECTING
                binding.job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    try {
                        // Reset/remove returns immediately on the EDT. A replacement must wait for
                        // its predecessor's close, and retry a failed close before attaching again.
                        closing[binding.identity]?.closeJob?.join()
                        identityLock(binding.identity).withLock {
                            val worker = connect(binding)
                            binding.worker = worker
                            closing[binding.identity]?.let { previous ->
                                // Reconnect first: a failed close may refer to a daemon that died.
                                worker.request("close", previous.identity)
                                closing.remove(previous.identity, previous)
                            }
                            val endpoint = Json.parseToJsonElement(worker.request("attach", binding.identity)).jsonObject
                            check(endpoint.getValue("protocol").jsonPrimitive.int == DaemonAttachProtocol.PROTOCOL_VERSION)
                            synchronized(binding) {
                                if (!binding.retired && bindings[state] === binding) {
                                    DaemonBridgeCoordinator.registerHosted(state, scope,
                                        endpoint.getValue("port").jsonPrimitive.int,
                                        endpoint.getValue("token").jsonPrimitive.content, binding.cwd, binding.command)
                                    binding.status.value = Status.DAEMON
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        logFailure("Connect", failure)
                        binding.status.value = Status.FAILED
                    }
                }.also { track(it, binding.windowId); it.start() }
            }
        }
        if (accepted == null) binding.status.value = Status.FAILED
    }

    fun retry(state: TabbedTerminalState) {
        bindings[state]?.let { binding ->
            val retry = synchronized(binding) {
                if (!binding.retired && binding.status.value == Status.FAILED) {
                    binding.status.value = Status.CONNECTING
                    true
                } else false
            }
            if (retry) start(state, binding)
        }
    }

    /** Explicit removal queues remote shutdown; it never waits for IPC on the UI thread. */
    fun close(state: TabbedTerminalState) {
        val binding = bindings.remove(state) ?: return
        retire(binding, closeSurface = true)
    }

    fun detach(state: TabbedTerminalState, forUnload: Boolean) {
        val binding = bindings.remove(state) ?: return
        // The external drain waits once after every state has been retired. Never join here
        // while the EDT still has more states to cancel/dispose.
        retire(binding, closeSurface = false)
    }

    private fun retire(binding: Binding, closeSurface: Boolean): Job = synchronized(binding) {
        binding.retired = true
        binding.job?.cancel()
        val job = retirementScope.launch(start = CoroutineStart.LAZY) {
            binding.job?.join()
            if (closeSurface && binding.usesDaemon) {
                repeat(3) { attempt ->
                    try {
                        identityLock(binding.identity).withLock {
                            if (closing[binding.identity] !== binding) return@launch
                            // Even a cancelled initial connect may refer to an existing PTY.
                            // Reconnect on every retry instead of using a dead/stale handle.
                            connect(binding).request("close", binding.identity)
                            closing.remove(binding.identity, binding)
                        }
                        return@launch
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        logFailure("Close", failure)
                        if (attempt < 2) delay(100L * (attempt + 1))
                    }
                }
                // Retain the failed intent. A replacement or another unload attempt can retry;
                // the unload barrier refuses success while any explicit close is unresolved.
            }
        }
        if (closeSurface && binding.usesDaemon) {
            binding.closeJob = job
            closing[binding.identity] = binding
        }
        track(job, binding.windowId)
        job.start()
        job
    }

    private fun track(job: Job, windowId: String) {
        retirements[job] = windowId
        job.invokeOnCompletion { retirements.remove(job) }
    }

    private suspend fun connect(binding: Binding): DaemonServiceConnection {
        val worker = checkNotNull(binding.provider).connect("terminals",
            HostedTerminalDaemonService::class.java.name,
            mapOf("settingsDirectory" to resolvedTerminalSettingsDirectory()))
        check(worker.endpoints["attachProtocol"] == DaemonAttachProtocol.PROTOCOL_VERSION.toString())
        return worker
    }

    /** Bounded external barrier; a failed drain retains the plugin loader for a later retry. */
    fun drainForUnload(windowId: String?, lastWindow: Boolean, timeoutMillis: Long = 5_000) {
        fun matches(id: String) = lastWindow || id == windowId.orEmpty()
        closing.values.filter { matches(it.windowId) && it.closeJob?.isCompleted == true }
            .forEach { retire(it, closeSurface = true) }
        val jobs = retirements.filterValues { matches(it) }.keys.toList()
        awaitTerminalDrain(timeoutMillis) { jobs.forEach { it.join() } }
        if (closing.values.any { matches(it.windowId) }) {
            throw PluginUnloadDeferredException("Background terminal close is pending; retry unload")
        }
    }

    private fun logFailure(operation: String, failure: Exception) {
        logger.warn(LogCategory.TERMINAL, "Hosted terminal $operation failed", mapOf("type" to failure.javaClass.simpleName))
    }
}

internal fun resolvedTerminalSettingsDirectory(): String =
    System.getProperty("bossterm.settings.dir")?.takeIf { it.isNotBlank() }
        ?: bossDataDir("bossterm").absolutePath

/** A failed connection never falls back to a second shell that might repeat the queued command. */
@Composable
internal fun terminalDaemonMode(state: TabbedTerminalState, windowId: String, terminalId: String,
    cwd: String?, command: String?, daemonEnabledOverride: Boolean? = null): Boolean? {
    val binding = remember(state) { HostedTerminalBindings.bind(state, windowId, terminalId, cwd, command,
        daemonEnabled = daemonEnabledOverride ?: SettingsManager.instance.settings.value.daemonEnabled) }
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
