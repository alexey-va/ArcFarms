package ru.ruscrafting.farms.paper.mine.incident.crystal

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.joml.Vector3d
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.ZoneReference
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmBlockDataDecoder
import ru.ruscrafting.farms.paper.mine.*
import ru.ruscrafting.farms.paper.mine.index.MineAnchorClassifier
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.index.MineIndexedTarget
import ru.ruscrafting.farms.paper.mine.recovery.MineIncidentBlockJournal
import java.util.UUID

class MineCrystalPlacementMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("live mine with 39 crystal anchors reaches safe rock beyond the twelve nearest decorations") {
        val (graph, runtime) = capturedCrystalScene(paper)
        val world = runtime.region.world
        val settings = runtime.settings
        val stock = graph.candidateStock.report(runtime, MineIncidentType.CRYSTAL_RESONANCE, 4)
        stock.considered shouldBe 39
        stock.usable shouldBe 4
        val centre = Vector3d(44.25, 90.73, 35.25)
        val nearest = graph.index.nearbyTargets(settings.id, MineAnchorRole.SUPPORT,
            Location(world, centre.x, centre.y, centre.z), 28).filter { it.y in 87..90 }
            .sortedBy { Vector3d(it.x + .5, it.y + .5, it.z + .5).distanceSquared(centre) }.take(12)
        nearest.size shouldBe 12
        nearest.none { MineCrystalWallPlan.natural(world.getBlockAt(it.x, it.y, it.z).type) } shouldBe true
        val planner = MineCrystalWallPlan(graph.index, graph.recovery)
        val search = planner.prepare(runtime, graph.candidateStock.candidates(runtime, MineIncidentType.CRYSTAL_RESONANCE))
        planner.advance(runtime, search)
        (search.cursor <= 12) shouldBe true
        search.pending shouldBe true
        search.result shouldBe null
        repeat(100) {
            val before = search.cursor
            planner.advance(runtime, search)
            (search.cursor - before <= 12) shouldBe true
        }
        search.result!!.cuts.isNotEmpty() shouldBe true
        (0 until 100).any { graph.crystalResonance.start(runtime, 4, 1_000L + it * 50) } shouldBe true
        runtime.state.objective!!.targets.size shouldBe 4
        runtime.state.incident!!.crystal!!.cuts.isNotEmpty() shouldBe true
    }

    test("pending forced replacement preserves the active incident until its full crystal plan is ready") {
        val (graph, runtime) = capturedCrystalScene(paper)
        runtime.state = runtime.state.copy(phase = MinePhase.INCIDENT, resumePhase = MinePhase.MINING,
            incident = ru.ruscrafting.farms.domain.MineIncidentState(MineIncidentType.GAS_LEAK, 2, progress = 1))
        val before = runtime.state
        graph.incidentSet.forceAdmin(runtime, MineIncidentType.CRYSTAL_RESONANCE, 1_000L) shouldBe false
        runtime.state shouldBe before
        graph.incidentSet.forceAdminAsync(runtime, MineIncidentType.CRYSTAL_RESONANCE, 1_001L).join() shouldBe false
        runtime.state shouldBe before
        graph.incidentScheduler.diagnostics(runtime, MineIncidentType.CRYSTAL_RESONANCE).rejected shouldBe mapOf("search_pending" to 1)
        repeat(100) { graph.crystalResonance.prewarm(runtime, 1_050L + it * 50) }
        runtime.state shouldBe before
        graph.incidentSet.forceAdminAsync(runtime, MineIncidentType.CRYSTAL_RESONANCE, 6_500L).join() shouldBe true
        runtime.state.incident!!.type shouldBe MineIncidentType.CRYSTAL_RESONANCE
        runtime.state.incident!!.progress shouldBe 0
        runtime.state.objective!!.targets.size shouldBe 4
    }

    test("no safe wall keeps the previous incident and reports the actual failed geometry") {
        val (graph, runtime) = simpleCrystalScene(paper)
        graph.index.targets(runtime.settings.id, MineAnchorRole.SUPPORT).forEach {
            runtime.region.world.getBlockAt(it.x, it.y, it.z).type = Material.STONE_BRICKS
        }
        runtime.state = runtime.state.copy(phase = MinePhase.INCIDENT, resumePhase = MinePhase.MINING,
            incident = ru.ruscrafting.farms.domain.MineIncidentState(MineIncidentType.GAS_LEAK, 2, progress = 1))
        val before = runtime.state
        graph.incidentSet.forceAdmin(runtime, MineIncidentType.CRYSTAL_RESONANCE, 1_000L) shouldBe false
        repeat(100) { graph.crystalResonance.prewarm(runtime, 1_000L) }
        graph.incidentSet.forceAdminAsync(runtime, MineIncidentType.CRYSTAL_RESONANCE, 1_001L).join() shouldBe false
        runtime.state shouldBe before
        graph.incidentScheduler.diagnostics(runtime, MineIncidentType.CRYSTAL_RESONANCE).rejected shouldBe mapOf("crystal_wall_missing" to 1)
    }

    listOf(false, true).forEach { asynchronous ->
        test("${if (asynchronous) "async" else "sync"} crystal replacement rejects an early resumed phase before retiring objectives or journal") {
            val journal = ImmediateMineJournal()
            val (graph, runtime) = simpleCrystalScene(paper, journal)
            runtime.settings = runtime.settings.copy(miningOnly = false)
            val targets = (1..2).map { ordinal -> ObjectiveTargetCandidate("vent_$ordinal",
                WorksitePosition("world", ordinal, 61, 1), ObjectiveTargetRole("vent"), ordinal.toLong()) }
            val activeObjective = ObjectiveTargetPool.plan(WorksiteObjectiveKey(runtime.settings.id, "incident_gas_leak", 1), 2, targets)
                .let { ObjectiveTargetPool.complete(it, targets.first().id, UUID.randomUUID()).state }
            val resumedObjective = ObjectiveTargetPool.plan(WorksiteObjectiveKey(runtime.settings.id, "prospecting", 1), 1,
                listOf(targets.first().copy(id = "probe")))
            runtime.state = runtime.state.copy(phase = MinePhase.INCIDENT, resumePhase = MinePhase.PROSPECTING,
                incident = ru.ruscrafting.farms.domain.MineIncidentState(MineIncidentType.GAS_LEAK, 2, progress = 1, objectiveNonce = 1),
                objective = activeObjective, resumeObjective = resumedObjective)
            val temporary = runtime.region.world.getBlockAt(1, 60, 1).also { it.type = Material.STONE }
            MineIncidentBlockJournal(graph.recovery).prepare(runtime, "gas_leak", 0, temporary.crystalPosition(),
                Material.COBBLESTONE).join() shouldBe true
            graph.crystalResonance.canStart(runtime, 1_000L) shouldBe true
            val before = runtime.state
            val records = journal.records()
            fun force(now: Long) = if (asynchronous)
                graph.incidentSet.forceAdminAsync(runtime, MineIncidentType.CRYSTAL_RESONANCE, now).join()
            else graph.incidentSet.forceAdmin(runtime, MineIncidentType.CRYSTAL_RESONANCE, now)

            force(1_001L) shouldBe false
            runtime.state shouldBe before
            graph.recovery.processDue(2_000L)
            journal.records() shouldBe records
            temporary.type shouldBe Material.COBBLESTONE
            val report = graph.incidentScheduler.diagnostics(runtime, MineIncidentType.CRYSTAL_RESONANCE)
            report.usable shouldBe 4
            report.required shouldBe 4
            report.rejected shouldBe mapOf("phase_too_early" to 1)

            runtime.state = before.copy(resumePhase = MinePhase.MINING)
            force(2_001L) shouldBe true
            runtime.state.incident!!.type shouldBe MineIncidentType.CRYSTAL_RESONANCE
            runtime.state.incident!!.progress shouldBe 0
            runtime.state.resumePhase shouldBe MinePhase.MINING
            runtime.state.objective!!.targets.size shouldBe 4
        }
    }

    test("a journal conflict invalidates a prepared wall before retiring the current incident") {
        val journal = ImmediateMineJournal()
        val (graph, runtime) = simpleCrystalScene(paper, journal)
        graph.crystalResonance.canStart(runtime, 1_000L) shouldBe true
        val planner = MineCrystalWallPlan(graph.index, graph.recovery)
        val search = planner.prepare(runtime, graph.candidateStock.candidates(runtime, MineIncidentType.CRYSTAL_RESONANCE))
        repeat(100) { planner.advance(runtime, search) }
        val cut = search.result!!.cuts.first()
        val p = cut.position
        journal.prepare(ru.ruscrafting.farms.domain.PendingMineBlock("other-owner", runtime.settings.id, p.world,
            p.x, p.y, p.z, cut.material, cut.material, cut.material, 50_000L)).join()
        runtime.state = runtime.state.copy(phase = MinePhase.INCIDENT, resumePhase = MinePhase.MINING,
            incident = ru.ruscrafting.farms.domain.MineIncidentState(MineIncidentType.GAS_LEAK, 2, progress = 1))
        val before = runtime.state
        graph.incidentSet.forceAdmin(runtime, MineIncidentType.CRYSTAL_RESONANCE, 1_001L) shouldBe false
        runtime.state shouldBe before
        graph.incidentScheduler.diagnostics(runtime, MineIncidentType.CRYSTAL_RESONANCE).rejected shouldBe mapOf("journalled_block" to 1)
        journal.records().size shouldBe 1
    }

    test("unloaded crystal chunks remain pending and a chunk arrival invalidates the stale preparation") {
        val (graph, runtime) = simpleCrystalScene(paper)
        graph.crystalResonance.canStart(runtime, 1_000L) shouldBe true
        runtime.region.world.unloadChunk(0, 0, false) shouldBe true
        graph.crystalResonance.canStart(runtime, 1_001L) shouldBe false
        graph.crystalResonance.diagnostics(runtime).rejected shouldBe mapOf("chunk_unloaded" to 1)
        runtime.state.phase shouldBe MinePhase.MINING
        runtime.region.world.loadChunk(0, 0)
        graph.incidentSet.reconcileChunk(runtime, runtime.region.world.getChunkAt(0, 0))
        graph.crystalResonance.start(runtime, 4, 1_002L) shouldBe true
    }
})

