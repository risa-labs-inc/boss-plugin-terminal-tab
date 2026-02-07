package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TerminalTabInfoInterface
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Tab data for Terminal tabs.
 *
 * Contains configuration for a terminal tab instance including:
 * - Standard tab properties (id, title, icon)
 * - Terminal-specific properties (initialCommand, workingDirectory)
 *
 * Implements [TerminalTabInfoInterface] to allow access to terminal-specific
 * properties through a common interface with the host's TerminalTabInfo.
 *
 * @param id Unique identifier for this tab instance
 * @param typeId Tab type identifier (matches bundled terminal tab)
 * @param title Display title for the tab (defaults to "Terminal")
 * @param icon Tab icon vector (defaults to Terminal icon)
 * @param tabIcon Tab icon wrapper (non-nullable with default)
 * @param initialCommand Optional command to execute on terminal start
 * @param workingDirectory Optional working directory for the terminal
 */
data class TerminalTabData(
    override val id: String,
    override val typeId: TabTypeId = TerminalTabType.typeId,
    override val title: String = "Terminal",
    override val icon: ImageVector = TerminalTabType.icon,
    override val tabIcon: TabIcon = TabIcon.Vector(icon),
    override val initialCommand: String? = null,
    override val workingDirectory: String? = null
) : TerminalTabInfoInterface {

    companion object {
        /** Maximum length for terminal tab titles */
        const val MAX_TITLE_LENGTH = 64
    }

    /**
     * Returns a copy of this tab data with an updated title.
     * Used when the terminal title changes (e.g., via escape sequences).
     * Title is truncated to [MAX_TITLE_LENGTH] characters.
     */
    fun updateTitle(newTitle: String): TerminalTabData {
        val truncatedTitle = if (newTitle.length > MAX_TITLE_LENGTH) {
            newTitle.take(MAX_TITLE_LENGTH)
        } else {
            newTitle
        }
        return copy(title = truncatedTitle)
    }
}
