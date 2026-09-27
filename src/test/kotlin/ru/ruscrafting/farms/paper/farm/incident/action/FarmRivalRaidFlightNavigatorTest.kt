package ru.ruscrafting.farms.paper.farm.incident.action

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.paper.platform.FarmRaidFlightEnvelope
import ru.ruscrafting.farms.paper.platform.FarmRaidFlightSpace
import ru.ruscrafting.farms.paper.platform.PaperFarmRaidFlightSpace
import kotlin.math.hypot

class FarmRivalRaidFlightNavigatorTest : FunSpec({
    test("clear flight keeps the orbit target and caches collision checks for five ticks") {
        val space = ScriptedFlightSpace(segmentClear = { _, _ -> true }, endpointClear = { true })
        val navigator = FarmRivalRaidFlightNavigator(space)
        val current = position(0.0, 70.0, 0.0)
        val target = position(0.0, 80.0, 64.0)

        navigator.plan(world, current, target, target, 0, envelope, verticalGoal = false).let {
            it.steeringTarget shouldBe target
            it.blocked shouldBe false
        }
        navigator.plan(world, current, target.copy(z = 65.0), target, 4, envelope, verticalGoal = false)

        space.segmentChecks shouldBe 1
    }

    test("Paper collision space selects a climb over a nearby low wall") {
        val paperWorld = mockWorld { x, y, z ->
            if (x in -3..3 && z in 6..8 && y in 58..60) Material.BARRIER else Material.AIR
        }
        val navigator = FarmRivalRaidFlightNavigator(PaperFarmRaidFlightSpace)
        val current = position(0.0, 64.0, 0.0)
        val target = position(0.0, 64.0, 64.0)

        val plan = navigator.plan(paperWorld, current, target, target, 0, envelope, verticalGoal = false)

        plan.blocked shouldBe true
        plan.recoveryTarget shouldBe null
        (requireNotNull(plan.steeringTarget).y > current.y) shouldBe true
        PaperFarmRaidFlightSpace.isClearSegment(
            paperWorld,
            Location(paperWorld, current.x, current.y, current.z),
            Location(paperWorld, plan.steeringTarget!!.x, plan.steeringTarget!!.y, plan.steeringTarget!!.z),
            envelope,
        ) shouldBe true
    }

    test("unloaded and colliding recovery endpoints are rejected") {
        val unloadedWorld = mockWorld(loadChunks = false) { _, _, _ -> Material.AIR }
        PaperFarmRaidFlightSpace.isClearAt(unloadedWorld, Location(unloadedWorld, 0.0, 64.0, 0.0), envelope) shouldBe false
        verify(exactly = 0) { unloadedWorld.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }

        val lavaWorld = mockWorld { x, y, z ->
            if (x == 0 && y == 64 && z == 16) Material.LAVA else Material.AIR
        }
        PaperFarmRaidFlightSpace.isClearAt(lavaWorld, Location(lavaWorld, 0.0, 64.0, 16.0), envelope) shouldBe false

        val space = ScriptedFlightSpace(segmentClear = { _, _ -> false }, endpointClear = { false })
        val navigator = FarmRivalRaidFlightNavigator(space)
        navigator.plan(world, position(0.0, 64.0, 0.0), position(0.0, 64.0, 128.0),
            position(0.0, 64.0, 128.0), 0, envelope, verticalGoal = false)
            .recoveryTarget shouldBe null
        navigator.plan(world, position(0.0, 64.0, 0.0), position(0.0, 64.0, 128.0),
            position(0.0, 64.0, 128.0), 40, envelope, verticalGoal = false)
            .recoveryTarget shouldBe null
        space.endpointChecks shouldBe 12
    }

    test("a blocked wall can be jumped only to a clear forward endpoint and failed teleports keep the stuck timer") {
        val space = ScriptedFlightSpace(
            segmentClear = { _, _ -> false },
            endpointClear = { it.z >= 16.0 },
        )
        val navigator = FarmRivalRaidFlightNavigator(space)
        val current = position(0.0, 64.0, 0.0)
        val target = position(0.0, 64.0, 128.0)
        navigator.plan(world, current, target, target, 0, envelope, verticalGoal = false)
            .recoveryTarget shouldBe null

        val recovered = navigator.plan(world, current, target, target, 40, envelope, verticalGoal = false)
        recovered.recoveryTarget shouldBe position(0.0, 64.0, 16.0)
        navigator.recoveryFailed(40)
        navigator.plan(world, current, target, target, 44, envelope, verticalGoal = false)
            .recoveryTarget shouldBe null
        navigator.plan(world, current, target, target, 45, envelope, verticalGoal = false)
            .recoveryTarget shouldBe position(0.0, 64.0, 16.0)
    }

    test("Paper collision space jumps the measured three-block barrier to its first clear endpoint") {
        val paperWorld = mockWorld { x, y, z ->
            if (x in 85..212 && z in 586..588 && y in 39..134) Material.BARRIER else Material.AIR
        }
        val navigator = FarmRivalRaidFlightNavigator(PaperFarmRaidFlightSpace)
        val current = position(180.0, 63.0, 583.8)
        val orbitTarget = position(91.44, 81.0, 735.52)
        val directionX = orbitTarget.x - current.x
        val directionZ = orbitTarget.z - current.z
        val horizontalDistance = hypot(directionX, directionZ)

        navigator.plan(paperWorld, current, orbitTarget, orbitTarget, 0, envelope, verticalGoal = false)
            .recoveryTarget shouldBe null
        val recovery = navigator.plan(paperWorld, current, orbitTarget, orbitTarget, 40, envelope,
            verticalGoal = false).recoveryTarget

        recovery shouldBe position(180.0 + directionX / horizontalDistance * 16.0,
            63.0,
            583.8 + directionZ / horizontalDistance * 16.0)
    }

    test("recovery cooldown holds after success and orbit recovery follows its current pursuit target") {
        val space = ScriptedFlightSpace(segmentClear = { _, _ -> false }, endpointClear = { true })
        val navigator = FarmRivalRaidFlightNavigator(space)
        val current = position(0.0, 64.0, 0.0)
        val orbitTarget = position(100.0, 64.0, 100.0)
        val pursuitTarget = position(100.0, 64.0, 0.0)
        navigator.plan(world, current, orbitTarget, pursuitTarget, 0, envelope, verticalGoal = false)
        val recovery = navigator.plan(world, current, orbitTarget, pursuitTarget, 40, envelope, verticalGoal = false)
            .recoveryTarget
        recovery shouldBe position(16.0, 64.0, 0.0)
        navigator.recoverySucceeded(40)

        val afterJump = position(16.0, 64.0, 0.0)
        navigator.plan(world, afterJump, orbitTarget, pursuitTarget, 45, envelope, verticalGoal = false)
            .recoveryTarget shouldBe null
        navigator.plan(world, afterJump, orbitTarget, pursuitTarget, 85, envelope, verticalGoal = false)
            .recoveryTarget shouldBe null
    }

    test("legitimate vertical launch counts vertical progress while target drift and bouncing do not") {
        val launchSpace = ScriptedFlightSpace(segmentClear = { _, _ -> false }, endpointClear = { true })
        val launchNavigator = FarmRivalRaidFlightNavigator(launchSpace)
        for (tick in 0L..40L step 5L) {
            val current = position(0.0, 64.0 + tick * 0.05, 0.0)
            val launch = position(0.0, 100.0, 0.0)
            launchNavigator.plan(world, current, launch, position(0.0, 100.0, 100.0), tick,
                envelope, verticalGoal = true).recoveryTarget shouldBe null
        }

        val stuckSpace = ScriptedFlightSpace(segmentClear = { _, _ -> false }, endpointClear = { it.z >= 16.0 })
        val stuckNavigator = FarmRivalRaidFlightNavigator(stuckSpace)
        for (tick in 0L..35L step 5L) {
            val z = if (tick % 10L == 5L) 0.5 else 0.0
            val movingTarget = position(0.0, 64.0, 100.0 + tick * 0.01)
            stuckNavigator.plan(world, position(0.0, 64.0, z), movingTarget, movingTarget,
                tick, envelope, verticalGoal = false).recoveryTarget shouldBe null
        }
        val finalTarget = position(0.0, 64.0, 100.4)
        stuckNavigator.plan(world, position(0.0, 64.0, 0.0), finalTarget, finalTarget,
            40, envelope, verticalGoal = false).recoveryTarget shouldBe position(0.0, 64.0, 16.0)
    }
}) {
    companion object {
        private val world = mockk<World> {
            every { name } returns "world"
        }
        private val envelope = FarmRaidFlightEnvelope.forRaid(4, 1.6, -4.5)
    }
}

