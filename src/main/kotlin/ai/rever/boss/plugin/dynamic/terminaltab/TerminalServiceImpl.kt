package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.CloseSessionRequest
import ai.rever.boss.ipc.proto.services.CreateSessionRequest
import ai.rever.boss.ipc.proto.services.CreateSessionResponse
import ai.rever.boss.ipc.proto.services.CursorState
import ai.rever.boss.ipc.proto.services.ListSessionsResponse
import ai.rever.boss.ipc.proto.services.ResizeRequest
import ai.rever.boss.ipc.proto.services.SendCompositionRequest
import ai.rever.boss.ipc.proto.services.SendInputRequest
import ai.rever.boss.ipc.proto.services.SendKeyEventRequest
import ai.rever.boss.ipc.proto.services.SendMouseEventRequest
import ai.rever.boss.ipc.proto.services.SetThemeRequest
import ai.rever.boss.ipc.proto.services.ShellEvent
import ai.rever.boss.ipc.proto.services.StreamCursorRequest
import ai.rever.boss.ipc.proto.services.StreamGridRequest
import ai.rever.boss.ipc.proto.services.StreamOutputRequest
import ai.rever.boss.ipc.proto.services.StreamScrollbackRequest
import ai.rever.boss.ipc.proto.services.StreamShellEventsRequest
import ai.rever.boss.ipc.proto.services.TerminalGridDelta
import ai.rever.boss.ipc.proto.services.TerminalOutputChunk
import ai.rever.boss.ipc.proto.services.TerminalServiceGrpcKt
import ai.rever.boss.ipc.proto.services.TerminalSessionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side implementation of the IPC v1.1.0 `TerminalService`. Hosted by
 * the OOP terminal plugin's child JVM; consumed by BossConsole's
 * `TerminalGridConnection` on the host side.
 *
 * v1 scope:
 *  - PTY creation and lifecycle (`createSession`, `closeSession`, `resize`,
 *    `listSessions`).
 *  - Grid + cursor streaming (`streamGrid`, `streamCursor`) backed by
 *    [TerminalSession] frames.
 *  - Raw byte input (`sendInput`) — host's pastes / programmatic writes.
 *  - Modifier-aware key input (`sendKeyEvent`) — translated to PTY bytes
 *    via the `text` field (proper escape-sequence encoding for special
 *    keys is a follow-up).
 *
 * Deferred to follow-ups:
 *  - `streamShellEvents` (OSC-133 wiring through `CommandStateListener`).
 *  - `streamScrollback` (windowed history range).
 *  - `streamOutput` (legacy byte-pipe semantics; pre-v1.1.0 callers).
 *  - `sendComposition`, `sendMouseEvent`, `setTheme` — proto-wired, child
 *    accepts and ignores until the host actually drives them.
 */
