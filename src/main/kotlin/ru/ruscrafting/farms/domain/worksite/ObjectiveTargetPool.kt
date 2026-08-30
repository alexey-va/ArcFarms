package ru.ruscrafting.farms.domain.worksite

import java.util.UUID

object ObjectiveTargetPool {
    fun plan(
        key: WorksiteObjectiveKey,
        required: Int,
        candidates: List<ObjectiveTargetCandidate>,
    ): WorksiteObjectiveState {
        require(required in 1..100_000) { "Worksite objective quota is invalid" }
        val ordered = distinctCandidates(candidates)
        require(ordered.size >= required) {
            "Worksite objective ${key.objectiveId} requires $required valid targets but found ${ordered.size}"
        }
        val visibleCount = minOf(required * 2, ordered.size)
        val reserveLimit = required * 2
        return WorksiteObjectiveState(
            key = key,
            required = required,
            targets = ordered.take(visibleCount).map { it.availableState() },
            reserve = ordered.drop(visibleCount).take(reserveLimit),
        )
    }

    fun lease(
        current: WorksiteObjectiveState,
        targetId: String,
        playerId: UUID,
        now: Long,
        leaseMillis: Long = 45_000L,
    ): ObjectiveTargetResult {
        require(now >= 0) { "Lease time cannot be negative" }
        require(leaseMillis in 1..3_600_000L) { "Lease duration is invalid" }
        val index = current.targets.indexOfFirst { it.id == targetId }
        if (index < 0 || current.completed >= current.required) return rejected(current)
        val target = current.targets[index]
        val claimable = target.status == ObjectiveTargetStatus.AVAILABLE ||
            (target.status == ObjectiveTargetStatus.LEASED && now >= target.leaseExpiresAt)
        if (!claimable) return rejected(current)
        val updated = target.copy(
            status = ObjectiveTargetStatus.LEASED,
            leasedBy = playerId,
            leaseExpiresAt = saturatingAdd(now, leaseMillis),
        )
        return ObjectiveTargetResult(current.copy(targets = current.targets.replaced(index, updated)), true)
    }

    fun release(current: WorksiteObjectiveState, playerId: UUID): ObjectiveTargetResult {
        var changed = false
        val targets = current.targets.map { target ->
            if (target.status == ObjectiveTargetStatus.LEASED && target.leasedBy == playerId) {
                changed = true
                target.available()
            } else {
                target
            }
        }
        return if (changed) ObjectiveTargetResult(current.copy(targets = targets), true) else rejected(current)
    }

    fun complete(
        current: WorksiteObjectiveState,
        targetId: String,
        playerId: UUID,
    ): ObjectiveTargetResult {
        if (current.completed >= current.required) return rejected(current)
        val index = current.targets.indexOfFirst { it.id == targetId }
        if (index < 0) return rejected(current)
        val target = current.targets[index]
        val accepted = target.status == ObjectiveTargetStatus.AVAILABLE ||
            (target.status == ObjectiveTargetStatus.LEASED && target.leasedBy == playerId)
        if (!accepted) return rejected(current)
        val completed = target.copy(
            status = ObjectiveTargetStatus.COMPLETED,
            leasedBy = null,
            leaseExpiresAt = 0L,
        )
        val contribution = ((current.contributions[playerId] ?: 0).toLong() + 1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return ObjectiveTargetResult(
            current.copy(
                targets = current.targets.replaced(index, completed),
                contributions = current.contributions + (playerId to contribution),
            ),
            accepted = true,
            contribution = 1,
        )
    }

    fun invalidate(
        current: WorksiteObjectiveState,
        targetId: String,
        replacement: ObjectiveTargetCandidate? = null,
    ): ObjectiveTargetResult {
        val index = current.targets.indexOfFirst { it.id == targetId }
        if (index < 0 || current.targets[index].status == ObjectiveTargetStatus.COMPLETED) return rejected(current)
        val candidate = replacement ?: current.reserve.firstOrNull() ?: return rejected(current)
        val remainingTargets = current.targets.filterIndexed { targetIndex, _ -> targetIndex != index }
        if (remainingTargets.any { it.id == candidate.id || it.position == candidate.position }) return rejected(current)
        val nextReserve = current.reserve.filterNot { it.id == candidate.id }
        return ObjectiveTargetResult(
            current.copy(
                targets = current.targets.replaced(index, candidate.availableState()),
                reserve = nextReserve,
            ),
            accepted = true,
        )
    }

    private fun distinctCandidates(candidates: List<ObjectiveTargetCandidate>): List<ObjectiveTargetCandidate> {
        val ids = mutableSetOf<String>()
        val positions = mutableSetOf<WorksitePosition>()
        return candidates
            .sortedWith(compareBy<ObjectiveTargetCandidate>({ it.score }, { it.id }))
            .filter { ids.add(it.id) && positions.add(it.position) }
    }

    private fun ObjectiveTargetCandidate.availableState(): ObjectiveTargetState = ObjectiveTargetState(
        id = id,
        position = position,
        role = role,
        score = score,
    )

    private fun ObjectiveTargetState.available(): ObjectiveTargetState = copy(
        status = ObjectiveTargetStatus.AVAILABLE,
        leasedBy = null,
        leaseExpiresAt = 0L,
    )

    private fun <T> List<T>.replaced(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }

    private fun rejected(current: WorksiteObjectiveState): ObjectiveTargetResult = ObjectiveTargetResult(current, false)

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
