package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Tab data for Terminal tabs.
 *
 * Contains configuration for a terminal tab instance including:
 * - Standard tab properties (id, title, icon)
 * - Terminal-specific properties (initialCommand, workingDirectory)
 *
 * @param id Unique identifier for this tab instance
 * @param title Display title for the tab (defaults to "Terminal")
 * @param icon Tab icon vector (defaults to Terminal icon)
 * @param tabIcon Tab icon wrapper
 * @param initialCommand Optional command to execute on terminal start
 * @param workingDirectory Optional working directory for the terminal
 */
data class TerminalTabData(
    override val id: String,
    override val title: String = "Terminal",
    override val icon: ImageVector = Icons.Filled.Terminal,
    override val tabIcon: TabIcon? = null,
    val initialCommand: String? = null,
    val workingDirectory: String? = null
) : TabInfo {
    override val typeId: TabTypeId = TerminalTabType.typeId

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
