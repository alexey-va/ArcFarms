package ru.ruscrafting.farms.domain.worksite

import ru.ruscrafting.farms.domain.DomainIdentifiers
import java.util.UUID

data class WorksiteObjectiveKey(
    val zoneId: String,
    val objectiveId: String,
    val sequence: Long,
) {
    init {
        require(DomainIdentifiers.isOrder(zoneId)) { "Invalid worksite zone id: $zoneId" }
        require(DomainIdentifiers.isOrder(objectiveId)) { "Invalid worksite objective id: $objectiveId" }
        require(sequence >= 0) { "Worksite objective sequence cannot be negative" }
    }
}

data class WorksitePosition(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    init {
        require(DomainIdentifiers.isWorld(world)) { "Invalid worksite world: $world" }
        require(x in -30_000_000..30_000_000 && z in -30_000_000..30_000_000) {
            "Worksite position is outside the world border"
        }
        require(y in -2_048..2_048) { "Worksite position height is invalid" }
    }
}

data class ObjectiveTargetRole(val value: String) {
    init {
        require(DomainIdentifiers.isOrder(value)) { "Invalid objective target role: $value" }
    }
}

data class ObjectiveTargetCandidate(
    val id: String,
    val position: WorksitePosition,
    val role: ObjectiveTargetRole,
    val score: Long,
) {
    init {
        require(DomainIdentifiers.isOrder(id)) { "Invalid objective target id: $id" }
    }
}

enum class ObjectiveTargetStatus {
    AVAILABLE,
    LEASED,
    COMPLETED,
}

data class ObjectiveTargetState(
    val id: String,
    val position: WorksitePosition,
    val role: ObjectiveTargetRole,
    val score: Long,
    val status: ObjectiveTargetStatus = ObjectiveTargetStatus.AVAILABLE,
    val leasedBy: UUID? = null,
    val leaseExpiresAt: Long = 0L,
) {
    init {
        require(DomainIdentifiers.isOrder(id)) { "Invalid objective target id: $id" }
        require((status == ObjectiveTargetStatus.LEASED) == (leasedBy != null)) {
            "Only leased targets may have an owner"
        }
        require(status == ObjectiveTargetStatus.LEASED || leaseExpiresAt == 0L) {
            "Only leased targets may have an expiry"
        }
    }

    fun candidate(): ObjectiveTargetCandidate = ObjectiveTargetCandidate(id, position, role, score)
}

data class WorksiteObjectiveState(
    val key: WorksiteObjectiveKey,
    val required: Int,
    val targets: List<ObjectiveTargetState>,
    val reserve: List<ObjectiveTargetCandidate> = emptyList(),
    val contributions: Map<UUID, Int> = emptyMap(),
) {
    init {
        require(required in 1..100_000) { "Worksite objective quota is invalid" }
        require(targets.size >= required) { "Worksite objective has fewer targets than its quota" }
        require(targets.map(ObjectiveTargetState::id).distinct().size == targets.size) {
            "Worksite objective contains duplicate target ids"
        }
        require(targets.map(ObjectiveTargetState::position).distinct().size == targets.size) {
            "Worksite objective contains duplicate target positions"
        }
        require(reserve.map(ObjectiveTargetCandidate::id).distinct().size == reserve.size) {
            "Worksite objective contains duplicate reserve ids"
        }
    }

    val completed: Int
        get() = targets.count { it.status == ObjectiveTargetStatus.COMPLETED }.coerceAtMost(required)

    fun target(id: String): ObjectiveTargetState? = targets.firstOrNull { it.id == id }
}

data class ObjectiveTargetResult(
    val state: WorksiteObjectiveState,
    val accepted: Boolean,
    val contribution: Int = 0,
)
