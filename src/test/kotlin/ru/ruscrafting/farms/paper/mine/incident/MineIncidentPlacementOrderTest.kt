package ru.ruscrafting.farms.paper.mine.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.mine.MineRuntimeFactory
import ru.ruscrafting.farms.paper.mine.mineV2Settings

class MineIncidentPlacementOrderTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("nest candidates stay inside the walkable ring away from both edges") {
        val ring = (-18..18).flatMap { x -> (-18..18).mapNotNull { z ->
            WorksitePosition("world", x, 63, z).takeIf { x * x + z * z in 64..324 }
        } }
        val interior = interiorMineIncidentPositions(ring)
        interior.isNotEmpty() shouldBe true
        interior.all { point ->
            (-2..2).all { dx -> (-2..2).all { dz ->
                dx * dx + dz * dz > 4 || point.copy(x = point.x + dx, z = point.z + dz) in ring
            } }
        } shouldBe true
        interior.none { it.x == 18 || it.x == 8 && it.z == 0 } shouldBe true
    }

    test("visible incident targets are deterministic and distributed across the mine") {
        val world = paper.server.addSimpleWorld("world")
        val runtime = MineRuntimeFactory.build(
            listOf(mineV2Settings()), emptyMap(), 5_000L, CuboidRegionGateway(),
        ).single()
        val candidates = (0..2).flatMap { x -> (0..2).map { z ->
            WorksitePosition(world.name, x * 10, 64, z * 10)
        } }

        val first = orderMineIncidentPositions(runtime, candidates, 4, 71L)
        val repeated = orderMineIncidentPositions(runtime, candidates, 4, 71L)

        first shouldBe repeated
        first.take(4).flatMapIndexed { index, left ->
            first.take(4).drop(index + 1).map { right ->
                Location(world, left.x.toDouble(), 64.0, left.z.toDouble())
                    .distanceSquared(Location(world, right.x.toDouble(), 64.0, right.z.toDouble()))
            }
        }.all { it >= 64.0 } shouldBe true
        first.toSet() shouldBe candidates.toSet()
    }

    test("successive shifts sample a different mine-wide objective pool") {
        val world = paper.server.addSimpleWorld("world")
        val runtime = MineRuntimeFactory.build(
            listOf(mineV2Settings()), emptyMap(), 5_000L, CuboidRegionGateway(),
        ).single()
        val candidates = (0 until 1_000).map { index -> WorksitePosition(world.name, index, 64, index % 37) }

        val first = orderMineIncidentPositions(runtime, candidates, 4, 71L).take(4)
        runtime.state = runtime.state.copy(sequence = 1)
        val second = orderMineIncidentPositions(runtime, candidates, 4, 71L).take(4)

        first shouldBe first.distinct()
        (first == second) shouldBe false
    }
})
