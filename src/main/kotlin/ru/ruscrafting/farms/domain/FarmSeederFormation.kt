package ru.ruscrafting.farms.domain

import kotlin.math.hypot

/** A bounded world position occupied by one working animal in the field rig. */
data class FarmMachinePosition(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(DomainIdentifiers.isWorld(world)) { "Farm machine world is invalid" }
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Farm machine position is invalid" }
    }
}

/** Plans a horse-drawn row with evenly spaced working animals ahead of the rider. */
object FarmSeederFormation {
    fun pigPositions(
        world: String,
        horseX: Double,
        horseY: Double,
        horseZ: Double,
        directionX: Double,
        directionZ: Double,
        pigCount: Int,
        leadDistance: Double,
        spacing: Double,
    ): List<FarmMachinePosition> {
        require(pigCount in 1..5) { "Farm seeder pig count must be in 1..5" }
        require(leadDistance.isFinite() && leadDistance in 1.0..6.0) {
            "Farm seeder lead distance must be in 1..6"
        }
        require(spacing.isFinite() && spacing in 0.6..3.0) { "Farm seeder pig spacing must be in 0.6..3" }
        val length = hypot(directionX, directionZ)
        val forwardX = if (length > MIN_DIRECTION_LENGTH) directionX / length else 0.0
        val forwardZ = if (length > MIN_DIRECTION_LENGTH) directionZ / length else 1.0
        val rightX = -forwardZ
        val rightZ = forwardX
        val center = (pigCount - 1) / 2.0
        return List(pigCount) { index ->
            val lateral = (index - center) * spacing
            FarmMachinePosition(
                world = world,
                x = horseX + forwardX * leadDistance + rightX * lateral,
                y = horseY,
                z = horseZ + forwardZ * leadDistance + rightZ * lateral,
            )
        }
    }

    private const val MIN_DIRECTION_LENGTH = 1.0e-6
}