private fun capturedCrystalScene(paper: MockBukkitTestRuntime): Pair<MineComponentGraph, MineRuntime> {
    val world = paper.server.addSimpleWorld("world")
    val graph = testMineComponentGraph(paper.createSimplePlugin("LiveCrystalPlacement"), CuboidRegionGateway(),
        immediateMinePort(), { 1_000L }, ImmediateMineJournal())
    val settings = mineV2Settings().copy(reference = ZoneReference("world", null, CuboidBounds(30, 83, 12, 60, 94, 43)))
    graph.module.rebuild(listOf(settings), mapOf(settings.id to MineShiftState(engineVersion = 2,
        phase = MinePhase.MINING, sequence = 6, orderId = "ore_run")), 5_000L)
    val runtime = graph.registry.byId(settings.id)!!
    for (x in 1..3) for (z in 0..2) world.loadChunk(x, z)
    val capture = requireNotNull(MineCrystalPlacementMockBukkitTest::class.java.getResourceAsStream("/mine/crystal-live-20261024.json"))
        .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    val blocks = linkedSetOf<org.bukkit.block.Block>()
    capture.getAsJsonArray("boxes").forEach { entry ->
        val box = entry.asJsonArray
        val serialized = box[6].asString
        val material = requireNotNull(Material.matchMaterial(serialized.substringBefore('[')))
        // Placement uses decorative material solidity only. The pinned mock does not implement shelf states.
        val data = if (MineCrystalWallPlan.natural(material) || material == Material.AMETHYST_CLUSTER)
            MockBukkitFarmBlockDataDecoder.decode(serialized) else material.createBlockData()
        for (x in box[0].asInt..box[3].asInt) for (y in box[1].asInt..box[4].asInt) for (z in box[2].asInt..box[5].asInt) {
            world.getBlockAt(x, y, z).also { it.blockData = data.clone(); blocks += it }
        }
    }
    val indexed = blocks.mapNotNull { block ->
        MineAnchorClassifier.classify(block, setOf(Material.STONE)).takeIf { it.isNotEmpty() }
            ?.let { MineIndexedTarget(block.crystalPosition(), it) }
    }
    graph.index.replaceZone(MineIndexDefinition(settings.id, runtime.region, setOf(Material.STONE)),
        blocks.map { it.chunk }.distinct(), indexed)
    graph.prewarmIncidentCandidates(runtime)
    return graph to runtime
}

private fun simpleCrystalScene(paper: MockBukkitTestRuntime, journal: ImmediateMineJournal = ImmediateMineJournal()): Pair<MineComponentGraph, MineRuntime> {
    val world = paper.server.addSimpleWorld("world")
    for (x in 0..1) for (z in 0..1) world.loadChunk(x, z)
    val graph = testMineComponentGraph(paper.createSimplePlugin("CrystalPlacementGuard"), CuboidRegionGateway(),
        immediateMinePort(), { 1_000L }, journal)
    val settings = mineV2Settings()
    graph.module.rebuild(listOf(settings), mapOf(settings.id to MineShiftState(engineVersion = 2,
        phase = MinePhase.MINING, sequence = 1, orderId = "ore_run")), 5_000L)
    val runtime = graph.registry.byId(settings.id)!!
    installCrystalTestScene(graph, runtime)
    return graph to runtime
}
