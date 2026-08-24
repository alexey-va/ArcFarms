package ru.ruscrafting.farms.paper

import io.papermc.paper.scoreboard.numbers.NumberFormat
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import ru.ruscrafting.farms.config.FarmScoreboardProvider
import ru.ruscrafting.farms.config.FarmScoreboardSettings
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val FARM_SCOREBOARD_OBJECTIVE = "arcfarms_farm"

internal data class FarmScoreboardSession(
    val zoneId: String,
    val scoreboard: Scoreboard,
    val previous: Scoreboard,
    var view: FarmScoreboardView? = null,
)

internal data class FarmTabScoreboardSnapshot(
    val title: String,
    val lines: List<String>,
)

internal class FarmScoreboardController(
    private val renderer: FarmScoreboardRenderer,
    private val settings: () -> FarmScoreboardSettings,
    private val debug: ArcFarmsDebug,
) {
    private val legacy = LegacyComponentSerializer.legacySection()
    private val sessions = mutableMapOf<UUID, FarmScoreboardSession>()
    private val suppressed = mutableSetOf<UUID>()
    private val tabSnapshots = ConcurrentHashMap<UUID, FarmTabScoreboardSnapshot>()

    fun update(player: Player, zoneId: String, view: FarmScoreboardView) {
        val current = settings()
        if (!current.enabled) {
            remove(player, "disabled")
            return
        }
        tabSnapshots[player.uniqueId] = FarmTabScoreboardSnapshot(
            title = legacy.serialize(renderer.title(player)),
            lines = renderer.rows(view, player).map(legacy::serialize),
        )
        if (current.provider == FarmScoreboardProvider.TAB) {
            restoreBukkit(player, "tab_provider")
            return
        }
        updateBukkit(player, zoneId, view, current)
    }

    fun active(playerId: UUID): Boolean = tabSnapshots.containsKey(playerId)

    fun tabTitle(playerId: UUID): String = tabSnapshots[playerId]?.title.orEmpty()

    fun tabLine(playerId: UUID, line: Int): String = tabSnapshots[playerId]?.lines?.getOrNull(line - 1).orEmpty()

    fun reconcile(expected: Set<UUID>) {
        (tabSnapshots.keys - expected).forEach(tabSnapshots::remove)
        (sessions.keys - expected).toList().forEach { playerId ->
            Bukkit.getPlayer(playerId)?.let { restoreBukkit(it, "not_in_active_farm") }
                ?: sessions.remove(playerId)
        }
        suppressed.retainAll(expected)
    }

    fun remove(player: Player, reason: String) {
        tabSnapshots.remove(player.uniqueId)
        restoreBukkit(player, reason)
    }

    fun restoreAll(reason: String) {
        tabSnapshots.clear()
        sessions.keys.toList().forEach { playerId ->
            Bukkit.getPlayer(playerId)?.let { restoreBukkit(it, reason) }
                ?: sessions.remove(playerId)
        }
        suppressed.clear()
    }

    private fun updateBukkit(
        player: Player,
        zoneId: String,
        view: FarmScoreboardView,
        current: FarmScoreboardSettings,
    ) {
        val playerId = player.uniqueId
        if (playerId in suppressed) return
        var session = sessions[playerId]
        if (session != null && session.zoneId != zoneId) {
            restoreBukkit(player, "changed_zone")
            session = null
        }
        if (session != null && player.scoreboard !== session.scoreboard) {
            sessions.remove(playerId)
            suppressed += playerId
            debug.event("farm_scoreboard_yielded", "player" to player.name, "zone" to zoneId, "reason" to "replaced")
            return
        }
        if (session == null) {
            val previous = player.scoreboard
            if (!current.replaceExisting && previous.getObjective(DisplaySlot.SIDEBAR) != null) {
                suppressed += playerId
                debug.event("farm_scoreboard_yielded", "player" to player.name, "zone" to zoneId, "reason" to "existing")
                return
            }
            val scoreboard = Bukkit.getScoreboardManager().newScoreboard
            scoreboard.registerNewObjective(FARM_SCOREBOARD_OBJECTIVE, Criteria.DUMMY, renderer.title(player)).also { objective ->
                objective.displaySlot = DisplaySlot.SIDEBAR
                objective.numberFormat(NumberFormat.blank())
            }
            session = FarmScoreboardSession(zoneId, scoreboard, previous)
            sessions[playerId] = session
            renderBukkit(player, session, view)
            player.scoreboard = scoreboard
            debug.message("scoreboard", "local", "farm:$zoneId", player, renderer.title(player))
            return
        }
        if (session.view != view) renderBukkit(player, session, view)
    }

    private fun renderBukkit(player: Player, session: FarmScoreboardSession, view: FarmScoreboardView) {
        val objective = requireNotNull(session.scoreboard.getObjective(FARM_SCOREBOARD_OBJECTIVE)) {
            "Farm scoreboard objective disappeared for ${player.name}"
        }
        objective.displayName(renderer.title(player))
        session.scoreboard.entries.toList().forEach(session.scoreboard::resetScores)
        renderer.rows(view, player).forEachIndexed { index, row ->
            objective.getScore("§${index.toString(16)}").also { score ->
                score.customName(row)
                score.score = FarmScoreboardRenderer.MAX_ROWS - index
            }
        }
        session.view = view
        debug.event(
            "farm_scoreboard_updated",
            "player" to player.name,
            "zone" to session.zoneId,
            "order" to view.orderId,
            "phase" to view.phase,
            "progress" to "${view.done}/${view.total}",
            "rows" to session.scoreboard.entries.size,
        )
    }

    private fun restoreBukkit(player: Player, reason: String) {
        val session = sessions.remove(player.uniqueId)
        if (session != null && player.scoreboard === session.scoreboard) {
            player.scoreboard = session.previous
            debug.event("farm_scoreboard_restored", "player" to player.name, "zone" to session.zoneId, "reason" to reason)
        }
        suppressed.remove(player.uniqueId)
    }
}
