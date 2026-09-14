package ru.ruscrafting.farms.paper.worksite

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.paper.api.ArcSidebarFrame
import ru.arc.paper.api.ArcSidebarHandle
import java.util.UUID

/** Publishes farm, mine and lumber activity frames to ARC Core's shared sidebar. */
internal class WorksiteSidebarController(
    private val sidebar: ArcSidebarHandle?,
) {
    private val runtimeKeys = mutableMapOf<UUID, String>()

    @Suppress("UNUSED_PARAMETER")
    fun update(player: Player, runtimeKey: String, title: Component, rows: List<Component>, replaceExisting: Boolean) {
        val visibleRows = if (runtimeKey.startsWith("mine:")) SidebarLineWrapper.wrap(rows, MINE_LINE_CHARACTERS) else rows
        require(visibleRows.size <= ArcSidebarFrame.MAX_ROWS) {
            "A Minecraft sidebar supports at most ${ArcSidebarFrame.MAX_ROWS} rows"
        }
        if (visibleRows.isEmpty() || sidebar == null) {
            remove(player)
            return
        }
        runtimeKeys[player.uniqueId] = runtimeKey
        sidebar.show(player, ArcSidebarFrame(title, visibleRows))
    }

    fun reconcile(owner: String, expected: Set<UUID>) {
        runtimeKeys.filter { (id, runtimeKey) -> runtimeKey.substringBefore(':') == owner && id !in expected }
            .keys.toList().forEach(::release)
    }

    fun reconcileKeys(expected: Set<Pair<UUID, String>>) {
        runtimeKeys.filter { (id, runtimeKey) -> id to runtimeKey !in expected }.keys.toList().forEach(::release)
    }

    fun remove(player: Player, owner: String? = null) {
        val runtimeKey = runtimeKeys[player.uniqueId] ?: return
        if (owner != null && runtimeKey.substringBefore(':') != owner) return
        runtimeKeys.remove(player.uniqueId)
        sidebar?.hide(player)
    }

    fun restoreAll() {
        runtimeKeys.keys.toList().forEach(::release)
    }

    private fun release(playerId: UUID) {
        runtimeKeys.remove(playerId)
        sidebar?.hide(playerId)
    }

    private companion object {
        const val MINE_LINE_CHARACTERS = 28
    }
}
