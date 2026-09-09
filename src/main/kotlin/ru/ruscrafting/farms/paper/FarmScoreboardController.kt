package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.FarmScoreboardProvider
import ru.ruscrafting.farms.config.FarmScoreboardSettings
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class FarmTabScoreboardSnapshot(
    val title: String,
    val lines: List<String>,
)

internal interface FarmScoreboardPort {
    fun update(player: Player, zoneId: String, view: FarmScoreboardView)
    fun active(playerId: UUID): Boolean
    fun tabTitle(playerId: UUID): String
    fun tabLine(playerId: UUID, line: Int): String
    fun reconcile(expected: Set<UUID>)
    fun remove(player: Player, reason: String)
    fun restoreAll(reason: String)
}

internal class FarmScoreboardController(
    private val renderer: FarmScoreboardRenderer,
    private val settings: () -> FarmScoreboardSettings,
    private val audience: ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort,
) : FarmScoreboardPort {
    private val legacy = LegacyComponentSerializer.legacySection()
    private val tabSnapshots = ConcurrentHashMap<UUID, FarmTabScoreboardSnapshot>()

    override fun update(player: Player, zoneId: String, view: FarmScoreboardView) {
        val current = settings()
        if (!current.enabled) {
            remove(player, "disabled")
            return
        }
        val title = renderer.title(player)
        val rows = renderer.rows(view, player)
        val rendered = FarmTabScoreboardSnapshot(legacy.serialize(title), rows.map(legacy::serialize))
        tabSnapshots[player.uniqueId] = rendered
        if (current.provider == FarmScoreboardProvider.TAB) {
            audience.removeSidebar(player, "farm")
            return
        }
        audience.updateSidebar(player, "farm:$zoneId", title, rows)
    }

    override fun active(playerId: UUID): Boolean =
        settings().provider == FarmScoreboardProvider.TAB && tabSnapshots.containsKey(playerId)

    override fun tabTitle(playerId: UUID): String = tabSnapshots[playerId]?.title.orEmpty()

    override fun tabLine(playerId: UUID, line: Int): String = tabSnapshots[playerId]?.lines?.getOrNull(line - 1).orEmpty()

    override fun reconcile(expected: Set<UUID>) {
        (tabSnapshots.keys - expected).forEach(tabSnapshots::remove)
        audience.reconcileSidebars("farm", expected)
    }

    override fun remove(player: Player, reason: String) {
        tabSnapshots.remove(player.uniqueId)
        audience.removeSidebar(player, "farm")
    }

    override fun restoreAll(reason: String) {
        tabSnapshots.clear()
        audience.reconcileSidebars("farm", emptySet())
    }
}
