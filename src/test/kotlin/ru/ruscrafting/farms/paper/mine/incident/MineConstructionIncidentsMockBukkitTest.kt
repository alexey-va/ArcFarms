package ru.ruscrafting.farms.paper.mine.incident

import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.spyk
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.block.data.Levelled
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.entity.BlockDisplay
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.fixtures.requiredMockBukkitScenario
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineIncidentState
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.MineWorkingStage
import ru.ruscrafting.farms.domain.MineWorkingState
import ru.ruscrafting.farms.domain.PendingMineBlock
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
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
import ru.ruscrafting.farms.persistence.MineRecoveryJournal
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import java.util.concurrent.CompletableFuture

class MineConstructionIncidentsMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("cave-in creates physical crash-safe rubble that players clear with a pickaxe") {
        requiredMockBukkitScenario {
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
        val port = immediateMinePort()
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineCaveInTest"), CuboidRegionGateway(), port,
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
        val highlights = world.entities.filterIsInstance<BlockDisplay>()
        highlights shouldHaveSize targets.size
        highlights.forEach { display ->
            display.isGlowing shouldBe true
            display.block.material shouldBe Material.COBBLESTONE
            display.brightness?.blockLight shouldBe 15
            display.transformation.scale.x shouldBe 1.002f
            display.transformation.scale.y shouldBe 1.002f
            display.transformation.scale.z shouldBe 1.002f
            (display.location.blockX shr 4) shouldBe 1
            (display.location.blockZ shr 4) shouldBe 1
        }

        val unauthorized = paper.server.addPlayer("Unauthorized")
        every { port.hasAccess(unauthorized, any()) } returns false
        val guardedRubble = world.getBlockAt(targets.first().position.x, targets.first().position.y, targets.first().position.z)
        unauthorized.teleport(guardedRubble.location.clone().add(0.5, 0.0, -1.5))
        val denied = BlockBreakEvent(guardedRubble, unauthorized)
        graph.module.onBreakHigh(denied) shouldBe true
        denied.isCancelled shouldBe true
        guardedRubble.type shouldBe Material.COBBLESTONE
        runtime.state.objective!!.target(targets.first().id)!!.status shouldBe ObjectiveTargetStatus.AVAILABLE

        player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        targets.forEachIndexed { index, target ->
            val rubble = world.getBlockAt(target.position.x, target.position.y, target.position.z)
            player.teleport(rubble.location.clone().add(0.5, 0.0, -1.5))
            val start = org.bukkit.event.player.PlayerInteractEvent(player,
                org.bukkit.event.block.Action.LEFT_CLICK_BLOCK, player.inventory.itemInMainHand,
                rubble, org.bukkit.block.BlockFace.NORTH, org.bukkit.inventory.EquipmentSlot.HAND)
                .also { it.isCancelled = true }
            graph.module.onInteract(start, rubble, player) shouldBe true
            start.useInteractedBlock() shouldBe org.bukkit.event.Event.Result.ALLOW
            start.useItemInHand() shouldBe org.bukkit.event.Event.Result.ALLOW
            val damageBlock = spyk(rubble)
            every { damageBlock.getBreakSpeed(player) } returns 0.2f
            val damage = org.bukkit.event.block.BlockDamageEvent(player, damageBlock, player.inventory.itemInMainHand, false)
                .also { it.isCancelled = true }
            graph.module.onBlockDamage(damage) shouldBe true
            damage.isCancelled shouldBe false
            val event = BlockBreakEvent(rubble, player).also { it.expToDrop = 7 }
            graph.module.onBreakHigh(event) shouldBe true
            event.isCancelled shouldBe true
            event.isDropItems shouldBe false
            event.expToDrop shouldBe 0
            rubble.type shouldBe Material.AIR
            val remainingGlow = world.entities.filterIsInstance<BlockDisplay>()
            remainingGlow shouldHaveSize targets.size - index - 1
            remainingGlow.none { it.location.blockX == target.position.x &&
                it.location.blockY == target.position.y && it.location.blockZ == target.position.z } shouldBe true
            if (index < targets.lastIndex) {
                runtime.state.objective!!.targets.first { it.id == target.id }.status shouldBe ObjectiveTargetStatus.COMPLETED
            }
        }

        world.entities.filterIsInstance<BlockDisplay>() shouldHaveSize 0
        graph.caveIn.cleanup(runtime) shouldBe 0
        targets.forEach { world.getBlockAt(it.position.x, it.position.y, it.position.z).type shouldBe Material.AIR }
        }
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

