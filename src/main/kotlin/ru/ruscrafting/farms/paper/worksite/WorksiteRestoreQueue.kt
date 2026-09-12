package ru.ruscrafting.farms.paper.worksite

import java.util.TreeSet

/**
 * Bounded in-memory view of delayed worksite repairs.
 *
 * Durable journals remain the source of truth. This queue only keeps one
 * scheduled entry per key, so repeated retries replace an existing tree node
 * instead of leaving stale heap entries behind.
 */
internal class WorksiteRestoreQueue<K, E>(
    private val keyOf: (E) -> K,
    private val restoreAtOf: (E) -> Long,
) {
    private data class Scheduled<E>(
        val value: E,
        val restoreAt: Long,
        val sequence: Long,
    )

    private val due = TreeSet<Scheduled<E>>(compareBy<Scheduled<E>> { it.restoreAt }.thenBy { it.sequence })
    private val scheduled = HashMap<K, Scheduled<E>>()
    private var nextSequence = 0L

    fun schedule(value: E) {
        val key = keyOf(value)
        val restoreAt = restoreAtOf(value)
        val current = scheduled[key]
        // The earlier deadline wins. Equal deadlines replace the value so a
        // retry cannot retain a stale payload for the same position.
        if (current != null && current.restoreAt < restoreAt) return
        if (current != null) {
            check(due.remove(current)) { "Restore queue lost its scheduled entry" }
        }
        val replacement = Scheduled(value, restoreAt, nextSequence++)
        check(due.add(replacement)) { "Restore queue could not schedule an entry" }
        scheduled[key] = replacement
    }

    fun pollDue(now: Long, limit: Int): List<E> = buildList {
        require(limit >= 1) { "Restore batch size must be positive" }
        while (size < limit) {
            val next = due.firstOrNull() ?: break
            if (next.restoreAt > now) break
            due.remove(next)
            scheduled.remove(keyOf(next.value))
            add(next.value)
        }
    }

    fun clear() {
        due.clear()
        scheduled.clear()
    }

    internal val size: Int get() = scheduled.size
}
