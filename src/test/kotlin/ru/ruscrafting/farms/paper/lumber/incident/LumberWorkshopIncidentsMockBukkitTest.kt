package ru.ruscrafting.farms.paper.lumber.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.domain.lumber.LumberBatchRole
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.lumber.ImmediateLumberJournal
import ru.ruscrafting.farms.paper.lumber.testLumbermillComponentGraph
import ru.ruscrafting.farms.paper.lumber.RecordingBundleEffects
import ru.ruscrafting.farms.paper.lumber.lumberSliceSettings
import ru.ruscrafting.farms.paper.lumber.lumberTestPort

class LumberWorkshopIncidentsMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("wrong switch and wrong pallet never erase accepted incident progress") {
        val world = paper.server.addSimpleWorld("world")
        for (x in 8..10) for (z in 8..10) world.getBlockAt(x, 63, z).type = Material.STONE
        val player = paper.server.addPlayer("WorkshopKeeper")
        val graph = testLumbermillComponentGraph(
            paper.createSimplePlugin("LumberWorkshopIncidentTest"),
            CuboidRegionGateway(),
            lumberTestPort(),
            clock = { 1_000L },
            journal = ImmediateLumberJournal(),
            bundleEffects = RecordingBundleEffects(),
        )
        graph.module.rebuild(listOf(lumberSliceSettings()), emptyMap(), cooldownMillis = 5_000L)
        val runtime = graph.registry.byId("sawmill")!!
        runtime.state = LumberShiftEngine.start(runtime.state, runtime.nextOrder().domain(), runtime.rules(), 1_000L).state

        graph.sawJam.start(runtime, required = 3, now = 2_000L) shouldBe true
        graph.sawJam.useSwitch(runtime, 2, player) shouldBe false
        runtime.state.incident!!.progress shouldBe 0
        graph.sawJam.useSwitch(runtime, 0, player) shouldBe true
        graph.sawJam.useSwitch(runtime, 2, player) shouldBe false
        runtime.state.incident!!.progress shouldBe 1
        graph.sawJam.useSwitch(runtime, 1, player) shouldBe true
        graph.sawJam.useSwitch(runtime, 2, player) shouldBe true
        runtime.state.phase shouldBe LumberPhase.FELLING

        graph.warped.start(runtime, required = 2, now = 3_000L) shouldBe true
        val warped = runtime.state.objective!!.targets.first { it.role.value == "reject" }
        graph.warped.deliver(runtime, warped.id, LumberBatchRole.ACCEPT, player) shouldBe false
        runtime.state.incident!!.progress shouldBe 0
        graph.warped.deliver(runtime, warped.id, LumberBatchRole.REJECT, player) shouldBe true
        runtime.state.incident!!.progress shouldBe 1
    }
})
