package ru.ruscrafting.farms.paper.lumber.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.lumber.ImmediateLumberJournal
import ru.ruscrafting.farms.paper.lumber.LumbermillComponentGraph
import ru.ruscrafting.farms.paper.lumber.RecordingBundleEffects
import ru.ruscrafting.farms.paper.lumber.index.LumberIndexDefinition
import ru.ruscrafting.farms.paper.lumber.index.LumberLogTarget
import ru.ruscrafting.farms.paper.lumber.lumberSliceSettings
import ru.ruscrafting.farms.paper.lumber.lumberTestPort
import ru.ruscrafting.farms.paper.lumber.stacking.LumberStackingEffects
import java.util.UUID

class LumberForestTargetIncidentsMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("windthrow and beetles replace invalid targets and resume the exact felling objective") {
        val world = paper.server.addSimpleWorld("world")
        for (x in 0..12) for (z in 0..12) world.getBlockAt(x, 63, z).type = Material.DIRT
        val logs = (1..8).map { index ->
            world.getBlockAt(index, 64, 2).also { it.type = Material.OAK_LOG }
        }
        val player = paper.server.addPlayer("Forester")
        val graph = LumbermillComponentGraph(
            paper.createSimplePlugin("LumberIncidentTest"),
            CuboidRegionGateway(),
            lumberTestPort(),
            clock = { 1_000L },
            journal = ImmediateLumberJournal(),
            bundleEffects = RecordingBundleEffects(),
            stackingEffects = NoopStackingEffects,
        )
        graph.module.rebuild(listOf(lumberSliceSettings()), emptyMap(), cooldownMillis = 5_000L)
        val runtime = graph.registry.byId("sawmill")!!
        graph.index.replaceZone(
            LumberIndexDefinition("sawmill", runtime.region, setOf("OAK")),
            listOf(world.getChunkAt(0, 0)),
            logs.map { LumberLogTarget(WorksitePosition("world", it.x, it.y, it.z), "OAK") },
        )
        val started = LumberShiftEngine.start(runtime.state, runtime.nextOrder().domain(), runtime.rules(), 1_000L).state
        val foreground = ObjectiveTargetPool.plan(
            WorksiteObjectiveKey("sawmill", "felling", started.sequence),
            2,
            logs.take(4).mapIndexed { index, block ->
                ObjectiveTargetCandidate(
                    "log_${index + 1}",
                    WorksitePosition("world", block.x, block.y, block.z),
                    ObjectiveTargetRole("log"),
                    index.toLong(),
                )
            },
        )
        runtime.state = started.copy(objective = foreground)

        graph.windthrow.start(runtime, required = 2, now = 2_000L) shouldBe true
        runtime.state.objective!!.targets shouldHaveSize 4
        val invalid = runtime.state.objective!!.targets.first().id
        graph.windthrow.invalidate(runtime, invalid) shouldBe true
        runtime.state.objective!!.targets shouldHaveSize 4
        repeat(2) {
            graph.windthrow.complete(runtime, runtime.state.objective!!.targets.first { target ->
                target.status.name == "AVAILABLE"
            }.id, player)
        }
        runtime.state.phase shouldBe LumberPhase.FELLING
        runtime.state.objective shouldBe foreground

        graph.beetles.start(runtime, required = 2, now = 3_000L) shouldBe true
        val missing = runtime.state.objective!!.targets.first()
        world.getBlockAt(missing.position.x, missing.position.y, missing.position.z).type = Material.AIR
        graph.beetles.reconcile(runtime) shouldBe true
        runtime.state.objective!!.targets.none { it.position == missing.position } shouldBe true
        repeat(2) {
            graph.beetles.complete(runtime, runtime.state.objective!!.targets.first { target ->
                target.status.name == "AVAILABLE"
            }.id, player)
        }
        runtime.state.phase shouldBe LumberPhase.FELLING
        runtime.state.objective shouldBe foreground
    }
})

private object NoopStackingEffects : LumberStackingEffects {
    override fun showPallet(runtime: ru.ruscrafting.farms.paper.lumber.LumberRuntime, targetId: String, position: WorksitePosition) = Unit
    override fun hidePallet(runtime: ru.ruscrafting.farms.paper.lumber.LumberRuntime, targetId: String) = Unit
    override fun showCarried(runtime: ru.ruscrafting.farms.paper.lumber.LumberRuntime, player: org.bukkit.entity.Player) = Unit
    override fun moveCarried(player: org.bukkit.entity.Player) = Unit
    override fun hideCarried(playerId: UUID) = Unit
    override fun cleanupZone(zoneId: String) = Unit
}
