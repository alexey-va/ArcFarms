package ru.ruscrafting.farms.paper.mine.incident.crystal

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.mine.crystal.MineCrystalPhase
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.mine.*

class MineCrystalResonanceMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("all four charges survive owner cleanup then drilling restores real wall and credits only once") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("ResonanceWorker")
        var now = 1_000L
        val journal = ImmediateMineJournal()
        val graph = testMineComponentGraph(paper.createSimplePlugin("CrystalLifecycle"), CuboidRegionGateway(),
            immediateMinePort(), { now }, journal)
        val settings = mineV2Settings().let { it.copy(orders = it.orders.map { order -> order.copy(miningRequired = 10) }) }
        graph.module.rebuild(listOf(settings), mapOf(settings.id to MineShiftState(engineVersion = 2,
            phase = MinePhase.MINING, sequence = 1, orderId = "ore_run")), 5_000L)
        val runtime = graph.registry.byId(settings.id)!!
        val crystals = installCrystalTestScene(graph, runtime)
        graph.crystalResonance.start(runtime, 4, now) shouldBe true
        val targets = runtime.state.objective!!.targets
        targets.take(2).forEach { target ->
            player.teleport(Location(world, target.position.x + .5, target.position.y.toDouble(), target.position.z + .5))
            graph.crystalResonance.hit(runtime, target.id, player, true) shouldBe true
        }
        // Cleanup only retires transient emitters. Durable charge state remains authoritative on re-entry.
        graph.crystalResonance.cleanupAll()
        runtime.state.incident!!.progress shouldBe 2
        targets.drop(2).forEach { target ->
            player.teleport(Location(world, target.position.x + .5, target.position.y.toDouble(), target.position.z + .5))
            graph.crystalResonance.hit(runtime, target.id, player, true) shouldBe true
        }
        runtime.state.incident!!.crystal!!.phase shouldBe MineCrystalPhase.CONVERGING
        val cuts = runtime.state.incident!!.crystal!!.cuts
        for (time in 2_400L..6_200L step 200L) {
            now = time
            graph.crystalResonance.tick(runtime, emptyList(), now)
        }
        cuts.all { world.getBlockAt(it.position.x, it.position.y, it.position.z).type == Material.AIR } shouldBe true
        journal.records().size shouldBe cuts.size
        runtime.state.incident!!.crystal!!.phase shouldBe MineCrystalPhase.EXTRACTING
        // Reconstructing transient state during extraction never creates another cut or an item entity.
        graph.crystalResonance.cleanupAll()
        now = 8_500L
        graph.crystalResonance.tick(runtime, emptyList(), now)
        runtime.state.phase shouldBe MinePhase.MINING
        runtime.state.mined shouldBe cuts.size
        graph.crystalResonance.tick(runtime, emptyList(), now + 1_000)
        runtime.state.mined shouldBe cuts.size
        graph.recovery.processDue(now + 2_000)
        // The shared AIR restoration guard deliberately waits until players leave the 12-block radius.
        journal.records().isNotEmpty() shouldBe true
        player.teleport(Location(world, 0.5, 64.0, 0.5))
        graph.recovery.processDue(now + 20_000)
        cuts.all { world.getBlockAt(it.position.x, it.position.y, it.position.z).type.name == it.material } shouldBe true
        journal.records().isEmpty() shouldBe true
        crystals.all { it.type == Material.AMETHYST_CLUSTER } shouldBe true
        world.entities.none { it is org.bukkit.entity.Item } shouldBe true
    }

    test("legacy two-of-four partial charges upgrade without clearing accepted crystals") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("LegacyResonance")
        val graph = testMineComponentGraph(paper.createSimplePlugin("CrystalLegacy"), CuboidRegionGateway(),
            immediateMinePort(), { 1_000L }, ImmediateMineJournal())
        val settings = mineV2Settings()
        graph.module.rebuild(listOf(settings), mapOf(settings.id to MineShiftState(engineVersion = 2,
            phase = MinePhase.MINING, sequence = 1, orderId = "ore_run")), 5_000L)
        val runtime = graph.registry.byId(settings.id)!!
        installCrystalTestScene(graph, runtime)
        graph.crystalResonance.start(runtime, 4, 1_000L) shouldBe true
        val first = runtime.state.objective!!.targets.first()
        player.teleport(Location(world, first.position.x + .5, first.position.y.toDouble(), first.position.z + .5))
        graph.crystalResonance.hit(runtime, first.id, player, true) shouldBe true
        runtime.state = runtime.state.copy(incident = runtime.state.incident!!.copy(required = 2, crystal = null),
            objective = runtime.state.objective!!.copy(required = 2))
        graph.crystalResonance.tick(runtime, emptyList(), 1_001L)
        runtime.state.incident!!.required shouldBe 4
        runtime.state.incident!!.progress shouldBe 1
        runtime.state.objective!!.completed shouldBe 1
        graph.crystalResonance.hit(runtime, first.id, player, true) shouldBe false
    }

    listOf(MineCrystalPhase.DRILLING to 5_000L, MineCrystalPhase.EXTRACTING to 6_200L).forEach { (phase, lastTick) ->
        test("a recovered wall conflict during $phase resumes the order without credit or overwriting the edit") {
            val world = paper.server.addSimpleWorld("world")
            val player = paper.server.addPlayer("RecoveryConflict")
            var now = 1_000L
            val journal = ImmediateMineJournal()
            val graph = testMineComponentGraph(paper.createSimplePlugin("CrystalConflict"), CuboidRegionGateway(),
                immediateMinePort(), { now }, journal)
            val settings = mineV2Settings()
            graph.module.rebuild(listOf(settings), mapOf(settings.id to MineShiftState(engineVersion = 2,
                phase = MinePhase.MINING, sequence = 1, orderId = "ore_run")), 5_000L)
            val runtime = graph.registry.byId(settings.id)!!
            installCrystalTestScene(graph, runtime)
            graph.crystalResonance.start(runtime, 4, now) shouldBe true
            runtime.state.objective!!.targets.forEach { target ->
                player.teleport(Location(world, target.position.x + .5, target.position.y.toDouble(), target.position.z + .5))
                graph.crystalResonance.hit(runtime, target.id, player, true) shouldBe true
            }
            val cuts = runtime.state.incident!!.crystal!!.cuts
            for (time in 2_400L..lastTick step 200L) {
                now = time
                graph.crystalResonance.tick(runtime, emptyList(), now)
            }
            runtime.state.incident!!.crystal!!.phase shouldBe phase
            journal.records().size shouldBe cuts.size
            val edited = cuts.first().position
            world.getBlockAt(edited.x, edited.y, edited.z).type = Material.GRANITE
            graph.crystalResonance.cleanupAll()
            now = 8_500L
            graph.crystalResonance.tick(runtime, emptyList(), now)
            runtime.state.phase shouldBe MinePhase.MINING
            runtime.state.incident shouldBe null
            runtime.state.mined shouldBe 0
            player.teleport(Location(world, 0.5, 64.0, 0.5))
            graph.recovery.processDue(now + 20_000L)
            world.getBlockAt(edited.x, edited.y, edited.z).type shouldBe Material.GRANITE
            cuts.drop(1).all { world.getBlockAt(it.position.x, it.position.y, it.position.z).type.name == it.material } shouldBe true
            journal.records().isEmpty() shouldBe true
        }
    }
})
