package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.DaemonServiceConnection
import ai.rever.boss.plugin.api.DaemonServiceProvider
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
        val provider: DaemonServiceProvider?) {
        val status = MutableStateFlow(Status.CONNECTING)
        @Volatile var job: Job? = null
        @Volatile var closeJob: Job? = null
        @Volatile var worker: DaemonServiceConnection? = null
        var retired = false
    }
    private val logger = BossLogger.forComponent("HostedTerminalBindings")
    private val bindings = ConcurrentHashMap<TabbedTerminalState, Binding>()
    private val closing = ConcurrentHashMap<String, Binding>()
    private val identityLocks = ConcurrentHashMap<String, Mutex>()
    private val retirementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retirements = ConcurrentHashMap<Job, String>()

    fun bind(state: TabbedTerminalState, windowId: String, terminalId: String, cwd: String?, command: String?,
        provider: DaemonServiceProvider? = HostWindows.daemonFor(windowId),
        daemonEnabled: Boolean = SettingsManager.instance.settings.value.daemonEnabled): MutableStateFlow<Status> {
        bindings[state]?.let { return it.status }
        val binding = Binding(windowId, HostWindows.terminalIdentity(windowId, terminalId), cwd, command, provider)
        bindings.putIfAbsent(state, binding)?.let { return it.status }
        if (provider == null || !daemonEnabled) binding.status.value = Status.LOCAL
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
                        identityLocks.computeIfAbsent(binding.identity) { Mutex() }.withLock {
                            val worker = checkNotNull(binding.provider).connect("terminals",
                                HostedTerminalDaemonService::class.java.name,
                                mapOf("settingsDirectory" to resolvedTerminalSettingsDirectory()))
                            binding.worker = worker
                            check(worker.endpoints["attachProtocol"] == DaemonAttachProtocol.PROTOCOL_VERSION.toString())
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
        val job = retire(binding, closeSurface = false)
        if (forUnload) runBlocking(Dispatchers.IO) { job.join() }
    }

    private fun retire(binding: Binding, closeSurface: Boolean): Job = synchronized(binding) {
        binding.retired = true
        binding.job?.cancel()
        val job = retirementScope.launch(start = CoroutineStart.LAZY) {
            binding.job?.join()
            if (closeSurface) {
                try {
                    identityLocks.computeIfAbsent(binding.identity) { Mutex() }.withLock {
                        binding.worker?.request("close", binding.identity)
                        closing.remove(binding.identity, binding)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { logFailure("Close", failure) }
            }
        }
        if (closeSurface) {
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

    /** Only the external unload barrier waits; ordinary tab close and reset remain responsive. */
    fun drainForUnload(windowId: String?, lastWindow: Boolean) {
        val jobs = retirements.filterValues { lastWindow || it == windowId.orEmpty() }.keys.toList()
        runBlocking(Dispatchers.IO) { jobs.forEach { it.join() } }
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
