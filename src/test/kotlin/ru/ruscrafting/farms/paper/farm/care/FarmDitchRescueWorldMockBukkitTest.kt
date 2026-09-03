package ru.ruscrafting.farms.paper.farm.care

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort

class FarmDitchRescueWorldMockBukkitTest : FunSpec({
    test("procedural ditch removes an uneven crop patch and restores exact active crops") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("sp11")
            world.getChunkAt(0, 0).load()
            val placementSequence = 12L
            val centerX = 8
            val centerZ = 8
            val ditchCells = FarmDitchLayout.offsets(placementSequence)
                .map { centerX + it.x to centerZ + it.z }
                .toSet()
            val expectedCrops = mutableMapOf<Pair<Int, Int>, String>()
            for (x in 5..11) for (z in 5..11) {
                world.getBlockAt(x, 64, z).type = Material.FARMLAND
                val crop = world.getBlockAt(x, 65, z)
                crop.type = Material.WHEAT
                val age = crop.blockData as Ageable
                age.age = (x + z) % (age.maximumAge + 1)
                crop.blockData = age
                if (x to z in ditchCells) expectedCrops[x to z] = crop.blockData.asString
            }
            val settings = mockk<FarmZoneSettings> {
                io.mockk.every { id } returns "communal_farm"
            }
            val runtime = FarmRuntime(
                settings = settings,
                region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 60, 0, 16, 72, 16)),
                orders = emptyMap(),
                orderList = emptyList(),
                rules = FarmRules(listOf(50), 1, 1_000),
                state = FarmShiftState(
                    phase = FarmPhase.CARE,
                    careType = FarmCareType.DITCH_RESCUE,
                    sequence = 12,
                    placementSequence = placementSequence,
                    careTargets = listOf(
                        FarmCareTarget(
                            0,
                            FarmCareRole.ANIMAL,
                            FarmPointPosition(world.name, centerX + 0.5, 64.0, centerZ + 0.5),
                        ),
                    ),
                    careGoal = 1,
                ),
            )
            val ledger = FarmBlockLedger(paper.createSimplePlugin("FarmDitchRescueWorldTest"))
            val ditch = FarmDitchRescueWorld(
                ledger,
                mockk<WorksiteStatePort>(relaxed = true),
                ArcFarmsDebug({ false }) {},
            )

            ditch.ensure(runtime)
            ditch.ensure(runtime)

            (ditchCells.size in 9..12) shouldBe true
            val boundingArea =
                (ditchCells.maxOf { it.first } - ditchCells.minOf { it.first } + 1) *
                    (ditchCells.maxOf { it.second } - ditchCells.minOf { it.second } + 1)
            (boundingArea > ditchCells.size) shouldBe true
            ditchCells.forEach { (x, z) ->
                world.getBlockAt(x, 64, z).type shouldBe Material.AIR
                world.getBlockAt(x, 65, z).type shouldBe Material.AIR
            }
            world.getBlockAt(5, 64, 5).type shouldBe Material.FARMLAND

            ditch.restore(runtime)

            ditchCells.forEach { (x, z) ->
                world.getBlockAt(x, 64, z).type shouldBe Material.FARMLAND
                world.getBlockAt(x, 65, z).blockData.asString shouldBe expectedCrops.getValue(x to z)
            }
        } finally {
            paper.close()
        }
    }

    test("every ditch template is connected and leaves a missing cell in its bounding box") {
        (0L..3L).forEach { selection ->
            val offsets = FarmDitchLayout.offsetsForSelection(selection).toSet()
            val visited = mutableSetOf(FarmDitchLayout.Offset(0, 0))
            val queue = ArrayDeque(visited)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                listOf(
                    FarmDitchLayout.Offset(current.x - 1, current.z),
                    FarmDitchLayout.Offset(current.x + 1, current.z),
                    FarmDitchLayout.Offset(current.x, current.z - 1),
                    FarmDitchLayout.Offset(current.x, current.z + 1),
                ).filter { it in offsets && visited.add(it) }.forEach(queue::addLast)
            }
            visited.shouldContainExactlyInAnyOrder(offsets)
            val boundingArea =
                (offsets.maxOf { it.x } - offsets.minOf { it.x } + 1) *
                    (offsets.maxOf { it.z } - offsets.minOf { it.z } + 1)
            (boundingArea > offsets.size) shouldBe true
        }
    }
})
