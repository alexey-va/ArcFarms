package ru.ruscrafting.farms.paper.mine.working

import kotlin.math.*

/** The machine may face any heading; only its swept physical footprint limits travel. */
internal object MineDriveMotion {
    const val SPEED = .12
    const val TURN = 3.8f
    private const val HALF_WIDTH = 1.18
    private const val HALF_LENGTH = 1.95
    private const val CENTER_FORWARD = .30

    fun heading(value: Float) = ((value % 360f) + 360f) % 360f
    fun speed(current: Double, throttle: Int): Double {
        val target = throttle * SPEED
        return current + (target - current).coerceIn(-.018, .018)
    }

    /** Rectangle/block SAT for the chassis and tracks. */
    fun footprint(side: Double, forward: Double, relativeHeading: Float): Set<Pair<Int, Int>> {
        return swept(side, forward, relativeHeading, HALF_WIDTH)
    }

    /** Rail v10's cutter clears one extra side block for sleeper service space. */
    fun cutterFootprint(
        side: Double,
        forward: Double,
        relativeHeading: Float,
        version: Int = ru.ruscrafting.farms.domain.MineWorkingPlacement.CURRENT_GEOMETRY_VERSION,
        rail: Boolean = false,
    ): Set<Pair<Int, Int>> = swept(
        side,
        forward,
        relativeHeading,
        if (rail && version >= 10) HALF_WIDTH + 1.0 else HALF_WIDTH,
    )

    private fun swept(side: Double, forward: Double, relativeHeading: Float, halfWidth: Double): Set<Pair<Int, Int>> {
        val angle = Math.toRadians(relativeHeading.toDouble())
        val fx = -sin(angle); val fz = cos(angle)
        val rx = cos(angle); val rz = sin(angle)
        val x = side + fx * CENTER_FORWARD; val z = forward + fz * CENTER_FORWARD
        val ex = abs(rx) * halfWidth + abs(fx) * HALF_LENGTH
        val ez = abs(rz) * halfWidth + abs(fz) * HALF_LENGTH
        return buildSet {
            for (s in floor(x-ex+.5).toInt()..floor(x+ex+.5).toInt())
                for (f in floor(z-ez+.5).toInt()..floor(z+ez+.5).toInt()) {
                    val dx = s-x; val dz = f-z
                    if (abs(dx) < ex+.5 && abs(dz) < ez+.5 &&
                        abs(dx*rx+dz*rz) < halfWidth+.5*(abs(rx)+abs(rz)) &&
                        abs(dx*fx+dz*fz) < HALF_LENGTH+.5*(abs(fx)+abs(fz))) add(s to f)
                }
        }
    }

    /** The whole small owned working is authorized once; checkpoint I/O never gates motion. */
    private val excavationCellsByVersion: Map<Int, Set<Int>> =
        (0..ru.ruscrafting.farms.domain.MineWorkingPlacement.CURRENT_GEOMETRY_VERSION)
            .associateWith { version -> cells(version) }
    val excavationCells: Set<Int>
        get() = excavationCellsByVersion.getValue(ru.ruscrafting.farms.domain.MineWorkingPlacement.CURRENT_GEOMETRY_VERSION)
    private val railCellsByVersion =
        (0..ru.ruscrafting.farms.domain.MineWorkingPlacement.CURRENT_GEOMETRY_VERSION)
            .associateWith { version -> cells(version, true) }
    fun excavationCells(placement: ru.ruscrafting.farms.domain.MineWorkingPlacement, rail: Boolean = false) =
        if (rail) railCellsByVersion.getValue(placement.geometryVersion)
        else excavationCellsByVersion.getValue(placement.geometryVersion)
    private fun cells(version: Int, rail: Boolean = false): Set<Int> = buildSet {
        val routeLength = MineDriveLayout.length(rail, version)
        for (s in -MineDriveLayout.width(version)..MineDriveLayout.width(version))
            for (f in 1 until routeLength) {
                if (MineDriveLayout.driveable(s,f,version,rail)) add(MineDriveLayout.id(s,f,version))
            }
    }
}
