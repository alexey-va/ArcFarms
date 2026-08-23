package ru.ruscrafting.farms.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * Owns the mutable statistics index behind both gameplay writes and
 * PlaceholderAPI reads. Placeholder consumers are not guaranteed to run on
 * the server thread, so no mutable collection escapes this lock.
 */
class ActivityStatsIndex(
    initial: Map<UUID, PlayerActivityStats> = emptyMap(),
    private val currentWeekStartEpochDay: () -> Long = {
        farmWeekStartEpochDay(System.currentTimeMillis())
    },
) {
    private val lock = Any()
    private var values = initial.toMutableMap()
    private var farmLeaderboardCache: List<Pair<UUID, Long>>? = null
    private var farmWeeklyLeaderboardCache: Pair<Long, List<Pair<UUID, Long>>>? = null

    fun replace(snapshot: Map<UUID, PlayerActivityStats>) = synchronized(lock) {
        values = snapshot.toMutableMap()
        farmLeaderboardCache = null
        farmWeeklyLeaderboardCache = null
    }

    fun snapshot(): Map<UUID, PlayerActivityStats> = synchronized(lock) { values.toMap() }

    fun player(playerId: UUID): PlayerActivityStats = synchronized(lock) {
        values[playerId] ?: PlayerActivityStats()
    }

    fun contribute(playerId: UUID, kind: ActivityKind, amount: Int) = synchronized(lock) {
        values[playerId] = (values[playerId] ?: PlayerActivityStats()).contributeWeekly(
            kind,
            amount,
            currentWeekStartEpochDay(),
        )
        if (kind == ActivityKind.FARM && amount > 0) {
            farmLeaderboardCache = null
            farmWeeklyLeaderboardCache = null
        }
    }

    fun complete(playerId: UUID, kind: ActivityKind) = synchronized(lock) {
        values[playerId] = (values[playerId] ?: PlayerActivityStats()).complete(kind)
    }

    fun leaderboard(kind: ActivityKind, limit: Int = 10): List<Pair<UUID, Long>> = synchronized(lock) {
        val ranked = if (kind == ActivityKind.FARM) {
            farmLeaderboardCache ?: rank(ActivityKind.FARM).also { farmLeaderboardCache = it }
        } else {
            rank(kind)
        }
        ranked.take(limit.coerceIn(1, 50))
    }

    fun farmRank(playerId: UUID): Int? = synchronized(lock) {
        val ranked = farmLeaderboardCache ?: rank(ActivityKind.FARM).also { farmLeaderboardCache = it }
        ranked.indexOfFirst { it.first == playerId }.takeIf { it >= 0 }?.plus(1)
    }

    fun weeklyLeaderboard(kind: ActivityKind, limit: Int = 10): List<Pair<UUID, Long>> = synchronized(lock) {
        val weekStart = currentWeekStartEpochDay()
        val ranked = if (kind == ActivityKind.FARM) {
            farmWeeklyLeaderboardCache
                ?.takeIf { it.first == weekStart }
                ?.second
                ?: rankWeekly(ActivityKind.FARM, weekStart).also {
                    farmWeeklyLeaderboardCache = weekStart to it
                }
        } else {
            rankWeekly(kind, weekStart)
        }
        ranked.take(limit.coerceIn(1, 50))
    }

    fun farmWeeklyRank(playerId: UUID): Int? = synchronized(lock) {
        val weekStart = currentWeekStartEpochDay()
        val ranked = farmWeeklyLeaderboardCache
            ?.takeIf { it.first == weekStart }
            ?.second
            ?: rankWeekly(ActivityKind.FARM, weekStart).also {
                farmWeeklyLeaderboardCache = weekStart to it
            }
        ranked.indexOfFirst { it.first == playerId }.takeIf { it >= 0 }?.plus(1)
    }

    fun weeklyContribution(playerId: UUID, kind: ActivityKind): Long = synchronized(lock) {
        values[playerId]
            ?.weeklyContributions
            .orEmpty()[kind]
            ?.takeIf { it.weekStartEpochDay == currentWeekStartEpochDay() }
            ?.contribution
            ?: 0L
    }

    private fun rank(kind: ActivityKind): List<Pair<UUID, Long>> = values.entries
        .map { it.key to (it.value.contributions[kind] ?: 0L) }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<UUID, Long>> { it.second }.thenBy { it.first.toString() })

    private fun rankWeekly(kind: ActivityKind, weekStartEpochDay: Long): List<Pair<UUID, Long>> = values.entries
        .mapNotNull { (playerId, stats) ->
            stats.weeklyContributions
                .orEmpty()[kind]
                ?.takeIf { it.weekStartEpochDay == weekStartEpochDay && it.contribution > 0 }
                ?.let { playerId to it.contribution }
        }
        .sortedWith(compareByDescending<Pair<UUID, Long>> { it.second }.thenBy { it.first.toString() })
}

internal fun farmWeekStartEpochDay(
    timestampMillis: Long,
    zoneId: ZoneId = ZoneId.of("Europe/Moscow"),
): Long = Instant.ofEpochMilli(timestampMillis)
    .atZone(zoneId)
    .toLocalDate()
    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    .toEpochDay()