    test("cave-in does not replay its durable batch while the world mutation is pending") {
        requiredMockBukkitScenario {
            val world = paper.server.addSimpleWorld("world")
            val anchor = world.getBlockAt(18, 64, 18)
            (-2..2).forEach { dx -> (-2..2).forEach { dz ->
                world.getBlockAt(anchor.x + dx, anchor.y, anchor.z + dz).type = Material.STONE
            } }
            (-2..2).forEach { dx -> (-1..2).forEach { dz ->
                world.getBlockAt(anchor.x + dx, anchor.y + 5, anchor.z + dz).type = Material.STONE
            } }
            val journal = DeferredMineJournal()
            val graph = testMineComponentGraph(
                paper.createSimplePlugin("MineCaveInPendingBatchTest"), CuboidRegionGateway(), immediateMinePort(),
                clock = { 1_000L }, journal = journal,
            )
            graph.module.rebuild(
                listOf(mineV2Settings().copy(miningOnly = true)),
                mapOf("old_shafts" to MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 3, orderId = "ore_run")),
                5_000L,
            )
            val runtime = graph.registry.byId("old_shafts")!!
            graph.index.replaceZone(
                MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
                listOf(anchor.chunk),
                listOf(MineIndexedTarget(anchor.position(), setOf(MineAnchorRole.NEST))),
            )

            graph.caveIn.start(runtime, now = 1_000L) shouldBe true
            val targets = requireNotNull(runtime.state.objective).targets
            targets.size.shouldBeInRange(55..65)
            journal.records() shouldHaveSize targets.size
            val first = targets.first().position
            world.getBlockAt(first.x, first.y, first.z).type shouldBe Material.AIR

            // This is the second reconcile that used to replay durable AIR->COBBLESTONE
            // records and make the first prepare callback fail validation.
            graph.caveIn.reconcile(runtime) shouldBe 0
            runtime.state.phase shouldBe MinePhase.INCIDENT
            world.getBlockAt(first.x, first.y, first.z).type shouldBe Material.AIR

            journal.completePrepare()
            runtime.state.phase shouldBe MinePhase.INCIDENT
            world.getBlockAt(first.x, first.y, first.z).type shouldBe Material.COBBLESTONE
        }
    }
    test("flooding does not replay its durable batch while the world mutation is pending") {
        requiredMockBukkitScenario {
            val world = paper.server.addSimpleWorld("world")
            val floorPlane = (0..14).flatMap { x -> (1..3).map { z ->
                world.getBlockAt(x, 63, z).also { it.type = Material.STONE }
            } }
            val floors = listOf(1, 4, 7, 10, 13).map { x -> world.getBlockAt(x, 63, 2) }
            val journal = DeferredMineJournal()
            val graph = testMineComponentGraph(
                paper.createSimplePlugin("MineFloodPendingBatchTest"), CuboidRegionGateway(), immediateMinePort(),
                clock = { 1_000L }, journal = journal,
            )
            graph.module.rebuild(
                listOf(mineV2Settings()),
                mapOf("old_shafts" to MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 4, orderId = "ore_run")),
                5_000L,
            )
            val runtime = graph.registry.byId("old_shafts")!!
            graph.index.replaceZone(
                MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
                listOf(world.getChunkAt(0, 0)),
                floors.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.NEST)) },
            )

            graph.flooding.start(runtime, required = 2, now = 1_000L) shouldBe true
            val waters = graph.flooding.waterPositions(runtime)
            (waters.size in 20..30) shouldBe true
            val first = waters.first()
            val block = world.getBlockAt(first.x, first.y, first.z)
            block.type shouldBe Material.AIR

            graph.flooding.reconcile(runtime) shouldBe 0
            runtime.state.phase shouldBe MinePhase.INCIDENT
            block.type shouldBe Material.AIR

            journal.completePrepare()
            runtime.state.phase shouldBe MinePhase.INCIDENT
            block.type shouldBe Material.WATER
            (block.blockData as Levelled).level shouldBe 0
            floorPlane.size shouldBe 45
        }
    }

    test("cave-in ignores stale successful journal completion after the incident was replaced") {
        requiredMockBukkitScenario {
            val world = paper.server.addSimpleWorld("world")
            val anchor = world.getBlockAt(18, 64, 18)
            (-2..2).forEach { dx -> (-2..2).forEach { dz ->
                world.getBlockAt(anchor.x + dx, anchor.y, anchor.z + dz).type = Material.STONE
            } }
            (-2..2).forEach { dx -> (-1..2).forEach { dz ->
                world.getBlockAt(anchor.x + dx, anchor.y + 5, anchor.z + dz).type = Material.STONE
            } }
            val journal = DeferredMineJournal()
            val graph = testMineComponentGraph(
                paper.createSimplePlugin("MineCaveReplacementBatchTest"), CuboidRegionGateway(), immediateMinePort(),
                clock = { 1_000L }, journal = journal,
            )
            graph.module.rebuild(
                listOf(mineV2Settings().copy(miningOnly = true)),
                mapOf("old_shafts" to MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 5, orderId = "ore_run")),
                5_000L,
            )
            val runtime = graph.registry.byId("old_shafts")!!
            graph.index.replaceZone(
                MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
                listOf(anchor.chunk),
                listOf(MineIndexedTarget(anchor.position(), setOf(MineAnchorRole.NEST))),
            )

            graph.caveIn.start(runtime, now = 1_000L) shouldBe true
            val first = requireNotNull(runtime.state.objective).targets.first().position
            val oldNonce = requireNotNull(runtime.state.incident).objectiveNonce
            runtime.state = runtime.state.copy(
                incident = runtime.state.incident!!.copy(objectiveNonce = oldNonce + 1L),
            )

            journal.completePrepare()

            runtime.state.phase shouldBe MinePhase.INCIDENT
            requireNotNull(runtime.state.incident).objectiveNonce shouldBe oldNonce + 1L
            world.getBlockAt(first.x, first.y, first.z).type shouldBe Material.AIR
        }
    }

    test("track damage kit release ignores a working rails lease") {
        requiredMockBukkitScenario {
            val world = paper.server.addSimpleWorld("world")
            val player = paper.server.addPlayer("TrackRepairer")
            val items = RecordingConstructionItems()
            val graph = testMineComponentGraph(
                paper.createSimplePlugin("MineTrackWorkingLeaseBoundaryTest"),
                CuboidRegionGateway(), immediateMinePort(),
                clock = { 1_000L }, journal = ImmediateMineJournal(), serviceItems = items,
            )
            graph.module.rebuild(listOf(mineV2Settings()), emptyMap(), 5_000L)
            val runtime = graph.registry.byId("old_shafts")!!
            val railsItemId = "rails_${player.uniqueId.toString().replace("-", "")}"
            val workingRailsIdentity = ServiceItemIdentity(
                ActivityKind.MINE, runtime.settings.id, 11, 17,
                ObjectiveTargetRole("working_rails"), railsItemId,
            )
            runtime.state = MineShiftState(
                engineVersion = 2,
                phase = MinePhase.INCIDENT,
                sequence = 11,
                orderId = "ore_run",
                incident = MineIncidentState(
                    type = MineIncidentType.TRACK_DAMAGE,
                    required = 3,
                    objectiveNonce = 17,
                    working = MineWorkingState(
                        MineWorkingPlacement(WorksitePosition(world.name, 18, 64, 18), 0, "fixture-floor"),
                        MineWorkingStage.CLEAR_TRACK,
                    ),
                    serviceLeases = mapOf(railsItemId to player.uniqueId),
                ),
            )
            graph.workings.isActive(workingRailsIdentity) shouldBe true

            graph.trackDamage.ensureKit(runtime, player) shouldBe false
            requireNotNull(runtime.state.incident).serviceLeases shouldBe mapOf(railsItemId to player.uniqueId)
            items.issued shouldBe emptyList()

            graph.trackDamage.releasePlayer(player.uniqueId) shouldBe false
            requireNotNull(runtime.state.incident).serviceLeases shouldBe mapOf(railsItemId to player.uniqueId)
            graph.workings.isActive(workingRailsIdentity) shouldBe true
            items.issued shouldBe emptyList()
        }
    }
})

