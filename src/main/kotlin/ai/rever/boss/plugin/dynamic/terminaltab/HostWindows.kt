package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.SplitViewOperations

/**
 * The BossConsole windows this plugin was registered in, and the way into each one's host
 * operations through the plugin API.
 *
 * **Why this exists.** This plugin used to reach host internals by reflection -
 * `DeepLinkHandler`, `TerminalLinkEventBus`, `WindowFocusManager`, `RunnerTerminalService`.
 * Since BOSS 9.5.25 (BossConsole `dc443ee9c`, "confine classloader parent delegation to shared
 * packages") the plugin classloader refuses every host class outside its shared packages, so
 * each of those `Class.forName` calls throws `ClassNotFoundException`: the `cli` tool reported
 * "dispatcher unavailable", `run_in_sidebar` found no window, and clicking a link in a terminal
 * did nothing. The boundary is deliberate (a plugin must not resolve host credentials or process
 * control by class name), so the fix is to stop reaching past it, not to widen it.
 *
 * **What replaces each hop.**
 * - `DeepLinkHandler.processDeepLink`: [dispatchBossLink]. The host's
 *   `SplitViewOperations.openUrlInActivePanel` hands any `boss://` URL to that same method
 *   (`routePluginDeepLink`, BossConsole 9.5.x), so every link keeps the origin it always had:
 *   unstated, which the host treats as external and confirms where that matters.
 * - `WindowFocusManager`: [noteFocused], fed by the terminal composables, which can see their own
 *   window's focus. The host publishes no `WindowFocusEvent` on the plugin bus, so the API cannot
 *   answer "which window is focused" by itself; a window that has never shown a terminal falls
 *   back to the most recently registered one.
 * - `TerminalLinkEventBus`: [splitViewFor] the clicking terminal's own window.
 *
 * Plugin contexts are window-scoped (`PluginContext.windowId`), and whether the host registers
 * this plugin once or once per window, every context it was given is kept here by window.
 */
internal object HostWindows {
    /** Key for a context that did not say which window it belongs to. */
    private const val UNKNOWN_WINDOW = ""

    private val lock = Any()

    /** Registration order is kept: the last entry is the most recently registered window. */
    private val contexts = LinkedHashMap<String, PluginContext>()
    private val surfaceSlots = mutableMapOf<Pair<String, String>, Int>()

    @Volatile
    private var lastFocused: String? = null

    fun register(context: PluginContext) {
        val key = context.windowId ?: UNKNOWN_WINDOW
        synchronized(lock) {
            contexts.remove(key)
            contexts[key] = context
        }
    }

    fun unregister(context: PluginContext): Boolean =
        synchronized(lock) {
            val removed = contexts.filterValues { it === context }.keys
            surfaceSlots.keys.removeAll { it.second in removed }
            terminalModes.keys.removeAll { it.first in removed }
            contexts.entries.removeAll { it.value === context }
            contexts.isEmpty()
        }

    /** A terminal in [windowId] saw its window gain focus. */
    fun noteFocused(windowId: String) {
        lastFocused = windowId
    }

    /**
     * The window a host action with no window of its own should land in: the last one a
     * terminal saw focused, if it is still registered, else the most recently registered.
     */
    fun targetWindowId(): String? =
        synchronized(lock) {
            lastFocused?.takeIf { it in contexts }
                ?: contexts.keys.lastOrNull { it != UNKNOWN_WINDOW }
                // Only a context that named no window can vouch for a composable's window id; a
                // closed window is never returned.
                ?: lastFocused?.takeIf { UNKNOWN_WINDOW in contexts }
        }

    /** [windowId]'s split-view operations, or the target window's when it has none of its own. */
    fun splitViewFor(windowId: String?): SplitViewOperations? =
        synchronized(lock) {
            val context =
                windowId?.let { contexts[it] }
                    ?: targetWindowId()?.let { contexts[it] }
                    ?: contexts[UNKNOWN_WINDOW]
                    ?: contexts.values.lastOrNull()
            context?.splitViewOperations
        }

    private val terminalModes = mutableMapOf<Pair<String, String>, Pair<Int, Boolean>>()

    fun terminalMode(windowId: String, terminalId: String, generation: Int, select: () -> Boolean): Boolean = synchronized(lock) {
        val key = windowId to terminalId
        val previous = terminalModes[key]
        if (previous?.first == generation) previous.second
        else select().also { terminalModes[key] = generation to it }
    }

    fun daemonFor(windowId: String): ai.rever.boss.plugin.api.DaemonServiceProvider? = synchronized(lock) {
        (contexts[windowId] ?: contexts[UNKNOWN_WINDOW])?.daemonServiceProvider
    }

    /** Reopening a workspace claims its first free surface slot; live sibling windows stay isolated. */
    fun terminalIdentity(windowId: String, terminalId: String): String = synchronized(lock) {
        val context = contexts[windowId] ?: contexts[UNKNOWN_WINDOW]
        val workspace = context?.workspaceDataProvider?.currentWorkspace?.value
        val workspaceKey = workspace?.id?.takeIf { it.isNotBlank() }
            ?: workspace?.projectPath?.takeIf { it.isNotBlank() } ?: "default"
        val key = workspaceKey to windowId
        val slot = surfaceSlots.getOrPut(key) {
            val occupied = surfaceSlots.filterKeys { it.first == workspaceKey }.values.toSet()
            generateSequence(0) { it + 1 }.first { it !in occupied }
        }
        // Hash length-prefixed fields: identities are bounded and disclose no local paths.
        val identity = "${workspaceKey.length}:$workspaceKey:$slot:$terminalId"
        "terminal:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * Hand a `boss://` link to the host's deep-link dispatcher through the plugin API. False
     * when no registered window has split-view operations, so nothing could take the link.
     */
    fun dispatchBossLink(uri: String): Boolean {
        require(uri.startsWith("boss://", ignoreCase = true)) { "not a boss:// link" }
        val operations = splitViewFor(null) ?: return false
        operations.openUrlInActivePanel(uri, uri)
        return true
    }

    /** Forget everything; tests only. */
    internal fun resetForTest() {
        synchronized(lock) { contexts.clear(); surfaceSlots.clear(); terminalModes.clear() }
        lastFocused = null
    }
}