private class ScriptedFlightSpace(
    private val segmentClear: (Location, Location) -> Boolean,
    private val endpointClear: (Location) -> Boolean,
) : FarmRaidFlightSpace {
    var segmentChecks = 0
        private set
    var endpointChecks = 0
        private set

    override fun isClearAt(world: World, center: Location, envelope: FarmRaidFlightEnvelope): Boolean {
        endpointChecks++
        return endpointClear(center)
    }

    override fun isClearSegment(
        world: World,
        from: Location,
        to: Location,
        envelope: FarmRaidFlightEnvelope,
    ): Boolean {
        segmentChecks++
        return segmentClear(from, to)
    }
}

private fun mockWorld(
    loadChunks: Boolean = true,
    materialAt: (Int, Int, Int) -> Material,
): World {
    val blocks = mutableMapOf<Triple<Int, Int, Int>, Block>()
    val world = mockk<World>()
    every { world.name } returns "world"
    every { world.minHeight } returns -64
    every { world.maxHeight } returns 320
    every { world.isChunkLoaded(any(), any()) } returns loadChunks
    every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
        val x = firstArg<Int>()
        val y = secondArg<Int>()
        val z = thirdArg<Int>()
        blocks.getOrPut(Triple(x, y, z)) {
            val material = materialAt(x, y, z)
            mockk {
                every { type } returns material
                every { isPassable } returns material.isAir
            }
        }
    }
    return world
}

private fun position(x: Double, y: Double, z: Double) = FarmPointPosition("world", x, y, z)
