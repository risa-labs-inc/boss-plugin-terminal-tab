package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.ipc.proto.services.CellAttr
import ai.rever.boss.ipc.proto.services.CellRun
import ai.rever.boss.ipc.proto.services.CellStyle
import ai.rever.boss.ipc.proto.services.CursorShape as ProtoCursorShape
import ai.rever.boss.ipc.proto.services.CursorState as ProtoCursorState
import ai.rever.boss.ipc.proto.services.TerminalGridDelta
import ai.rever.bossterm.core.Color
import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.ProcessTtyConnector
import ai.rever.bossterm.terminal.TerminalDataStream
import ai.rever.bossterm.terminal.TextStyle
import ai.rever.bossterm.terminal.TtyBasedArrayDataStream
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalLine
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.model.TextBufferChangesListener
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * One live terminal session inside the OOP terminal plugin's child JVM.
 *
 * Owns a [PtyProcess] + the bossterm-core pipeline (`ProcessTtyConnector`,
 * `TtyBasedArrayDataStream`, `BossEmulator`, `BossTerminal`,
 * `TerminalTextBuffer`) and produces:
 *
 *  - [gridFrames]: a hot flow of [TerminalGridDelta] frames the host can
 *    apply to its [ai.rever.boss.terminal.render.GridState].
 *  - [cursorFrames]: cursor position/shape updates.
 *  - [keystrokes]: input bytes the host wants written to the PTY.
 *
 * v1 emits **full-redraw** frames coalesced at ~20 fps. The per-line
 * `IncrementalSnapshotBuilder`-based delta path is a follow-up — v1's
 * focus is "the wire works end-to-end".
 */
