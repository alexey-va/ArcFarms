package ru.ruscrafting.farms.paper.mine.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.mine.ImmediateMineJournal
import ru.ruscrafting.farms.paper.mine.immediateMinePort
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.index.MineIndexedTarget
import ru.ruscrafting.farms.paper.mine.mineV2Settings
import ru.ruscrafting.farms.paper.mine.testMineComponentGraph

class MineConstructionIncidentsMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("cave-in creates physical crash-safe rubble that players clear with a pickaxe") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("Miner")
        val anchor = world.getBlockAt(10, 64, 10)
        (-1..1).forEach { dx -> world.getBlockAt(anchor.x + dx, anchor.y, anchor.z).type = Material.STONE }
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineCaveInTest"), CuboidRegionGateway(), immediateMinePort(),
            clock = { 1_000L }, journal = ImmediateMineJournal(),
        )
        graph.module.rebuild(
            listOf(mineV2Settings()),
            mapOf("old_shafts" to MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 1, orderId = "ore_run")),
            5_000L,
        )
        val runtime = graph.registry.byId("old_shafts")!!
        graph.index.replaceZone(
            MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
            listOf(world.getChunkAt(0, 0)),
            listOf(MineIndexedTarget(anchor.position(), setOf(MineAnchorRole.NEST))),
        )

        graph.caveIn.start(runtime, now = 1_000L) shouldBe true
        val targets = runtime.state.objective!!.targets
        targets shouldHaveSize 4
        targets.forEach { world.getBlockAt(it.position.x, it.position.y, it.position.z).type shouldBe Material.COBBLESTONE }

        player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        val target = targets.first()
        val rubble = world.getBlockAt(target.position.x, target.position.y, target.position.z)
        val event = BlockBreakEvent(rubble, player).also { it.expToDrop = 7 }
        graph.caveIn.onBreak(event) shouldBe true

        event.isCancelled shouldBe true
        event.isDropItems shouldBe false
        event.expToDrop shouldBe 0
        rubble.type shouldBe Material.AIR
        runtime.state.objective!!.targets.first { it.id == target.id }.status shouldBe ObjectiveTargetStatus.COMPLETED

        graph.caveIn.cleanup(runtime) shouldBe 3
        targets.forEach { world.getBlockAt(it.position.x, it.position.y, it.position.z).type shouldBe Material.AIR }
    }
})

private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)
