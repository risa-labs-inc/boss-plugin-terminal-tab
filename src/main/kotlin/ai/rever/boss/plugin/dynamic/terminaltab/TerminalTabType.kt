package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal

/**
 * Terminal tab type info (Dynamic Plugin)
 *
 * This tab type provides a terminal emulator session
 * using BossTerm library.
 */
object TerminalTabType : TabTypeInfo {
    override val typeId = TabTypeId("terminal")
    override val displayName = "Terminal"
    override val icon = Icons.Filled.Terminal
}
