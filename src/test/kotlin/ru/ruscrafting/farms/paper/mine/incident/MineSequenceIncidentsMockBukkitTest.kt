package ru.ruscrafting.farms.paper.mine.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
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

    test("wrong sequence input repeats the next target without losing progress") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("Engineer")
        val vents = (1..5).map { x -> world.getBlockAt(x, 64, 2).also { it.type = Material.IRON_BARS } }
        val crystals = (1..5).map { x -> world.getBlockAt(x, 64, 5).also { it.type = Material.AMETHYST_BLOCK } }
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
            vents.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.VENT)) } +
                crystals.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.CRYSTAL)) },
        )

        graph.gasLeak.start(runtime, required = 2, now = 1_000L) shouldBe true
        val gasTargets = runtime.state.objective!!.targets
        graph.gasLeak.useVent(runtime, gasTargets[1].id, player) shouldBe false
        runtime.state.incident!!.progress shouldBe 0
        graph.gasLeak.useVent(runtime, gasTargets[0].id, player) shouldBe true
        graph.gasLeak.useVent(runtime, gasTargets[2].id, player) shouldBe false
        runtime.state.incident!!.progress shouldBe 1
        graph.gasLeak.useVent(runtime, gasTargets[1].id, player) shouldBe true
        runtime.state.phase shouldBe MinePhase.MINING

        graph.crystalResonance.start(runtime, required = 2, now = 2_000L) shouldBe true
        val crystalTargets = runtime.state.objective!!.targets
        graph.crystalResonance.hit(runtime, crystalTargets[0].id, player, insideForgivingWindow = false) shouldBe false
        runtime.state.incident!!.progress shouldBe 0
        graph.crystalResonance.hit(runtime, crystalTargets[0].id, player, insideForgivingWindow = true) shouldBe true
        graph.crystalResonance.hit(runtime, crystalTargets[2].id, player, insideForgivingWindow = false) shouldBe false
        runtime.state.incident!!.progress shouldBe 1
    }
})

private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)
