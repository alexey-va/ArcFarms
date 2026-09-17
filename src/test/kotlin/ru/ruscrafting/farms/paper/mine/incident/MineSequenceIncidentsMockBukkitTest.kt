package ru.ruscrafting.farms.paper.mine.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.mine.ImmediateMineJournal
import ru.ruscrafting.farms.paper.mine.testMineComponentGraph
import ru.ruscrafting.farms.paper.mine.immediateMinePort
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.index.MineIndexedTarget
import ru.ruscrafting.farms.paper.mine.mineV2Settings

class MineSequenceIncidentsMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("any remaining sequence target can be activated without ordered clicks") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("Engineer")
        val supports = (1..5).map { x -> world.getBlockAt(x, 64, 2).also { it.type = Material.STONE } }
        val crystals = (1..5).map { x -> world.getBlockAt(x, 64, 5).also { it.type = Material.AMETHYST_CLUSTER } }
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineSequenceTest"), CuboidRegionGateway(), immediateMinePort(),
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
            supports.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.SUPPORT)) } +
                crystals.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.CRYSTAL)) },
        )

        graph.gasLeak.start(runtime, required = 2, now = 1_000L) shouldBe true
        val gasTargets = runtime.state.objective!!.targets
        val feedback = mockk<Player>(relaxed = true)
        every { feedback.uniqueId } returns player.uniqueId
        every { feedback.location } returns player.location
        graph.gasLeak.useVent(runtime, gasTargets[1].id, feedback) shouldBe true
        verify(exactly = 1) { feedback.playSound(any<Location>(), Sound.BLOCK_FIRE_EXTINGUISH, 0.75f, 1.0f) }
        runtime.state.incident!!.progress shouldBe 1
        graph.gasLeak.useVent(runtime, gasTargets[0].id, player) shouldBe true
        runtime.state.phase shouldBe MinePhase.MINING

        graph.crystalResonance.start(runtime, required = 2, now = 2_000L) shouldBe true
        val crystalTargets = runtime.state.objective!!.targets
        graph.crystalResonance.hit(runtime, crystalTargets[1].id, feedback, insideForgivingWindow = true) shouldBe true
        verify(exactly = 1) { feedback.playSound(any<Location>(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.75f, 1.0f) }
        runtime.state.incident!!.progress shouldBe 1
        graph.crystalResonance.hit(runtime, crystalTargets[0].id, player, insideForgivingWindow = true) shouldBe true
        runtime.state.phase shouldBe MinePhase.MINING
    }

    test("crystal incident rejects a fully enclosed cluster without a direct click face") {
        val world = paper.server.addSimpleWorld("world")
        val crystal = world.getBlockAt(4, 64, 5).also { it.type = Material.AMETHYST_CLUSTER }
        listOf(
            world.getBlockAt(4, 63, 5), world.getBlockAt(4, 65, 5),
            world.getBlockAt(3, 64, 5), world.getBlockAt(5, 64, 5),
            world.getBlockAt(4, 64, 4), world.getBlockAt(4, 64, 6),
        ).forEach { it.type = Material.STONE }
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineEnclosedCrystalTest"), CuboidRegionGateway(), immediateMinePort(),
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
            listOf(MineIndexedTarget(crystal.position(), setOf(MineAnchorRole.CRYSTAL))),
        )

        graph.crystalResonance.start(runtime, required = 1, now = 1_000L) shouldBe false
        runtime.state.phase shouldBe MinePhase.MINING
    }
})

private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)
