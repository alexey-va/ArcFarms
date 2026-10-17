package ru.ruscrafting.farms.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.collections.immutable.toPersistentMap

/** Immutable roots make durable snapshots O(1); only detached roots reach the ranking worker. */
class ActivityStatsIndex(
    initial: Map<UUID, PlayerActivityStats> = emptyMap(),
    private val currentWeekStartEpochDay: () -> Long = { farmWeekStartEpochDay(System.currentTimeMillis()) },
    private val calculate: (() -> Unit) -> CompletableFuture<Unit> = { action ->
        runCatching(action).fold({ CompletableFuture.completedFuture(Unit) }, CompletableFuture<Unit>::failedFuture)
    },
) {
    private val lock = Any()
    private var values = initial.toPersistentMap()
    private var revision = 0L
    private var epoch = 0L
    private var calculating = false
    private var ranks: Rankings? = null

    private data class RankInput(val values: Map<UUID, PlayerActivityStats>, val revision: Long, val week: Long, val epoch: Long)

    private data class Rankings(
        val revision: Long,
        val week: Long,
        val all: Map<ActivityKind, List<Pair<UUID, Long>>>,
        val weekly: Map<ActivityKind, List<Pair<UUID, Long>>>,
        val farm: Map<UUID, Int>,
        val farmWeekly: Map<UUID, Int>,
    )

    fun replace(snapshot: Map<UUID, PlayerActivityStats>) {
        synchronized(lock) { values = snapshot.toPersistentMap(); revision++; epoch++; ranks = null }
        refresh()
    }

    fun snapshot(): Map<UUID, PlayerActivityStats> = synchronized(lock) { values }
    fun player(playerId: UUID): PlayerActivityStats = synchronized(lock) { values[playerId] ?: PlayerActivityStats() }

    fun contribute(playerId: UUID, kind: ActivityKind, amount: Int) {
        synchronized(lock) {
            values = values.put(playerId, (values[playerId] ?: PlayerActivityStats()).contributeWeekly(kind, amount, currentWeekStartEpochDay()))
            revision++
        }
        refresh()
    }

    fun complete(playerId: UUID, kind: ActivityKind) {
        synchronized(lock) { values = values.put(playerId, (values[playerId] ?: PlayerActivityStats()).complete(kind)) }
    }

    fun leaderboard(kind: ActivityKind, limit: Int = 10): List<Pair<UUID, Long>> {
        refresh()
        return synchronized(lock) { ranks?.all?.get(kind).orEmpty().take(limit.coerceIn(1, 50)) }
    }

    fun farmRank(playerId: UUID): Int? {
        refresh()
        return synchronized(lock) { ranks?.farm?.get(playerId) }
    }

    fun weeklyLeaderboard(kind: ActivityKind, limit: Int = 10): List<Pair<UUID, Long>> {
        refresh()
        return synchronized(lock) { ranks?.takeIf { it.week == currentWeekStartEpochDay() }?.weekly?.get(kind)
            .orEmpty().take(limit.coerceIn(1, 50)) }
    }

    fun farmWeeklyRank(playerId: UUID): Int? {
        refresh()
        return synchronized(lock) { ranks?.takeIf { it.week == currentWeekStartEpochDay() }?.farmWeekly?.get(playerId) }
    }

    fun weeklyContribution(playerId: UUID, kind: ActivityKind): Long = synchronized(lock) {
        values[playerId]?.weeklyContributions.orEmpty()[kind]
            ?.takeIf { it.weekStartEpochDay == currentWeekStartEpochDay() }?.contribution ?: 0L
    }

    private fun refresh() {
        val capture = synchronized(lock) {
            val week = currentWeekStartEpochDay()
            if (calculating || ranks?.let { it.revision == revision && it.week == week } == true) return
            calculating = true
            RankInput(values, revision, week, epoch)
        }
        val (root, capturedRevision, week, capturedEpoch) = capture
        calculate {
            val ordering = compareByDescending<Pair<UUID, Long>> { it.second }.thenBy { it.first.toString() }
            val all = ActivityKind.entries.associateWith { kind ->
                root.entries.mapNotNull { (id, value) -> (value.contributions[kind] ?: 0L).takeIf { it > 0 }?.let { id to it } }
                    .sortedWith(ordering)
            }
            val weekly = ActivityKind.entries.associateWith { kind ->
                root.entries.mapNotNull { (id, value) -> value.weeklyContributions.orEmpty()[kind]
                    ?.takeIf { it.weekStartEpochDay == week && it.contribution > 0 }?.let { id to it.contribution } }
                    .sortedWith(ordering)
            }
            val ranked = Rankings(capturedRevision, week, all, weekly,
                all.getValue(ActivityKind.FARM).mapIndexed { index, pair -> pair.first to index + 1 }.toMap(),
                weekly.getValue(ActivityKind.FARM).mapIndexed { index, pair -> pair.first to index + 1 }.toMap())
            synchronized(lock) {
                // Replacements invalidate older results; contributions may display the last completed revision.
                if (capturedEpoch == epoch && (ranks == null || capturedRevision >= requireNotNull(ranks).revision)) ranks = ranked
            }
        }.whenComplete { _, _ -> synchronized(lock) { calculating = false } }
    }
}

internal fun farmWeekStartEpochDay(
    timestampMillis: Long,
    zoneId: ZoneId = ZoneId.of("Europe/Moscow"),
): Long = Instant.ofEpochMilli(timestampMillis)
    .atZone(zoneId)
    .toLocalDate()
    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    .toEpochDay()
