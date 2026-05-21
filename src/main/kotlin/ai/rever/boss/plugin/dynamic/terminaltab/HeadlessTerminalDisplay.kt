package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.core.Color
import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.RequestOrigin
import ai.rever.bossterm.terminal.TerminalDisplay
import ai.rever.bossterm.terminal.emulator.mouse.MouseFormat
import ai.rever.bossterm.terminal.emulator.mouse.MouseMode
import ai.rever.bossterm.terminal.model.TerminalSelection
import java.util.concurrent.atomic.AtomicReference

/**
 * Headless [TerminalDisplay] for the OOP terminal plugin's child JVM.
 *
 * In the in-process world, `ComposeTerminalDisplay` is what wired the
 * `BossTerminal` engine to a Compose canvas. In the OOP world, rendering
 * lives in the host. The child only needs to remember whatever the engine
 * sets so the gRPC streams can include it — cursor position, cursor shape,
 * cursor visibility, alt-buffer state, window title.
 *
 * All visual concerns (scrolling animation, color palettes, redraw
 * triggers) are no-ops here. The host renders deltas from
 * [ai.rever.bossterm.terminal.model.TerminalTextBuffer] directly.
 */
class HeadlessTerminalDisplay : TerminalDisplay {
    private val cursorPos = AtomicReference(CursorPosition(0, 0))
    @Volatile private var cursorShape: CursorShape? = null
    @Volatile private var cursorVisible = true
    @Volatile private var altBuffer = false
    @Volatile private var bracketedPasteMode = false
    @Volatile private var mouseMode: MouseMode = MouseMode.MOUSE_REPORTING_NONE
    @Volatile private var _windowTitle: String? = null
    @Volatile private var _iconTitle: String? = null
    @Volatile private var _selection: TerminalSelection? = null
    @Volatile private var _ambiguousDoubleWidth: Boolean = false

    data class CursorPosition(val x: Int, val y: Int)

    fun cursorPosition(): CursorPosition = cursorPos.get()
    fun cursorShapeOrDefault(): CursorShape = cursorShape ?: CursorShape.STEADY_BLOCK
    fun isCursorVisible(): Boolean = cursorVisible
    fun isAlternateBuffer(): Boolean = altBuffer

    override fun setCursor(x: Int, y: Int) {
        cursorPos.set(CursorPosition(x, y))
    }

    override fun setCursorShape(cursorShape: CursorShape?) {
        this.cursorShape = cursorShape
    }

    override fun beep() {
        // No-op: bells are host UI concern.
    }

    override fun onResize(newTermSize: TermSize, origin: RequestOrigin) {
        // No-op: resize is initiated by the host via gRPC; the engine reads
        // its own buffer dimensions.
    }

    override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) {
        // No-op: scrolling is reflected in the cell grid the host streams.
    }

    override fun setCursorVisible(isCursorVisible: Boolean) {
        this.cursorVisible = isCursorVisible
    }

    override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) {
        this.altBuffer = useAlternateScreenBuffer
    }

    override var windowTitle: String?
        get() = _windowTitle
        set(value) { _windowTitle = value }

    override var iconTitle: String?
        get() = _iconTitle
        set(value) { _iconTitle = value }

    override val selection: TerminalSelection?
        get() = _selection

    override fun terminalMouseModeSet(mouseMode: MouseMode) {
        this.mouseMode = mouseMode
    }

    override fun setMouseFormat(mouseFormat: MouseFormat) {
        // No-op: mouse encoding is handled host-side.
    }

    override fun ambiguousCharsAreDoubleWidth(): Boolean = _ambiguousDoubleWidth

    override fun setBracketedPasteMode(bracketedPasteModeEnabled: Boolean) {
        this.bracketedPasteMode = bracketedPasteModeEnabled
    }

    override val windowForeground: Color? get() = null
    override val windowBackground: Color? get() = null
    override var cursorColor: Color? = null
}
