package ru.ruscrafting.farms.domain

import java.util.UUID

/**
 * Owns the mutable statistics index behind both gameplay writes and
 * PlaceholderAPI reads. Placeholder consumers are not guaranteed to run on
 * the server thread, so no mutable collection escapes this lock.
 */
class ActivityStatsIndex(initial: Map<UUID, PlayerActivityStats> = emptyMap()) {
    private val lock = Any()
    private var values = initial.toMutableMap()
    private var farmLeaderboardCache: List<Pair<UUID, Long>>? = null

    fun replace(snapshot: Map<UUID, PlayerActivityStats>) = synchronized(lock) {
        values = snapshot.toMutableMap()
        farmLeaderboardCache = null
    }

    fun snapshot(): Map<UUID, PlayerActivityStats> = synchronized(lock) { values.toMap() }

    fun player(playerId: UUID): PlayerActivityStats = synchronized(lock) {
        values[playerId] ?: PlayerActivityStats()
    }

    fun contribute(playerId: UUID, kind: ActivityKind, amount: Int) = synchronized(lock) {
        values[playerId] = (values[playerId] ?: PlayerActivityStats()).contribute(kind, amount)
        if (kind == ActivityKind.FARM && amount > 0) farmLeaderboardCache = null
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

    private fun rank(kind: ActivityKind): List<Pair<UUID, Long>> = values.entries
        .map { it.key to (it.value.contributions[kind] ?: 0L) }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<UUID, Long>> { it.second }.thenBy { it.first.toString() })
}
