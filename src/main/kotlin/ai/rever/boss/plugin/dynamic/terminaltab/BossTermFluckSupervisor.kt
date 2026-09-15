package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.CustomPluginEvent
import ai.rever.boss.plugin.api.NotificationDuration
import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.bossterm.compose.onboarding.BossTermSetupSupervisor
import ai.rever.bossterm.compose.onboarding.SetupFailure
import ai.rever.bossterm.compose.onboarding.SetupDebugRequest
import ai.rever.bossterm.compose.onboarding.SetupDebugResult
import ai.rever.bossterm.compose.onboarding.SetupRepair
import ai.rever.bossterm.compose.onboarding.SetupTaskState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Event-bus bridge that keeps BossTerm independent from the optional Fluck plugin. */
internal class BossTermFluckSupervisor(private val context: PluginContext) : BossTermSetupSupervisor {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<CustomPluginEvent>>()
    private val debugAccepted = ConcurrentHashMap<String, CompletableDeferred<CustomPluginEvent>>()
    private val debugCompleted = ConcurrentHashMap<String, CompletableDeferred<CustomPluginEvent>>()
    private val subscription: Job? = context.applicationEventBus?.let { bus ->
        context.pluginScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.eventsOfType(CustomPluginEvent::class.java).collect { event ->
                if (event.sourcePluginId != FLUCK_PLUGIN_ID) return@collect
                val requestId = event.payload["requestId"] as? String ?: return@collect
                when (event.eventName) {
                    EVENT_DEBUG_ACCEPTED -> debugAccepted.remove(requestId)?.complete(event)
                    EVENT_DEBUG_COMPLETED -> debugCompleted.remove(requestId)?.complete(event)
                }
                pending.remove(requestId)?.complete(event)
            }
        }
    }

    override suspend fun start(sessionId: String, tasks: List<SetupTaskState>): Boolean {
        if (subscription == null) return false
        val reply = request(
            eventName = EVENT_PROBE,
            payload = mapOf(
                "sessionId" to sessionId,
                "tasks" to tasks.joinToString(",") { it.id },
            ),
            timeoutMs = PROBE_TIMEOUT_MS,
        ) ?: return false
        return reply.eventName == EVENT_READY && reply.payload["available"] == true
    }

    override suspend fun taskChanged(sessionId: String, task: SetupTaskState) {
        publish(
            EVENT_PROGRESS,
            mapOf(
                "sessionId" to sessionId,
                "taskId" to task.id,
                "taskTitle" to task.title,
                "status" to task.status.name,
            ),
        )
    }

    override suspend fun repair(failure: SetupFailure): SetupRepair {
        val reply = request(
            eventName = EVENT_FAILURE,
            payload = mapOf(
                "sessionId" to failure.sessionId,
                "taskId" to failure.taskId,
                "taskTitle" to failure.taskTitle,
                "attempt" to failure.attempt,
                "exitCode" to failure.exitCode,
                "output" to failure.output,
                "platform" to failure.platform.name,
            ),
            timeoutMs = REPAIR_TIMEOUT_MS,
        ) ?: return SetupRepair.STOP
        return when (reply.payload["action"] as? String) {
            "retry" -> SetupRepair.RETRY
            "refresh_packages_and_retry" -> SetupRepair.REFRESH_PACKAGES_AND_RETRY
            else -> SetupRepair.STOP
        }
    }

    override suspend fun finish(sessionId: String, success: Boolean, message: String?) {
        publish(EVENT_FINISHED, mapOf("sessionId" to sessionId, "success" to success, "message" to message))
        context.notificationProvider?.showToast(
            message = if (success) "Your terminal setup is ready." else (message ?: "Terminal setup needs attention."),
            type = if (success) NotificationType.SUCCESS else NotificationType.WARNING,
            duration = NotificationDuration.LONG,
            title = "BOSS Term setup",
        )
    }

    override suspend fun debugAndFix(request: SetupDebugRequest, onAccepted: () -> Unit): SetupDebugResult {
        if (subscription == null) return SetupDebugResult(false, "Fluck debugging is unavailable")
        val accepted = CompletableDeferred<CustomPluginEvent>()
        val completed = CompletableDeferred<CustomPluginEvent>()
        debugAccepted[request.requestId] = accepted
        debugCompleted[request.requestId] = completed
        publish(
            EVENT_DEBUG_REQUEST,
            mapOf(
                "requestId" to request.requestId,
                "sessionId" to request.sessionId,
                "terminalId" to request.terminalId,
                "windowId" to context.windowId,
                "taskId" to request.task.id,
                "taskTitle" to request.task.title,
                "status" to request.task.status.name,
                "output" to request.output,
                "expiresAtMs" to (System.currentTimeMillis() + DEBUG_ACK_TIMEOUT_MS),
            ),
        )
        return try {
            val ack = withTimeoutOrNull(DEBUG_ACK_TIMEOUT_MS) { accepted.await() }
                ?: return SetupDebugResult(false, "Fluck did not accept the debugging request")
            if (ack.payload["accepted"] != true) {
                return SetupDebugResult(false, ack.payload["error"] as? String ?: "Fluck debugging is unavailable")
            }
            onAccepted()
            val result = withTimeoutOrNull(DEBUG_COMPLETION_TIMEOUT_MS) { completed.await() }
                ?: return SetupDebugResult(false, "Fluck debugging timed out")
            SetupDebugResult(
                completed = result.payload["completed"] == true,
                error = result.payload["error"] as? String,
            )
        } finally {
            debugAccepted.remove(request.requestId)
            debugCompleted.remove(request.requestId)
        }
    }

    private suspend fun request(
        eventName: String,
        payload: Map<String, Any?>,
        timeoutMs: Long,
    ): CustomPluginEvent? {
        val requestId = UUID.randomUUID().toString()
        val response = CompletableDeferred<CustomPluginEvent>()
        pending[requestId] = response
        publish(eventName, payload + ("requestId" to requestId))
        return try {
            withTimeoutOrNull(timeoutMs) { response.await() }
        } finally {
            pending.remove(requestId)
        }
    }

    private fun publish(eventName: String, payload: Map<String, Any?>) {
        runCatching {
            context.applicationEventBus?.publish(CustomPluginEvent(TERMINAL_PLUGIN_ID, eventName, payload))
        }
    }

    companion object {
        const val TERMINAL_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.terminaltab"
        const val FLUCK_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.fluckagent"
        const val EVENT_PROBE = "bossterm.setup.supervision.probe"
        const val EVENT_READY = "bossterm.setup.supervision.ready"
        const val EVENT_PROGRESS = "bossterm.setup.supervision.progress"
        const val EVENT_FAILURE = "bossterm.setup.supervision.failure"
        const val EVENT_REPAIR = "bossterm.setup.supervision.repair"
        const val EVENT_FINISHED = "bossterm.setup.supervision.finished"
        const val EVENT_DEBUG_REQUEST = "bossterm.setup.debug.request"
        const val EVENT_DEBUG_ACCEPTED = "bossterm.setup.debug.accepted"
        const val EVENT_DEBUG_COMPLETED = "bossterm.setup.debug.completed"
        private const val PROBE_TIMEOUT_MS = 1_500L
        private const val REPAIR_TIMEOUT_MS = 45_000L
        private const val DEBUG_ACK_TIMEOUT_MS = 5_000L
        private const val DEBUG_COMPLETION_TIMEOUT_MS = 10 * 60_000L
    }
}

/** One plugin-lifetime bridge shared by every Compose entry point. */
internal object TerminalPluginContextHolder {
    @Volatile
    var setupSupervisor: BossTermFluckSupervisor? = null
}
