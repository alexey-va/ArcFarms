package ru.ruscrafting.farms.paper.farm.incident.action

import org.bukkit.Location
import org.bukkit.World
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRaidFlight
import ru.ruscrafting.farms.paper.platform.FarmRaidFlightEnvelope
import ru.ruscrafting.farms.paper.platform.FarmRaidFlightSpace
import kotlin.math.hypot
import kotlin.math.min

/** Session-owned obstacle avoidance and recovery policy for the autonomous raid ghast. */
internal class FarmRivalRaidFlightNavigator(
    private val space: FarmRaidFlightSpace,
) {
    data class Plan(
        /** Null means no physically clear short route is available, so the ghast should wait. */
        val steeringTarget: FarmPointPosition?,
        /** A verified clear endpoint; crossing intervening collision is allowed only for this teleport. */
        val recoveryTarget: FarmPointPosition? = null,
        val blocked: Boolean = false,
    )

    private var lastAssessmentTick = Long.MIN_VALUE
    private var cachedHighLevelTarget: FarmPointPosition? = null
    private var cachedSteeringTarget: FarmPointPosition? = null
    private var cachedBlocked = false
    private var avoidanceTarget: FarmPointPosition? = null
    private var blockedSinceTick: Long? = null
    private var lastProgressTick: Long? = null
    private var lastObservedTick: Long = Long.MIN_VALUE
    private var lastObservedPosition: FarmPointPosition? = null
    private var lastProgressHeading = FarmMotionDirection(0.0, 0.0, 0.0)
    private var accumulatedTargetProgress = 0.0
    private var nextRecoveryAttemptTick = Long.MIN_VALUE
    private var recoveryCooldownUntilTick = Long.MIN_VALUE

    fun plan(
        world: World,
        current: FarmPointPosition,
        target: FarmPointPosition,
        recoveryTarget: FarmPointPosition,
        nowTick: Long,
        envelope: FarmRaidFlightEnvelope,
        verticalGoal: Boolean,
    ): Plan {
        require(current.world == target.world && current.world == recoveryTarget.world && current.world == world.name)
        observeProgress(current, target, nowTick, verticalGoal)

        val cachedTarget = cachedHighLevelTarget
        val targetDrift = cachedTarget?.horizontalDistanceSquared(target) ?: Double.POSITIVE_INFINITY
        val needsAssessment = lastAssessmentTick == Long.MIN_VALUE ||
            nowTick >= lastAssessmentTick + ASSESSMENT_INTERVAL_TICKS ||
            targetDrift > MAX_CACHED_TARGET_DRIFT_SQUARED
        if (needsAssessment) assess(world, current, target, nowTick, envelope)

        val hasBeenStuck = cachedBlocked && blockedSinceTick != null && lastProgressTick != null &&
            nowTick - lastProgressTick!! >= STUCK_TICKS
        val recovery = if (
            hasBeenStuck && nowTick >= recoveryCooldownUntilTick && nowTick >= nextRecoveryAttemptTick
        ) {
            findRecoveryTarget(world, current, recoveryTarget, envelope)
        } else {
            null
        }
        if (hasBeenStuck && recovery == null && nowTick >= nextRecoveryAttemptTick) {
            // Retry clearance on the next bounded assessment; never forgive the accumulated stuck time.
            nextRecoveryAttemptTick = nowTick + ASSESSMENT_INTERVAL_TICKS
        }
        return Plan(
            steeringTarget = cachedSteeringTarget ?: if (cachedBlocked) null else target,
            recoveryTarget = recovery,
            blocked = cachedBlocked,
        )
    }

    fun recoverySucceeded(nowTick: Long) {
        lastProgressTick = nowTick
        lastObservedPosition = null
        lastObservedTick = nowTick
        accumulatedTargetProgress = 0.0
        blockedSinceTick = null
        avoidanceTarget = null
        cachedSteeringTarget = null
        cachedBlocked = false
        cachedHighLevelTarget = null
        lastAssessmentTick = nowTick
        nextRecoveryAttemptTick = nowTick + ASSESSMENT_INTERVAL_TICKS
        recoveryCooldownUntilTick = nowTick + RECOVERY_COOLDOWN_TICKS
    }

    fun recoveryFailed(nowTick: Long) {
        // The entity did not move: keep lastProgressTick/blockedSinceTick intact and try again later.
        nextRecoveryAttemptTick = nowTick + ASSESSMENT_INTERVAL_TICKS
    }

    private fun observeProgress(
        current: FarmPointPosition,
        target: FarmPointPosition,
        nowTick: Long,
        verticalGoal: Boolean,
    ) {
        if (lastProgressTick == null) lastProgressTick = nowTick
        if (nowTick != lastObservedTick) {
            val previous = lastObservedPosition
            if (previous != null && previous.world == current.world) {
                val dx = current.x - previous.x
                val dy = current.y - previous.y
                val dz = current.z - previous.z
                val progress = dx * lastProgressHeading.x + dy * lastProgressHeading.y + dz * lastProgressHeading.z
                accumulatedTargetProgress = (accumulatedTargetProgress + progress)
                    .coerceIn(-PROGRESS_RESET_DISTANCE, PROGRESS_RESET_DISTANCE)
                if (accumulatedTargetProgress >= PROGRESS_RESET_DISTANCE) {
                    lastProgressTick = nowTick
                    accumulatedTargetProgress = 0.0
                }
            }
            val dx = target.x - current.x
            val dy = target.y - current.y
            val dz = target.z - current.z
            val horizontal = hypot(dx, dz)
            lastProgressHeading = when {
                verticalGoal && horizontal <= DIRECTION_EPSILON -> {
                    FarmMotionDirection(0.0, dy.compareTo(0.0).toDouble(), 0.0)
                }
                horizontal > DIRECTION_EPSILON -> FarmMotionDirection(dx / horizontal, 0.0, dz / horizontal)
                else -> FarmMotionDirection(0.0, 0.0, 0.0)
            }
            lastObservedPosition = current
            lastObservedTick = nowTick
        }
    }

    private fun assess(
        world: World,
        current: FarmPointPosition,
        target: FarmPointPosition,
        nowTick: Long,
        envelope: FarmRaidFlightEnvelope,
    ) {
        val currentLocation = current.location(world)
        val distance = current.distance(target)
        val lookAhead = if (distance <= DIRECTION_EPSILON) {
            current
        } else {
            FarmRaidFlight.step(current, target, min(LOOKAHEAD_DISTANCE, distance))
        }
        val directClear = space.isClearSegment(world, currentLocation, lookAhead.location(world), envelope)
        if (directClear) {
            avoidanceTarget = null
            blockedSinceTick = null
            cachedSteeringTarget = target
            cachedBlocked = false
        } else {
            if (blockedSinceTick == null) blockedSinceTick = nowTick
            cachedBlocked = true
            val oldAvoidance = avoidanceTarget
            val validOldAvoidance = oldAvoidance != null && current.distance(oldAvoidance) > ARRIVAL_DISTANCE &&
                space.isClearSegment(world, currentLocation, oldAvoidance.location(world), envelope)
            avoidanceTarget = if (validOldAvoidance) oldAvoidance else findDetour(world, current, target, envelope)
            cachedSteeringTarget = avoidanceTarget
        }
        cachedHighLevelTarget = target
        lastAssessmentTick = nowTick
    }

    private fun findDetour(
        world: World,
        current: FarmPointPosition,
        target: FarmPointPosition,
        envelope: FarmRaidFlightEnvelope,
    ): FarmPointPosition? {
        val dx = target.x - current.x
        val dz = target.z - current.z
        val horizontal = hypot(dx, dz)
        val distance = min(LOOKAHEAD_DISTANCE, current.distance(target))
        val verticalDistance = (target.y - current.y).coerceIn(-distance, distance)
        if (horizontal <= DIRECTION_EPSILON) {
            return null
        }
        val forwardX = dx / horizontal
        val forwardZ = dz / horizontal
        val sideX = -forwardZ
        val sideZ = forwardX
        val candidates = listOf(
            current.copy(y = current.y + DETOUR_HEIGHT),
            current.copy(y = current.y + DETOUR_HIGH_DETOUR),
            current.copy(x = current.x + forwardX * distance, y = current.y + verticalDistance + DETOUR_HEIGHT, z = current.z + forwardZ * distance),
            current.copy(x = current.x + forwardX * distance, y = current.y + verticalDistance + DETOUR_HIGH_DETOUR, z = current.z + forwardZ * distance),
            current.copy(x = current.x + forwardX * distance + sideX * DETOUR_SIDE, y = current.y + verticalDistance, z = current.z + forwardZ * distance + sideZ * DETOUR_SIDE),
            current.copy(x = current.x + forwardX * distance - sideX * DETOUR_SIDE, y = current.y + verticalDistance, z = current.z + forwardZ * distance - sideZ * DETOUR_SIDE),
        )
        return candidates.firstOrNull { isClearDetour(world, current, it, envelope) }
    }

    private fun isClearDetour(
        world: World,
        current: FarmPointPosition,
        target: FarmPointPosition,
        envelope: FarmRaidFlightEnvelope,
    ): Boolean = space.isClearSegment(world, current.location(world), target.location(world), envelope)

    private fun findRecoveryTarget(
        world: World,
        current: FarmPointPosition,
        target: FarmPointPosition,
        envelope: FarmRaidFlightEnvelope,
    ): FarmPointPosition? {
        val dx = target.x - current.x
        val dz = target.z - current.z
        val length = hypot(dx, dz)
        if (length <= DIRECTION_EPSILON) return null
        val directionX = dx / length
        val directionZ = dz / length
        for (distance in RECOVERY_DISTANCES) {
            if (length < MIN_RECOVERY_FORWARD_DISTANCE) break
            val forwardDistance = min(distance, length)
            if (forwardDistance < MIN_RECOVERY_FORWARD_DISTANCE) continue
            for (verticalOffset in RECOVERY_VERTICAL_OFFSETS) {
                val candidate = current.copy(
                    x = current.x + directionX * forwardDistance,
                    y = current.y + verticalOffset,
                    z = current.z + directionZ * forwardDistance,
                )
                // Intentionally validate only the endpoint: this is the one operation allowed to jump a wall.
                if (space.isClearAt(world, candidate.location(world), envelope)) {
                    nextRecoveryAttemptTick = Long.MIN_VALUE
                    return candidate
                }
            }
        }
        return null
    }

    private fun FarmPointPosition.location(world: World): Location = Location(world, x, y, z)

    private fun FarmPointPosition.distance(other: FarmPointPosition): Double =
        kotlin.math.sqrt(distanceSquared(other))

    private fun FarmPointPosition.distanceSquared(other: FarmPointPosition): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun FarmPointPosition.horizontalDistanceSquared(other: FarmPointPosition): Double {
        val dx = x - other.x
        val dz = z - other.z
        return dx * dx + dz * dz
    }

    private companion object {
        const val ASSESSMENT_INTERVAL_TICKS = 5L
        const val STUCK_TICKS = 40L
        const val RECOVERY_COOLDOWN_TICKS = 100L
        const val PROGRESS_RESET_DISTANCE = 1.0
        const val LOOKAHEAD_DISTANCE = 8.0
        const val ARRIVAL_DISTANCE = 2.0
        const val DETOUR_HEIGHT = 3.0
        const val DETOUR_HIGH_DETOUR = 6.0
        const val DETOUR_SIDE = 4.0
        const val DIRECTION_EPSILON = 1.0e-6
        const val MAX_CACHED_TARGET_DRIFT_SQUARED = 4.0
        const val MIN_RECOVERY_FORWARD_DISTANCE = 4.0
        val RECOVERY_DISTANCES = listOf(16.0, 24.0, 32.0)
        val RECOVERY_VERTICAL_OFFSETS = listOf(0.0, 2.0, -2.0, 4.0)
    }

    private data class FarmMotionDirection(val x: Double, val y: Double, val z: Double)
}
