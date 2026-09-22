package ru.ruscrafting.farms.domain

import ru.ruscrafting.farms.domain.worksite.WorksitePosition

/** Coordinates refer to the floor at the entrance, facing into the rock. */
data class MineWorkingPlacement(
    val entrance: WorksitePosition,
    val direction: Int,
    val floorId: String,
    /** Stable geometry seed, retained across scene reconstruction. */
    val layoutSeed: Long = 0L,
    val geometryVersion: Int = CURRENT_GEOMETRY_VERSION,
) {
    init { validate() }

    fun validate() {
        require(direction in 0..3)
        require(floorId.isNotBlank() && floorId.length <= 64)
        require(geometryVersion in 0..CURRENT_GEOMETRY_VERSION)
    }

    fun position(side: Int, up: Int, forward: Int): WorksitePosition {
        val (dx, dz) = when (direction) {
            0 -> side to forward
            1 -> -forward to side
            2 -> -side to -forward
            else -> forward to -side
        }
        return entrance.copy(x = entrance.x + dx, y = entrance.y + up, z = entrance.z + dz)
    }

    companion object { const val CURRENT_GEOMETRY_VERSION = 9 }
}

enum class MineWorkingStage {
    EXCAVATE, SUPPORT, CLEAR_TRACK, LAY_TRACK, TEST_TRACK,
    LOAD, CRUSH, HEAT, SHIP,
}

data class MineWorkingState(
    val placement: MineWorkingPlacement,
    val stage: MineWorkingStage,
    val completed: Set<Int> = emptySet(),
    val batch: Int = 0,
    val heatStartedAt: Long = 0,
    val drive: MineDriveProgress? = null,
) {
    init { validate() }

    fun validate() {
        placement.validate()
        drive?.validate(placement.geometryVersion)
        require(completed.size <= 256 && completed.all { it in 0..255 })
        require(batch in 0 until MineWorkingEngine.BATCHES)
        require(heatStartedAt >= 0)
        require(stage == MineWorkingStage.HEAT || heatStartedAt == 0L)
    }
}

/** Cell ids reference the replayable drilling ground, never arbitrary world coordinates. */
data class MineDriveProgress(
    val carved: Set<Int> = emptySet(),
    val lamps: Set<Int> = emptySet(),
    val checkpoint: Int = 42,
    val heading: Float = 0f,
    /** Durable permission to excavate within the already journalled scene; not yet carved. */
    val prepared: Set<Int> = emptySet(),
    val rail: MineRailProgress? = null,
) {
    fun validate(geometryVersion: Int = MineWorkingPlacement.CURRENT_GEOMETRY_VERSION) {
        val cells=if(geometryVersion>=7) 1485 else 765
        rail?.validate(cells)
        require(prepared.size <= cells && prepared.all { it in 0 until cells })
        require(carved.size <= cells && carved.all { it in 0 until cells })
        require(lamps.size <= 256 && lamps.all { it in carved })
        require(checkpoint in 0 until cells && heading.isFinite())
    }
}

data class MineWorkingStep(val state: MineWorkingState, val accepted: Boolean, val finished: Boolean = false)

/** Monotonic physical actions; retries and duplicate clicks never count twice. */
object MineWorkingEngine {
    const val BATCHES = 3
    const val CRUSH_STROKES = 3
    const val HEAT_MILLIS = 4_000L
    const val HEAT_WINDOW_MILLIS = 4_000L

    fun initial(type: MineIncidentType, placement: MineWorkingPlacement): MineWorkingState = MineWorkingState(
        placement,
        when (type) {
            MineIncidentType.TUNNEL_DRIVE -> MineWorkingStage.EXCAVATE
            MineIncidentType.RAIL_EXTENSION -> if (placement.geometryVersion >= 9) MineWorkingStage.EXCAVATE else MineWorkingStage.CLEAR_TRACK
            MineIncidentType.TRACK_DAMAGE -> MineWorkingStage.CLEAR_TRACK
            MineIncidentType.ORE_WORKSHOP -> MineWorkingStage.LOAD
            else -> error("Not a lateral working incident: $type")
        },
    )

    fun completeTarget(current: MineWorkingState, target: Int, total: Int, now: Long): MineWorkingStep =
        complete(current, target, total, now, controlledHeat = false)

    /** The fixed workshop has an air-control furnace rather than the legacy timed quench window. */
    fun completeWorkshopHeat(current: MineWorkingState, heat: MineWorkshopHeat, now: Long): MineWorkingStep =
        if (current.stage == MineWorkingStage.HEAT && heat.ready) complete(current, 0, 1, now, controlledHeat = true)
        else MineWorkingStep(current, false)

    private fun complete(current: MineWorkingState, target: Int, total: Int, now: Long, controlledHeat: Boolean): MineWorkingStep {
        require(total in 1..256 && now >= 0L)
        if (target !in 0 until total || target in current.completed) return MineWorkingStep(current, false)
        // Rails and the test cart advance along one continuous path.
        if (current.stage in setOf(MineWorkingStage.LAY_TRACK, MineWorkingStage.TEST_TRACK) &&
            target != current.completed.size) return MineWorkingStep(current, false)
        if (current.stage == MineWorkingStage.HEAT && !controlledHeat && !canQuench(current, now)) return MineWorkingStep(current, false)
        val next = current.copy(completed = current.completed + target)
        if (next.completed.size < total) return MineWorkingStep(next, true)
        val nextStage = when (current.stage) {
            MineWorkingStage.EXCAVATE -> MineWorkingStage.SUPPORT
            MineWorkingStage.CLEAR_TRACK -> MineWorkingStage.LAY_TRACK
            MineWorkingStage.LAY_TRACK -> MineWorkingStage.TEST_TRACK
            MineWorkingStage.LOAD -> MineWorkingStage.CRUSH
            MineWorkingStage.CRUSH -> MineWorkingStage.HEAT
            MineWorkingStage.HEAT -> MineWorkingStage.SHIP
            MineWorkingStage.SHIP -> if (current.batch + 1 < BATCHES) MineWorkingStage.LOAD else null
            MineWorkingStage.SUPPORT, MineWorkingStage.TEST_TRACK -> null
        }
        if (nextStage == null) return MineWorkingStep(next, true, finished = true)
        return MineWorkingStep(current.copy(
            stage = nextStage, completed = emptySet(),
            batch = current.batch + if (current.stage == MineWorkingStage.SHIP) 1 else 0,
            heatStartedAt = if (nextStage == MineWorkingStage.HEAT) now else 0L,
        ), true)
    }

    fun canQuench(current: MineWorkingState, now: Long): Boolean =
        current.stage == MineWorkingStage.HEAT && now - current.heatStartedAt in HEAT_MILLIS..(HEAT_MILLIS + HEAT_WINDOW_MILLIS)

    /** Missed heat windows cool down automatically and start another attempt without losing the batch. */
    fun reheat(current: MineWorkingState, now: Long): MineWorkingState =
        if (current.stage == MineWorkingStage.HEAT && now - current.heatStartedAt > HEAT_MILLIS + HEAT_WINDOW_MILLIS)
            current.copy(heatStartedAt = now) else current

    fun supports(type: MineIncidentType): Boolean = type in setOf(
        MineIncidentType.TUNNEL_DRIVE, MineIncidentType.RAIL_EXTENSION, MineIncidentType.TRACK_DAMAGE, MineIncidentType.ORE_WORKSHOP,
    )
}
