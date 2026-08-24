package ru.ruscrafting.farms.paper

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import ru.ruscrafting.farms.domain.ActivityKind
import java.util.UUID

class ArcFarmsPlaceholderExpansion(
    private val version: String,
    private val service: ArcFarmsService,
) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "arcfarms"
    override fun getAuthor(): String = "RusCrafting"
    override fun getVersion(): String = version
    override fun persist(): Boolean = true
    override fun canRegister(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        when (val request = FarmScoreboardPlaceholder.parse(params)) {
            FarmScoreboardPlaceholder.Active -> return player?.uniqueId
                ?.let(service::farmScoreboardActive)
                ?.toString() ?: "false"
            FarmScoreboardPlaceholder.Title -> return player?.uniqueId?.let(service::farmScoreboardTitle).orEmpty()
            is FarmScoreboardPlaceholder.Line -> return player?.uniqueId
                ?.let { service.farmScoreboardLine(it, request.number) }
                .orEmpty()
            null -> Unit
        }
        return when (val request = FarmLeaderboardPlaceholder.parse(params)) {
            FarmLeaderboardPlaceholder.PersonalScore -> player?.uniqueId?.let {
                service.playerStats(it).contributions[ActivityKind.FARM] ?: 0L
            }?.toString() ?: "0"
            FarmLeaderboardPlaceholder.PersonalRank -> player?.uniqueId?.let(service::leaderboardRank)?.toString() ?: ""
            FarmLeaderboardPlaceholder.PersonalWeeklyScore -> player?.uniqueId?.let {
                service.weeklyContribution(it, ActivityKind.FARM)
            }?.toString() ?: "0"
            FarmLeaderboardPlaceholder.PersonalWeeklyRank ->
                player?.uniqueId?.let(service::weeklyLeaderboardRank)?.toString() ?: ""
            is FarmLeaderboardPlaceholder.Top -> {
                val entry = service.leaderboard(ActivityKind.FARM, request.rank).getOrNull(request.rank - 1) ?: return ""
                renderTop(entry, request.field)
            }
            is FarmLeaderboardPlaceholder.WeeklyTop -> {
                val entry = service.weeklyLeaderboard(ActivityKind.FARM, request.rank)
                    .getOrNull(request.rank - 1) ?: return ""
                renderTop(entry, request.field)
            }
            null -> null
        }
    }

    private fun renderTop(entry: Pair<UUID, Long>, field: FarmLeaderboardPlaceholder.Field): String {
        val name = Bukkit.getOfflinePlayer(entry.first).name
        return when (field) {
            FarmLeaderboardPlaceholder.Field.NAME -> name ?: entry.first.toString().take(8)
            FarmLeaderboardPlaceholder.Field.SKIN -> name ?: entry.first.toString()
            FarmLeaderboardPlaceholder.Field.UUID -> entry.first.toString()
            FarmLeaderboardPlaceholder.Field.SCORE -> entry.second.toString()
        }
    }
}

internal sealed interface FarmScoreboardPlaceholder {
    data object Active : FarmScoreboardPlaceholder
    data object Title : FarmScoreboardPlaceholder
    data class Line(val number: Int) : FarmScoreboardPlaceholder

    companion object {
        private val LINE = Regex("farm_line_([1-9]|1[0-5])")

        fun parse(raw: String): FarmScoreboardPlaceholder? = when (val normalized = raw.lowercase()) {
            "farm_active" -> Active
            "farm_title" -> Title
            else -> LINE.matchEntire(normalized)?.groupValues?.get(1)?.toIntOrNull()?.let(::Line)
        }
    }
}

internal sealed interface FarmLeaderboardPlaceholder {
    data object PersonalScore : FarmLeaderboardPlaceholder
    data object PersonalRank : FarmLeaderboardPlaceholder
    data object PersonalWeeklyScore : FarmLeaderboardPlaceholder
    data object PersonalWeeklyRank : FarmLeaderboardPlaceholder
    data class Top(val rank: Int, val field: Field) : FarmLeaderboardPlaceholder
    data class WeeklyTop(val rank: Int, val field: Field) : FarmLeaderboardPlaceholder

    enum class Field { NAME, SKIN, UUID, SCORE }

    companion object {
        private val TOP = Regex("farm_top_([1-9][0-9]?)_(name|skin|uuid|score)")
        private val WEEKLY_TOP = Regex("farm_weekly_top_([1-9][0-9]?)_(name|skin|uuid|score)")

        fun parse(raw: String): FarmLeaderboardPlaceholder? {
            val normalized = raw.lowercase()
            if (normalized == "farm_score") return PersonalScore
            if (normalized == "farm_rank") return PersonalRank
            if (normalized == "farm_weekly_score") return PersonalWeeklyScore
            if (normalized == "farm_weekly_rank") return PersonalWeeklyRank
            WEEKLY_TOP.matchEntire(normalized)?.let { match ->
                val rank = match.groupValues[1].toInt()
                if (rank !in 1..50) return null
                return WeeklyTop(rank, Field.valueOf(match.groupValues[2].uppercase()))
            }
            val match = TOP.matchEntire(normalized) ?: return null
            val rank = match.groupValues[1].toInt()
            if (rank !in 1..50) return null
            return Top(rank, Field.valueOf(match.groupValues[2].uppercase()))
        }
    }
}
