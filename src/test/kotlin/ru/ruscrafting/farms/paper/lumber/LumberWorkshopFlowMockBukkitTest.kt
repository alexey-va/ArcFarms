package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.domain.lumber.LumberSawSide
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.lumber.stacking.LumberStackingEffects

class LumberWorkshopFlowMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("mistimed saw use keeps progress and stacked pallets enable one dispatch") {
        val world = paper.server.addSimpleWorld("world")
        for (x in 8..10) for (z in 8..10) world.getBlockAt(x, 63, z).type = Material.STONE
        val player = paper.server.addPlayer("Sawyer")
        val port = lumberTestPort()
        val effects = RecordingStackingEffects()
        val graph = testLumbermillComponentGraph(
            paper.createSimplePlugin("LumberWorkshopTest"),
            CuboidRegionGateway(),
            port,
            clock = { 1_000L },
            journal = ImmediateLumberJournal(),
            bundleEffects = RecordingBundleEffects(),
            stackingEffects = effects,
        )
        graph.module.rebuild(listOf(lumberSliceSettings()), emptyMap(), cooldownMillis = 5_000L)
        val runtime = graph.registry.byId("sawmill")!!
        runtime.state = LumberShiftState(
            phase = LumberPhase.SAWING,
            sequence = 1,
            orderId = "oak_contract",
            species = "OAK",
        )

        graph.sawing.use(runtime, LumberSawSide.LEFT, player, now = 1_000L).accepted shouldBe true
        graph.sawing.use(runtime, LumberSawSide.LEFT, player, now = 1_100L).accepted shouldBe false
        runtime.state.sawCuts shouldBe 1
        graph.sawing.use(runtime, LumberSawSide.RIGHT, player, now = 5_000L).accepted shouldBe true

        runtime.state.phase shouldBe LumberPhase.STACKING
        runtime.state.objective!!.targets shouldHaveSize 2
        effects.ground shouldHaveSize 2
        graph.stackingScene.pickupPlank(runtime, player) shouldBe true
        val pallet = runtime.state.objective!!.targets.first().id
        graph.stackingScene.place(runtime, pallet, player) shouldBe true
        runtime.state.phase shouldBe LumberPhase.DISPATCH

        graph.dispatch.ringBell(runtime, player, now = 6_000L).accepted shouldBe true
        graph.dispatch.ringBell(runtime, player, now = 6_001L).accepted shouldBe false
        runtime.state.phase shouldBe LumberPhase.COOLDOWN
        verify(exactly = 1) { port.recordCompletion(ActivityKind.LUMBER, any()) }
    }
})

private class RecordingStackingEffects : LumberStackingEffects {
    val ground = linkedSetOf<String>()
    val carried = linkedSetOf<java.util.UUID>()

    override fun showPallet(runtime: LumberRuntime, targetId: String, position: WorksitePosition) {
        ground += targetId
    }

    override fun hidePallet(runtime: LumberRuntime, targetId: String) {
        ground -= targetId
    }

    override fun showCarried(runtime: LumberRuntime, player: Player) {
        carried += player.uniqueId
    }

    override fun moveCarried(player: Player) = Unit

    override fun hideCarried(playerId: java.util.UUID) {
        carried -= playerId
    }

    override fun cleanupZone(zoneId: String) {
        ground.clear()
        carried.clear()
    }
}
