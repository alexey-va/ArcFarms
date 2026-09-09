package ru.ruscrafting.farms.paper.worksite

import io.papermc.paper.scoreboard.numbers.NumberFormat
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import java.util.UUID

/** One native sidebar owner across activities; TAB 6 detects its objective packets and yields automatically. */
internal class WorksiteSidebarController {
    private data class Session(
        var runtimeKey: String,
        val board: Scoreboard,
        val previous: Scoreboard,
        var rows: List<Component> = emptyList(),
    )

    private val sessions = mutableMapOf<UUID, Session>()

    fun update(player: Player, runtimeKey: String, title: Component, rows: List<Component>, replaceExisting: Boolean) {
        require(rows.size <= MAX_ROWS) { "A Minecraft sidebar supports at most $MAX_ROWS rows" }
        if (rows.isEmpty()) {
            remove(player)
            return
        }
        var session = sessions[player.uniqueId]
        if (session != null && player.scoreboard !== session.board) {
            sessions.remove(player.uniqueId)
            session = null
        }
        if (session == null) {
            val previous = player.scoreboard
            if (!replaceExisting && previous.getObjective(DisplaySlot.SIDEBAR) != null) return
            val board = Bukkit.getScoreboardManager().newScoreboard
            board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, title).also {
                it.displaySlot = DisplaySlot.SIDEBAR
                it.numberFormat(NumberFormat.blank())
            }
            session = Session(runtimeKey, board, previous)
            sessions[player.uniqueId] = session
        }
        session.runtimeKey = runtimeKey
        val objective = requireNotNull(session.board.getObjective(OBJECTIVE))
        if (objective.displayName() != title) objective.displayName(title)
        // Stable entries allow duplicate/blank rows. Shrinking deletes only vanished rows.
        for (index in rows.size until session.rows.size) session.board.resetScores(entry(index))
        rows.forEachIndexed { index, row ->
            if (session.rows.getOrNull(index) != row) {
                objective.getScore(entry(index)).also {
                    it.customName(row)
                    it.score = MAX_ROWS - index
                }
            }
        }
        session.rows = rows.toList()
        if (player.scoreboard !== session.board) player.scoreboard = session.board
    }

    fun reconcile(owner: String, expected: Set<UUID>) {
        sessions.filter { (id, session) -> session.runtimeKey.substringBefore(':') == owner && id !in expected }
            .keys.toList().forEach(::release)
    }

    fun reconcileKeys(expected: Set<Pair<UUID, String>>) {
        sessions.filter { (id, session) -> id to session.runtimeKey !in expected }.keys.toList().forEach(::release)
    }

    fun remove(player: Player, owner: String? = null) {
        val session = sessions[player.uniqueId] ?: return
        if (owner != null && session.runtimeKey.substringBefore(':') != owner) return
        sessions.remove(player.uniqueId)
        // Removing the objective is also the signal that lets proxy TAB resume its sidebar.
        if (player.scoreboard === session.board) player.scoreboard = session.previous
    }

    fun restoreAll() = sessions.keys.toList().forEach(::release)

    private fun release(playerId: UUID) {
        Bukkit.getPlayer(playerId)?.let { remove(it) } ?: sessions.remove(playerId)
    }

    private fun entry(index: Int) = "§${index.toString(16)}"

    private companion object {
        const val OBJECTIVE = "arcfarms_work"
        const val MAX_ROWS = 15
    }
}