class TerminalServiceImpl(
    private val pluginScope: CoroutineScope,
) : TerminalServiceGrpcKt.TerminalServiceCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(TerminalServiceImpl::class.java)
    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    override suspend fun createSession(request: CreateSessionRequest): CreateSessionResponse {
        val sessionId = UUID.randomUUID().toString()
        return try {
            val session = TerminalSession(
                sessionId = sessionId,
                workingDirectory = request.workingDirectory.takeIf { it.isNotBlank() },
                command = request.commandList.ifEmpty { defaultShellCommand() },
                environment = request.environmentMap.toMap(),
                initialCols = request.cols.takeIf { it > 0 } ?: DEFAULT_COLS,
                initialRows = request.rows.takeIf { it > 0 } ?: DEFAULT_ROWS,
                parentScope = pluginScope,
            )
            sessions[sessionId] = session
            logger.info("Created session {} (cmd={})", sessionId, request.commandList)
            CreateSessionResponse.newBuilder()
                .setSessionId(sessionId)
                .setSuccess(true)
                .build()
        } catch (e: Exception) {
            logger.error("createSession failed", e)
            CreateSessionResponse.newBuilder()
                .setSessionId("")
                .setSuccess(false)
                .setErrorMessage(e.message ?: e.javaClass.simpleName)
                .build()
        }
    }

    override suspend fun closeSession(request: CloseSessionRequest): Empty {
        sessions.remove(request.sessionId)?.close()
        return Empty.getDefaultInstance()
    }

    override suspend fun listSessions(request: Empty): ListSessionsResponse {
        val builder = ListSessionsResponse.newBuilder()
        for ((id, _) in sessions) {
            builder.addSessions(
                TerminalSessionInfo.newBuilder()
                    .setSessionId(id)
                    .setIsAlive(true)
                    .build(),
            )
        }
        return builder.build()
    }

    override suspend fun resize(request: ResizeRequest): Empty {
        sessions[request.sessionId]?.resize(request.cols, request.rows)
        return Empty.getDefaultInstance()
    }

    override suspend fun sendInput(request: SendInputRequest): Empty {
        sessions[request.sessionId]?.sendInput(request.data.toByteArray())
        return Empty.getDefaultInstance()
    }

    override suspend fun sendKeyEvent(request: SendKeyEventRequest): Empty {
        if (!request.isPress) return Empty.getDefaultInstance()
        // v1 only forwards the text payload as PTY bytes. Special keys
        // (arrows, function keys, etc.) need ANSI escape encoding —
        // follow-up work using `TerminalKeyEncoder` from bossterm-core.
        val text = request.text
        if (text.isEmpty()) return Empty.getDefaultInstance()
        sessions[request.sessionId]?.sendInput(text.toByteArray(Charsets.UTF_8))
        return Empty.getDefaultInstance()
    }

    override suspend fun sendComposition(request: SendCompositionRequest): Empty {
        // Accepted on the wire; not driven yet (host-side IME pipeline pending).
        return Empty.getDefaultInstance()
    }

    override suspend fun sendMouseEvent(request: SendMouseEventRequest): Empty {
        // Accepted on the wire; not driven yet (host-side pointer capture pending).
        return Empty.getDefaultInstance()
    }

    override suspend fun setTheme(request: SetThemeRequest): Empty {
        // Accepted on the wire; child currently emits engine-resolved colours
        // and lets the host apply its own palette. Wire-up follow-up.
        return Empty.getDefaultInstance()
    }

    override fun streamGrid(request: StreamGridRequest): Flow<TerminalGridDelta> {
        val session = sessions[request.sessionId]
            ?: return emptyFlow()
        return session.gridFrames
    }

    override fun streamCursor(request: StreamCursorRequest): Flow<CursorState> {
        val session = sessions[request.sessionId]
            ?: return emptyFlow()
        return session.cursorFrames
    }

    override fun streamScrollback(request: StreamScrollbackRequest): Flow<ai.rever.boss.ipc.proto.services.ScrollbackDelta> {
        // v1: no scrollback streaming. The grid stream's full-redraw frames
        // include any visible scroll position; an explicit windowed history
        // RPC is a follow-up once line-version diffing is wired.
        return emptyFlow()
    }

    override fun streamShellEvents(request: StreamShellEventsRequest): Flow<ShellEvent> {
        // v1: no shell events. CommandStateListener wiring is a follow-up.
        return emptyFlow()
    }

    override fun streamOutput(request: StreamOutputRequest): Flow<TerminalOutputChunk> {
        // Legacy byte-pipe RPC from the pre-v1.1.0 contract. Not used by the
        // new grid pipeline; not implemented here.
        return emptyFlow()
    }

    /** Shut down every session — call when the plugin is unloaded. */
    fun shutdownAll() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    private fun defaultShellCommand(): List<String> {
        val shell = System.getenv("SHELL")
            ?: if (isWindows()) "cmd.exe" else "/bin/sh"
        return listOf(shell)
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("windows")

    companion object {
        private const val DEFAULT_COLS = 80
        private const val DEFAULT_ROWS = 24
    }
}