class TerminalSession(
    val sessionId: String,
    workingDirectory: String?,
    command: List<String>,
    environment: Map<String, String>,
    initialCols: Int,
    initialRows: Int,
    parentScope: CoroutineScope,
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger("TerminalSession[$sessionId]")
    private val scope = CoroutineScope(parentScope.coroutineContext + Job())

    private val styleState = StyleState()
    private val textBuffer = TerminalTextBuffer(
        initialCols.coerceAtLeast(MIN_COLS),
        initialRows.coerceAtLeast(MIN_ROWS),
        styleState,
        SCROLLBACK_LINES,
    )
    private val display = HeadlessTerminalDisplay()
    private val terminal: BossTerminal = BossTerminal(display, textBuffer, styleState)

    private val ptyProcess: PtyProcess
    private val connector: ProcessTtyConnector
    private val dataStream: TerminalDataStream
    private val emulator: BossEmulator

    private val revision = AtomicLong(0L)
    private val gridFlow = MutableSharedFlow<TerminalGridDelta>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val cursorFlow = MutableStateFlow(emitInitialCursor())

    val gridFrames: Flow<TerminalGridDelta> get() = gridFlow.asSharedFlow()
    val cursorFrames: Flow<ProtoCursorState> get() = cursorFlow.asStateFlow()

    /** Lifecycle: true once the PTY has exited or the session has been closed. */
    @Volatile private var closed: Boolean = false

    init {
        // Spawn the PTY. pty4j auto-extracts native libs to a temp directory
        // if `pty4j.tmpdir` isn't set; the OOP child JVM doesn't inherit the
        // host's pty4j system properties, but the default behaviour is fine
        // for typical desktop installs.
        val envWithDefaults = HashMap(System.getenv()).apply { putAll(environment) }
        val cwd = workingDirectory?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
        ptyProcess = PtyProcessBuilder()
            .setCommand(command.toTypedArray())
            .setEnvironment(envWithDefaults)
            .setDirectory(cwd)
            .setConsole(false)
            .setInitialColumns(textBuffer.width)
            .setInitialRows(textBuffer.height)
            .start()

        connector = object : ProcessTtyConnector(
            ptyProcess,
            StandardCharsets.UTF_8,
            command.toMutableList(),
        ) {
            override val name: String = "terminal-tab:$sessionId"
            override fun resize(termSize: TermSize) {
                try {
                    ptyProcess.winSize = WinSize(termSize.columns, termSize.rows)
                } catch (e: Exception) {
                    logger.warn("Failed to set PTY winsize: {}", e.message)
                }
            }
            override fun close() {
                ptyProcess.destroy()
            }
        }

        dataStream = TtyBasedArrayDataStream(connector)
        emulator = BossEmulator(dataStream, terminal)

        // Subscribe to text-buffer changes so we know when to emit a frame.
        textBuffer.addChangesListener(object : TextBufferChangesListener {
            override fun linesChanged(fromIndex: Int) = scheduleFrame()
            override fun linesDiscardedFromHistory(lines: List<TerminalLine>) = scheduleFrame()
            override fun historyCleared() = scheduleFrame()
            override fun widthResized() = scheduleFrame()
        })

        // Run the emulator loop.
        scope.launch(Dispatchers.IO) {
            try {
                while (ptyProcess.isAlive && !closed) {
                    emulator.processChar(dataStream.char, terminal)
                }
            } catch (_: TerminalDataStream.EOF) {
                // Normal exit.
            } catch (e: Exception) {
                logger.warn("Emulator loop terminated: {}", e.message)
            } finally {
                logger.info("PTY process exited (alive={})", ptyProcess.isAlive)
                emitFrame() // Final frame so host sees the closing state.
            }
        }

        // Coalesced frame emitter: any number of `scheduleFrame()` calls
        // produces at most one frame per FRAME_INTERVAL_MS.
        scope.launch(Dispatchers.Default) {
            while (!closed) {
                if (framePending.compareAndSet(true, false)) {
                    runCatching { emitFrame() }.onFailure {
                        logger.warn("Frame emission failed", it)
                    }
                }
                kotlinx.coroutines.delay(FRAME_INTERVAL_MS)
            }
        }

        // Push the initial state so subscribers receive a baseline frame.
        emitFrame()
    }

    /** Write raw bytes to the PTY input (for SendInput RPC). */
    fun sendInput(data: ByteArray) {
        try {
            connector.write(data)
        } catch (e: Exception) {
            logger.warn("PTY write failed: {}", e.message)
        }
    }

    /** Resize the PTY + cell grid. */
    fun resize(cols: Int, rows: Int) {
        val safeCols = cols.coerceAtLeast(MIN_COLS)
        val safeRows = rows.coerceAtLeast(MIN_ROWS)
        val size = TermSize(safeCols, safeRows)
        try {
            terminal.resize(size, ai.rever.bossterm.terminal.RequestOrigin.User)
        } catch (e: Exception) {
            logger.warn("Terminal resize failed: {}", e.message)
        }
        scheduleFrame()
    }

    override fun close() {
        if (closed) return
        closed = true
        try { ptyProcess.destroy() } catch (_: Exception) {}
        scope.cancel()
    }

    // ─── Frame production ──────────────────────────────────────────────

    private val framePending = java.util.concurrent.atomic.AtomicBoolean(true)

    private fun scheduleFrame() { framePending.set(true) }

    private fun emitFrame() {
        val cols = textBuffer.width
        val rows = textBuffer.height
        val builder = TerminalGridDelta.newBuilder()
            .setSessionId(sessionId)
            .setRevision(revision.incrementAndGet())
            .setCols(cols)
            .setRows(rows)
            .setIsFullRedraw(true)
            .setIsAlternateBuffer(textBuffer.isUsingAlternateBuffer)

        for (row in 0 until rows) {
            appendLineRuns(builder, row, cols)
        }
        gridFlow.tryEmit(builder.build())
        publishCursor()
    }

    private fun appendLineRuns(
        builder: TerminalGridDelta.Builder,
        row: Int,
        cols: Int,
    ) {
        val line: TerminalLine = textBuffer.getLine(row)
        // Pragmatic v1: treat the entire row as one CellRun using the line's
        // text + a default style. Proper per-style-segment grouping needs to
        // walk `TextEntries`; that's a follow-up so v1 ships visible text
        // without colour fidelity beyond the host's defaults.
        val text = line.text.let { raw ->
            // Pad/trim to `cols` so the host's mirror matches the buffer.
            when {
                raw.length >= cols -> raw.substring(0, cols)
                else -> raw + " ".repeat(cols - raw.length)
            }
        }
        val run = CellRun.newBuilder()
            .setRow(row)
            .setCol(0)
            .setText(text)
            .also { rb ->
                // ASCII-only fast path: each grapheme is one codepoint is one
                // char. Non-ASCII rows will render with imperfect cell-width
                // alignment until per-grapheme segmentation is added.
                for (i in text.indices) rb.addGraphemeStarts(i)
            }
            .setStyle(CellStyle.getDefaultInstance())
            .build()
        builder.addRowsChanged(run)
    }

    private fun publishCursor() {
        val pos = display.cursorPosition()
        cursorFlow.update {
            ProtoCursorState.newBuilder()
                .setSessionId(sessionId)
                .setRow(pos.y)
                .setCol(pos.x)
                .setVisible(display.isCursorVisible())
                .setShape(mapCursorShape(display.cursorShapeOrDefault()))
                .setBlink(display.cursorShapeOrDefault().isBlinking)
                .build()
        }
    }

    private fun emitInitialCursor(): ProtoCursorState =
        ProtoCursorState.newBuilder()
            .setSessionId(sessionId)
            .setRow(0)
            .setCol(0)
            .setVisible(true)
            .setShape(ProtoCursorShape.CURSOR_SHAPE_BLOCK)
            .setBlink(true)
            .build()

    private fun mapCursorShape(shape: CursorShape): ProtoCursorShape = when (shape) {
        CursorShape.BLINK_BLOCK, CursorShape.STEADY_BLOCK -> ProtoCursorShape.CURSOR_SHAPE_BLOCK
        CursorShape.BLINK_UNDERLINE, CursorShape.STEADY_UNDERLINE ->
            ProtoCursorShape.CURSOR_SHAPE_UNDERLINE
        CursorShape.BLINK_VERTICAL_BAR, CursorShape.STEADY_VERTICAL_BAR ->
            ProtoCursorShape.CURSOR_SHAPE_BAR
    }

    companion object {
        private const val MIN_COLS = 1
        private const val MIN_ROWS = 1
        private const val SCROLLBACK_LINES = 10_000
        private const val FRAME_INTERVAL_MS = 50L // ~20 fps
    }
}
