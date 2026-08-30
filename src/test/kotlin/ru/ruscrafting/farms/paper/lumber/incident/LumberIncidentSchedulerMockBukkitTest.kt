package ru.ruscrafting.farms.paper.lumber.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.lumber.ImmediateLumberJournal
import ru.ruscrafting.farms.paper.lumber.testLumbermillComponentGraph
import ru.ruscrafting.farms.paper.lumber.RecordingBundleEffects
import ru.ruscrafting.farms.paper.lumber.lumberSliceSettings
import ru.ruscrafting.farms.paper.lumber.lumberTestPort

class LumberIncidentSchedulerMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("persisted incident schedule starts at compatible phases and rush never blocks stacking") {
        val world = paper.server.addSimpleWorld("world")
        for (x in 0..20) for (z in 0..20) world.getBlockAt(x, 63, z).type = Material.STONE
        val player = paper.server.addPlayer("ShiftWorker")
        player.teleport(Location(world, 9.0, 64.0, 9.0))
        val graph = testLumbermillComponentGraph(
            paper.createSimplePlugin("LumberSchedulerTest"),
            CuboidRegionGateway(),
            lumberTestPort(listOf(player)),
            clock = { 1_000L },
            journal = ImmediateLumberJournal(),
            bundleEffects = RecordingBundleEffects(),
        )
        val settings = lumberSliceSettings().let { base ->
            base.copy(
                orders = base.orders.map { order ->
                    order.copy(
                        incidentTypes = listOf(
                            LumberIncidentType.SAW_JAM,
                            LumberIncidentType.WARPED_BATCH,
                            LumberIncidentType.RUSH_ORDER,
                        ),
                    )
                },
            )
        }
        graph.module.rebuild(listOf(settings), emptyMap(), cooldownMillis = 5_000L)
        val runtime = graph.registry.byId("sawmill")!!
        runtime.state = LumberShiftEngine.start(runtime.state, runtime.nextOrder().domain(), runtime.rules(), 1_000L).state

        graph.incidentScheduler.tick(runtime, 2_000L, onlineParticipants = 1)
        runtime.state.phase shouldBe LumberPhase.FELLING

        runtime.state = runtime.state.copy(phase = LumberPhase.SAWING)
        graph.incidentScheduler.tick(runtime, 3_000L, onlineParticipants = 1)
        runtime.state.incident?.type shouldBe LumberIncidentType.SAW_JAM

        runtime.state = LumberShiftEngine.resolveIncident(
            runtime.state.copy(incident = runtime.state.incident!!.copy(progress = runtime.state.incident!!.required)),
        ).state.copy(phase = LumberPhase.STACKING)
        graph.incidentScheduler.tick(runtime, 4_000L, onlineParticipants = 1)
        runtime.state.incident?.type shouldBe LumberIncidentType.WARPED_BATCH

        runtime.state = LumberShiftEngine.resolveIncident(
            runtime.state.copy(incident = runtime.state.incident!!.copy(progress = runtime.state.incident!!.required)),
        ).state.copy(phase = LumberPhase.STACKING)
        graph.incidentScheduler.tick(runtime, 5_000L, onlineParticipants = 1)
        runtime.state.phase shouldBe LumberPhase.STACKING
        runtime.state.rushOrder?.bonusAvailable shouldBe true
        runtime.state.incidentCursor shouldBe 3
    }
})
