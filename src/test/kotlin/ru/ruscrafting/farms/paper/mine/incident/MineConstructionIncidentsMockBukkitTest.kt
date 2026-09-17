package ru.ruscrafting.farms.paper.mine.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.entity.BlockDisplay
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
        // The rubble begins at x=16. The entity origin must remain in chunk 1;
        // anti-z-fighting expansion belongs in the display transformation.
        val anchor = world.getBlockAt(18, 64, 18)
        (-2..2).forEach { dx -> (-2..2).forEach { dz ->
            world.getBlockAt(anchor.x + dx, anchor.y, anchor.z + dz).type = Material.STONE
        } }
        (-2..2).forEach { dx -> (-1..2).forEach { dz ->
            world.getBlockAt(anchor.x + dx, anchor.y + 5, anchor.z + dz).type = Material.STONE
        } }
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineCaveInTest"), CuboidRegionGateway(), immediateMinePort(),
            clock = { 1_000L }, journal = ImmediateMineJournal(),
        )
        graph.module.rebuild(
            listOf(mineV2Settings().copy(miningOnly = true)),
            mapOf("old_shafts" to MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 1, orderId = "ore_run")),
            5_000L,
        )
        val runtime = graph.registry.byId("old_shafts")!!
        graph.index.replaceZone(
            MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
            listOf(anchor.chunk),
            listOf(MineIndexedTarget(anchor.position(), setOf(MineAnchorRole.NEST))),
        )

        graph.caveIn.start(runtime, now = 1_000L) shouldBe true
        val targets = runtime.state.objective!!.targets
        targets.size.shouldBeInRange(55..65)
        targets.forEach { world.getBlockAt(it.position.x, it.position.y, it.position.z).type shouldBe Material.COBBLESTONE }
        world.entities.filterIsInstance<BlockDisplay>().single().apply {
            isGlowing shouldBe true
            block.material shouldBe Material.BLUE_STAINED_GLASS
            brightness?.blockLight shouldBe 15
            brightness?.skyLight shouldBe 15
            transformation.scale.x shouldBeGreaterThan 5.0f
            transformation.scale.y shouldBeGreaterThan 3.0f
            transformation.scale.z shouldBeGreaterThan 3.0f
            (transformation.translation.y < 0.0f) shouldBe true
            location.blockY shouldBe targets.minOf { it.position.y }
            (location.blockX shr 4) shouldBe 1
            (location.blockZ shr 4) shouldBe 1
        }

        player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        targets.forEachIndexed { index, target ->
            val rubble = world.getBlockAt(target.position.x, target.position.y, target.position.z)
            val event = BlockBreakEvent(rubble, player).also { it.expToDrop = 7 }
            graph.module.onBreakHigh(event) shouldBe true
            event.isCancelled shouldBe true
            event.isDropItems shouldBe false
            event.expToDrop shouldBe 0
            rubble.type shouldBe Material.AIR
            if (index < targets.lastIndex) {
                runtime.state.objective!!.targets.first { it.id == target.id }.status shouldBe ObjectiveTargetStatus.COMPLETED
            }
        }

        world.entities.filterIsInstance<BlockDisplay>() shouldHaveSize 0
        graph.caveIn.cleanup(runtime) shouldBe 0
        targets.forEach { world.getBlockAt(it.position.x, it.position.y, it.position.z).type shouldBe Material.AIR }
    }

    test("cave-in searches the complete indexed pool instead of rejecting after 512 anchors") {
        val world = paper.server.addSimpleWorld("world")
        val anchor = world.getBlockAt(10, 64, 10)
        (-2..2).forEach { dx -> (-2..2).forEach { dz ->
            world.getBlockAt(anchor.x + dx, anchor.y, anchor.z + dz).type = Material.STONE
        } }
        (-2..2).forEach { dx -> (-1..2).forEach { dz ->
            world.getBlockAt(anchor.x + dx, anchor.y + 5, anchor.z + dz).type = Material.STONE
        } }
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineCaveInFullPoolTest"), CuboidRegionGateway(), immediateMinePort(),
            clock = { 1_000L }, journal = ImmediateMineJournal(),
        )
        graph.module.rebuild(
            listOf(mineV2Settings().copy(miningOnly = true)),
            mapOf("old_shafts" to MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 7, orderId = "ore_run")),
            5_000L,
        )
        val runtime = graph.registry.byId("old_shafts")!!
        val invalid = (0 until 599).map { ordinal ->
            WorksitePosition("world", ordinal % 21, 50 + ordinal / 441, (ordinal / 21) % 21)
        }
        val chunks = (0..1).flatMap { x -> (0..1).map { z -> world.getChunkAt(x, z) } }
        graph.index.replaceZone(
            MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
            chunks,
            (invalid + anchor.position()).map { MineIndexedTarget(it, setOf(MineAnchorRole.NEST)) },
        )

        graph.caveIn.start(runtime, now = 1_000L) shouldBe true

        runtime.state.phase shouldBe MinePhase.INCIDENT
        graph.caveIn.diagnostics(runtime).considered shouldBe 600
    }

    test("cave-in aborts instead of exposing an objective whose journaled rubble conflicts") {
        val world = paper.server.addSimpleWorld("world")
        val anchor = world.getBlockAt(10, 64, 10)
        (-2..2).forEach { dx -> (-2..2).forEach { dz ->
            world.getBlockAt(anchor.x + dx, anchor.y, anchor.z + dz).type = Material.STONE
        } }
        (-2..2).forEach { dx -> (-1..2).forEach { dz ->
            world.getBlockAt(anchor.x + dx, anchor.y + 5, anchor.z + dz).type = Material.STONE
        } }
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineCaveInConflictTest"), CuboidRegionGateway(), immediateMinePort(),
            clock = { 1_000L }, journal = ImmediateMineJournal(),
        )
        graph.module.rebuild(
            listOf(mineV2Settings().copy(miningOnly = true)),
            mapOf("old_shafts" to MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 2, orderId = "ore_run")),
            5_000L,
        )
        val runtime = graph.registry.byId("old_shafts")!!
        graph.index.replaceZone(
            MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
            listOf(world.getChunkAt(0, 0)),
            listOf(MineIndexedTarget(anchor.position(), setOf(MineAnchorRole.NEST))),
        )
        graph.caveIn.start(runtime, now = 1_000L) shouldBe true
        val conflicted = requireNotNull(runtime.state.objective).targets.first().position
        world.getBlockAt(conflicted.x, conflicted.y, conflicted.z).type = Material.DIAMOND_BLOCK

        graph.caveIn.reconcile(runtime)

        runtime.state.phase shouldBe MinePhase.MINING
        runtime.state.objective shouldBe null
        world.getBlockAt(conflicted.x, conflicted.y, conflicted.z).type shouldBe Material.DIAMOND_BLOCK
    }
})

private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)
