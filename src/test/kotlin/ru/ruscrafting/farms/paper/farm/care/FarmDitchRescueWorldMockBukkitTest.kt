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
            val layout = FarmDitchLayout.cells(placementSequence)
            val ditchCells = layout.map { centerX + it.x to centerZ + it.z }.toSet()
            val expectedCrops = mutableMapOf<Pair<Int, Int>, String>()
            val expectedTerrain = mutableMapOf<Triple<Int, Int, Int>, String>()
            for (x in 2..14) for (z in 2..14) {
                world.getBlockAt(x, 64, z).type = Material.FARMLAND
                world.getBlockAt(x, 63, z).type = Material.DIRT
                world.getBlockAt(x, 62, z).type = Material.STONE
                world.getBlockAt(x, 61, z).type = Material.DEEPSLATE
                world.getBlockAt(x, 60, z).type = Material.STONE
                world.getBlockAt(x, 59, z).type = Material.DEEPSLATE
                world.getBlockAt(x, 58, z).type = Material.STONE
                val crop = world.getBlockAt(x, 65, z)
                crop.type = Material.WHEAT
                val age = crop.blockData as Ageable
                age.age = (x + z) % (age.maximumAge + 1)
                crop.blockData = age
                if (x to z in ditchCells) expectedCrops[x to z] = crop.blockData.asString
            }
            layout.forEach { cell ->
                repeat(cell.depth) { depth ->
                    val block = world.getBlockAt(centerX + cell.x, 64 - depth, centerZ + cell.z)
                    expectedTerrain[Triple(block.x, block.y, block.z)] = block.blockData.asString
                }
            }
            val settings = mockk<FarmZoneSettings> {
                io.mockk.every { id } returns "communal_farm"
            }
            val runtime = FarmRuntime(
                settings = settings,
                region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 56, 0, 16, 72, 16)),
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

            val floatingCrop = layout.first().let { cell ->
                world.getBlockAt(centerX + cell.x, 65, centerZ + cell.z)
            }
            floatingCrop.type = Material.WHEAT
            ditch.ensure(runtime)
            floatingCrop.type shouldBe Material.AIR

            (ditchCells.size in 68..78) shouldBe true
            val boundingArea =
                (ditchCells.maxOf { it.first } - ditchCells.minOf { it.first } + 1) *
                    (ditchCells.maxOf { it.second } - ditchCells.minOf { it.second } + 1)
            (boundingArea > ditchCells.size) shouldBe true
            layout.forEach { cell ->
                repeat(cell.depth) { depth ->
                    world.getBlockAt(centerX + cell.x, 64 - depth, centerZ + cell.z).type shouldBe Material.AIR
                }
                val x = centerX + cell.x
                val z = centerZ + cell.z
                world.getBlockAt(x, 65, z).type shouldBe Material.AIR
            }
            world.getBlockAt(2, 64, 2).type shouldBe Material.FARMLAND
            val spawn = requireNotNull(ditch.spawnLocation(runtime, runtime.state.careTargets.single()))
            spawn.block.type shouldBe Material.AIR
            spawn.block.getRelative(org.bukkit.block.BlockFace.DOWN).type.isSolid shouldBe true

            ditch.restore(runtime)

            expectedTerrain.forEach { (position, data) ->
                world.getBlockAt(position.first, position.second, position.third).blockData.asString shouldBe data
            }
            ditchCells.forEach { (x, z) ->
                world.getBlockAt(x, 65, z).blockData.asString shouldBe expectedCrops.getValue(x to z)
            }
        } finally {
            paper.close()
        }
    }

    test("every ditch template is connected and leaves a missing cell in its bounding box") {
        (0L..3L).forEach { selection ->
            val cells = FarmDitchLayout.cellsForSelection(selection)
            cells.first().x shouldBe 0
            cells.first().z shouldBe 0
            val offsets = cells.map { it.x to it.z }.toSet()
            val visited = mutableSetOf(0 to 0)
            val queue = ArrayDeque(visited)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                listOf(
                    current.first - 1 to current.second,
                    current.first + 1 to current.second,
                    current.first to current.second - 1,
                    current.first to current.second + 1,
                ).filter { it in offsets && visited.add(it) }.forEach(queue::addLast)
            }
            visited.shouldContainExactlyInAnyOrder(offsets)
            val boundingArea =
                (offsets.maxOf { it.first } - offsets.minOf { it.first } + 1) *
                    (offsets.maxOf { it.second } - offsets.minOf { it.second } + 1)
            (boundingArea > offsets.size) shouldBe true
            cells.minOf { it.depth } shouldBe 3
            cells.maxOf { it.depth } shouldBe 6
            (cells.sumOf { it.depth } >= 280) shouldBe true
        }
    }
})
