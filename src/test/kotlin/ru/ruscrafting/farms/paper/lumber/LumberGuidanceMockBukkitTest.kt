package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.LumberIncidentState
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.paper.CuboidRegionGateway

class LumberGuidanceMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("every lumber phase and incident exposes a concrete next action") {
        val world = paper.server.addSimpleWorld("world")
        for (x in 0..20) for (z in 0..20) world.getBlockAt(x, 63, z).type = Material.STONE
        world.getChunkAt(0, 0).load()
        val player = paper.server.addPlayer("GuidedForester")
        player.teleport(org.bukkit.Location(world, 4.5, 64.0, 4.5))
        val graph = testLumbermillComponentGraph(
            paper.createSimplePlugin("LumberGuidanceTest"),
            CuboidRegionGateway(),
            lumberTestPort(listOf(player)),
            clock = { 1_000L },
            journal = ImmediateLumberJournal(),
            bundleEffects = RecordingBundleEffects(),
        )
        graph.module.rebuild(listOf(lumberSliceSettings()), emptyMap(), cooldownMillis = 5_000L)
        val runtime = graph.registry.byId("sawmill")!!
        val base = LumberShiftEngine.start(runtime.state, runtime.nextOrder().domain(), runtime.rules(), 1_000L).state
        val plain = PlainTextComponentSerializer.plainText()

        listOf(
            LumberPhase.FELLING,
            LumberPhase.SKIDDING,
            LumberPhase.SAWING,
            LumberPhase.STACKING,
            LumberPhase.DISPATCH,
        ).forEach { phase ->
            runtime.state = base.copy(phase = phase)
            val view = graph.guidance.view(player.uniqueId)!!
            plain.serialize(view.title).length shouldBeGreaterThan 0
            plain.serialize(view.subtitle).length shouldBeGreaterThan 0
            plain.serialize(view.barName).length shouldBeGreaterThan 0
        }

        LumberIncidentType.entries.filterNot { it == LumberIncidentType.RUSH_ORDER }.forEach { type ->
            runtime.state = base.copy(
                phase = LumberPhase.INCIDENT,
                resumePhase = LumberPhase.FELLING,
                incident = LumberIncidentState(type, required = 2),
            )
            val view = graph.guidance.view(player.uniqueId)!!
            plain.serialize(view.subtitle).length shouldBeGreaterThan 0
        }

        runtime.state = base.copy(phase = LumberPhase.STACKING)
        graph.rush.start(runtime, 1_000L, 10_000L)
        plain.serialize(graph.guidance.view(player.uniqueId)!!.subtitle).length shouldBeGreaterThan 0
        graph.guidance.participants().map { it.uniqueId }.shouldNotBeEmpty()
    }
})
