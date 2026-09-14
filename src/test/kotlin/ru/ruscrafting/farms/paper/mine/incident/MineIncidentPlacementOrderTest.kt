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
})