private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)

private class DeferredMineJournal : MineRecoveryJournal {
    private val records = linkedMapOf<String, PendingMineBlock>()
    private var pending: CompletableFuture<Unit>? = null

    override fun records(): List<PendingMineBlock> = records.values.toList()
    override fun containsPosition(positionKey: String): Boolean = records.values.any { it.positionKey == positionKey }

    override fun prepare(record: PendingMineBlock): CompletableFuture<Unit> = prepareAll(listOf(record))

    override fun prepareAll(batch: List<PendingMineBlock>): CompletableFuture<Unit> {
        check(pending == null) { "Only one mine batch is expected" }
        check(batch.map(PendingMineBlock::id).distinct().size == batch.size)
        batch.forEach { record -> records[record.id] = record }
        return CompletableFuture<Unit>().also { pending = it }
    }

    override fun remove(recordId: String): CompletableFuture<Unit> {
        records.remove(recordId)
        return CompletableFuture.completedFuture(Unit)
    }

    fun completePrepare() {
        requireNotNull(pending).complete(Unit)
    }

}

private class RecordingConstructionItems : WorksiteServiceItems {
    val issued = mutableListOf<ServiceItemIdentity>()

    override fun issue(
        player: Player,
        identity: ServiceItemIdentity,
        material: Material,
        name: Component,
    ): ItemStack = ItemStack(material).also {
        issued += identity
        player.inventory.addItem(it)
    }

    override fun issueTool(
        player: Player,
        identity: ServiceItemIdentity,
        material: Material,
        name: Component,
        customModelData: Int,
        itemModel: org.bukkit.NamespacedKey?,
    ): ItemStack? = issue(player, identity, material, name)

    override fun consume(player: Player, expected: ServiceItemIdentity): Boolean = false
    override fun identity(item: ItemStack?): ServiceItemIdentity? = null
    override fun isServiceItem(item: ItemStack?): Boolean = false
}
