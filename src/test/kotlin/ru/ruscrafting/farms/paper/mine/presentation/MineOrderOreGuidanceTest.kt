package ru.ruscrafting.farms.paper.mine.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.mine.*
import ru.ruscrafting.farms.paper.mine.index.*

class MineOrderOreGuidanceTest : FunSpec({
    test("bright nearest ore hints follow remaining quotas and ignore stale or buried ore") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
            val settings = mineV2Settings().let { it.copy(miningOnly = true, materialWeights = linkedMapOf("STONE" to 1, "IRON_ORE" to 1, "COAL_ORE" to 1), orders = listOf(it.orders.single().copy(
                miningMaterials = setOf("IRON_ORE", "COAL_ORE"), resourceRequirements = mapOf("IRON" to 2, "COAL" to 2)))) }
            val runtime = MineRuntimeFactory.build(listOf(settings), emptyMap(), 5_000L, CuboidRegionGateway()).single()
            runtime.state = MineShiftState(engineVersion = 2, phase = MinePhase.MINING, orderId = "ore_run", sequence = 1,
                minedByMaterial = mapOf("COAL" to 2))
            val player = paper.server.addPlayer("OreSeeker")
            player.teleport(Location(world, 2.5, 64.0, 2.5))
            val ores = (3..10).map { x -> world.getBlockAt(x, 64, 2).also { it.type = Material.IRON_ORE } }
            ores[0].type = Material.COAL_ORE
            ores[1].type = Material.AIR
            val index = MineBlockIndex(paper.createSimplePlugin("OreGuidance"))
            index.replaceZone(MineIndexDefinition("old_shafts", runtime.region, runtime.mineableMaterials), world.loadedChunks.toList(),
                ores.map { MineIndexedTarget(WorksitePosition("world", it.x, it.y, it.z), setOf(MineAnchorRole.MINEABLE)) })
            val guidance = MineOrderOreGuidance(index)
            val targets = guidance.targets(runtime, player)
            targets.map { it.id } shouldBe listOf("ore:5:64:2", "ore:6:64:2", "ore:7:64:2", "ore:8:64:2")
            targets.all { it.bright } shouldBe true
            runtime.state = runtime.state.copy(minedByMaterial = mapOf("COAL" to 2, "IRON" to 2))
            guidance.targets(runtime, player) shouldBe emptyList()
        }
    }
})
