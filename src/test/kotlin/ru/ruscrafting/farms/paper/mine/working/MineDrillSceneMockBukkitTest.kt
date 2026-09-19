package ru.ruscrafting.farms.paper.mine.working

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Interaction
import org.bukkit.entity.Minecart
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.MineIncidentState
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.MineWorkingStage
import ru.ruscrafting.farms.domain.MineWorkingState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.mineV2Settings
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene
import kotlin.math.abs

class MineDrillSceneMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("reconciles a glowing real minecart and exposes the drill control target") {
        val world = paper.server.addSimpleWorld("drill")
        world.getChunkAt(0, 0).load()
        val plugin = paper.createSimplePlugin("MineDrillSceneTest")
        val runtime = runtime(world)
        val scene = scene(world, runtime)
        val drill = MineDrillScene(plugin)

        drill.reconcile(runtime, scene, running = false, now = 1_000L)

        val cart = world.entities.filterIsInstance<Minecart>().single()
        val head = world.entities.filterIsInstance<BlockDisplay>().single()
        val control = world.entities.filterIsInstance<Interaction>().single()
        cart.isPersistent shouldBe false
        cart.isInvulnerable shouldBe true
        cart.passengers.shouldBeEmpty()
        cart.isGlowing shouldBe true
        head.isGlowing shouldBe true
        drill.target(control)?.first shouldBe runtime.settings.id
        // The entry interaction owns forward 0; keep the drill two blocks in
        // the clear stub before the first excavation face at forward 3.
        drill.target(cart)?.second shouldBe WorksitePosition(world.name, 0, 65, 2)

        runtime.state = runtime.state.copy(
            incident = runtime.state.incident!!.copy(
                working = runtime.state.incident!!.working!!.copy(completed = setOf(0)),
            ),
        )
        drill.reconcile(runtime, scene, running = true, now = 1_500L)
        cart.isValid shouldBe true
        head.isValid shouldBe true
    }

    test("keeps the cart and drill head on the curved floor centreline in every direction") {
        (0..3).forEach { direction ->
            val world = paper.server.addSimpleWorld("drill_curve_$direction")
            world.getChunkAt(0, 0).load()
            val plugin = paper.createSimplePlugin("MineDrillCurveTest$direction")
            val runtime = runtime(world, direction)
            val scene = scene(world, runtime)
            val drill = MineDrillScene(plugin)

            drill.reconcile(runtime, scene, running = false, now = 1_000L)
            val cart = world.entities.filterIsInstance<Minecart>().single()
            val head = world.entities.filterIsInstance<BlockDisplay>().single()
            val layers = scene.plan.excavation.withIndex()
                .groupBy { forwardDistance(runtime.state.incident!!.working!!.placement, it.value) }
                .toSortedMap()

            var completed = emptySet<Int>()
            layers.entries.take(3).forEach { (forward, points) ->
                completed = completed + points.map { it.index }
                runtime.state = runtime.state.copy(
                    incident = runtime.state.incident!!.copy(
                        working = runtime.state.incident!!.working!!.copy(completed = completed),
                    ),
                )
                drill.reconcile(runtime, scene, running = false, now = 1_100L + forward)

                val expected = center(runtime.state.incident!!.working!!.placement, points)
                cart.location.x.closeTo(expected.x + 0.5)
                cart.location.y.closeTo(expected.y + 0.1)
                cart.location.z.closeTo(expected.z + 0.5)
                head.location.y.closeTo(expected.y + 0.82)
                drill.target(cart)?.second shouldBe expected
            }
        }
    }

    test("reload cleanup removes orphaned vehicle, drillhead and control") {
        val world = paper.server.addSimpleWorld("drill_reload")
        world.getChunkAt(0, 0).load()
        val plugin = paper.createSimplePlugin("MineDrillReloadTest")
        val runtime = runtime(world)
        val scene = scene(world, runtime)
        val drill = MineDrillScene(plugin)
        drill.reconcile(runtime, scene, running = true, now = 2_000L)
        world.entities.size shouldBe 3

        drill.reconcileLoaded()
        world.entities.shouldBeEmpty()

        drill.reconcile(runtime, scene, running = false, now = 2_100L)
        world.entities.size shouldBe 3
        drill.cleanup(runtime.settings.id)
        world.entities.shouldBeEmpty()
    }
})

private fun runtime(world: WorldMock, direction: Int = 0): MineRuntime {
    val placement = MineWorkingPlacement(WorksitePosition(world.name, 0, 64, 0), direction, "drill-floor", 11L)
    return MineRuntime(
        settings = mineV2Settings().copy(id = "drill_zone"),
        region = CuboidActivityRegion(world, "drill_zone", CuboidBounds(-32, 48, -32, 32, 96, 48)),
        cooldownMillis = 0L,
        state = MineShiftState(
            engineVersion = 2,
            phase = MinePhase.INCIDENT,
            sequence = 4L,
            incident = MineIncidentState(
                type = MineIncidentType.TUNNEL_DRIVE,
                required = 90,
                objectiveNonce = 2L,
                startedAt = 1_000L,
                working = MineWorkingState(placement, MineWorkingStage.EXCAVATE),
            ),
        ),
    )
}

private fun forwardDistance(placement: MineWorkingPlacement, position: WorksitePosition): Int = when (placement.direction) {
    0 -> position.z - placement.entrance.z
    1 -> placement.entrance.x - position.x
    2 -> placement.entrance.z - position.z
    else -> position.x - placement.entrance.x
}

private fun sideDistance(placement: MineWorkingPlacement, position: WorksitePosition): Int = when (placement.direction) {
    0 -> position.x - placement.entrance.x
    1 -> position.z - placement.entrance.z
    2 -> placement.entrance.x - position.x
    else -> placement.entrance.z - position.z
}

private fun center(
    placement: MineWorkingPlacement,
    points: List<IndexedValue<WorksitePosition>>,
): WorksitePosition {
    val sides = points.map { sideDistance(placement, it.value) }
    val side = (sides.minOrNull()!! + sides.maxOrNull()!!) / 2
    return placement.position(side, 1, forwardDistance(placement, points.first().value))
}

private fun Double.closeTo(expected: Double) {
    (abs(this - expected) < 1.0e-6) shouldBe true
}

private fun scene(world: WorldMock, runtime: MineRuntime): MineWorkingScene {
    val plan = MineWorkingLayout.plan(MineIncidentType.TUNNEL_DRIVE, runtime.state.incident!!.working!!.placement)
    val origin = Location(world, 0.5, 65.0, 0.5)
    val prepared = WorksitePreparedScene(world, runtime.settings.id, runtime.state.sequence, 0, origin, origin, origin, emptyList())
    return MineWorkingScene(plan, prepared)
}
