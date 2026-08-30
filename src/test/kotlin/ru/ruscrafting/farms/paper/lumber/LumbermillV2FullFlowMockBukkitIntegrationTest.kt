package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.event.block.BlockBreakEvent
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.lumber.LumberSawSide
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.lumber.index.LumberIndexDefinition
import ru.ruscrafting.farms.paper.lumber.index.LumberLogTarget
import ru.ruscrafting.farms.paper.lumber.stacking.LumberStackingEffects
import java.util.UUID

class LumbermillV2FullFlowMockBukkitIntegrationTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("one V2 order runs from indexed felling through one idempotent dispatch") {
        val world = paper.server.addSimpleWorld("world")
        for (x in 0..20) for (z in 0..20) world.getBlockAt(x, 63, z).type = Material.STONE
        world.getChunkAt(0, 0).load()
        val logs = listOf(world.getBlockAt(2, 64, 2), world.getBlockAt(5, 64, 2)).onEach { it.type = Material.OAK_LOG }
        val player = paper.server.addPlayer("EndToEndForester")
        player.inventory.setItemInMainHand(org.bukkit.inventory.ItemStack(Material.IRON_AXE))
        val port = lumberTestPort(listOf(player))
        val bundleEffects = RecordingBundleEffects()
        val graph = testLumbermillComponentGraph(
            paper.createSimplePlugin("LumberFullFlowTest"),
            CuboidRegionGateway(),
            port,
            clock = { 1_000L },
            journal = ImmediateLumberJournal(),
            bundleEffects = bundleEffects,
            stackingEffects = FullFlowStackingEffects,
        )
        graph.module.rebuild(listOf(lumberSliceSettings()), emptyMap(), cooldownMillis = 5_000L)
        val runtime = graph.registry.byId("sawmill")!!
        graph.index.replaceZone(
            LumberIndexDefinition("sawmill", runtime.region, setOf("OAK")),
            listOf(world.getChunkAt(0, 0)),
            logs.map { LumberLogTarget(WorksitePosition("world", it.x, it.y, it.z), "OAK") },
        )

        logs.forEach { graph.felling.onBreakHigh(BlockBreakEvent(it, player)) shouldBe true }
        runtime.state.phase shouldBe LumberPhase.SKIDDING

        val bundle = runtime.state.objective!!.targets.first { it.status == ObjectiveTargetStatus.AVAILABLE }
        graph.bundleScene.pickup(runtime, bundle.id, player) shouldBe true
        graph.bundleScene.deliver(runtime, player) shouldBe true
        runtime.state.phase shouldBe LumberPhase.SAWING

        graph.sawing.use(runtime, LumberSawSide.LEFT, player, 2_000L).accepted shouldBe true
        graph.sawing.use(runtime, LumberSawSide.RIGHT, player, 3_000L).accepted shouldBe true
        runtime.state.phase shouldBe LumberPhase.STACKING

        graph.stackingScene.pickupPlank(runtime, player) shouldBe true
        graph.stackingScene.place(runtime, runtime.state.objective!!.targets.first().id, player) shouldBe true
        runtime.state.phase shouldBe LumberPhase.DISPATCH

        graph.dispatch.ringBell(runtime, player, 4_000L).accepted shouldBe true
        graph.dispatch.ringBell(runtime, player, 4_001L).accepted shouldBe false
        runtime.state.phase shouldBe LumberPhase.COOLDOWN
        verify(exactly = 1) { port.recordCompletion(ActivityKind.LUMBER, any()) }
    }

    test("restart releases an orphaned bundle lease without losing foreground progress") {
        val world = paper.server.addSimpleWorld("world")
        for (x in 0..20) for (z in 0..20) world.getBlockAt(x, 63, z).type = Material.STONE
        val player = paper.server.addPlayer("RestartedCarrier")
        val plugin = paper.createSimplePlugin("LumberRestartTest")
        val effects = RecordingBundleEffects()
        val first = testLumbermillComponentGraph(
            plugin,
            CuboidRegionGateway(),
            lumberTestPort(listOf(player)),
            clock = { 1_000L },
            journal = ImmediateLumberJournal(),
            bundleEffects = effects,
        )
        first.module.rebuild(listOf(lumberSliceSettings()), emptyMap(), 5_000L)
        val runtime = first.registry.byId("sawmill")!!
        runtime.state = ru.ruscrafting.farms.domain.LumberShiftState(
            phase = LumberPhase.SKIDDING,
            sequence = 7,
            orderId = "oak_contract",
            species = "OAK",
            skidded = 0,
            objective = ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool.plan(
                ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey("sawmill", "skidding", 7),
                1,
                listOf(1, 2).map { index ->
                    ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate(
                        "bundle_$index",
                        WorksitePosition("world", index, 64, 4),
                        ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole("bundle"),
                        index.toLong(),
                    )
                },
            ),
        )
        first.bundleScene.pickup(runtime, "bundle_1", player) shouldBe true
        val persisted = runtime.state
        persisted.objective!!.target("bundle_1")!!.status shouldBe ObjectiveTargetStatus.LEASED
        first.module.cleanup("restart")

        val second = testLumbermillComponentGraph(
            plugin,
            CuboidRegionGateway(),
            lumberTestPort(listOf(player)),
            clock = { 2_000L },
            journal = ImmediateLumberJournal(),
            bundleEffects = RecordingBundleEffects(),
        )
        second.module.rebuild(listOf(lumberSliceSettings()), mapOf("sawmill" to persisted), 5_000L)
        second.module.activateLoadedState()
        val recovered = second.registry.byId("sawmill")!!.state
        recovered.phase shouldBe LumberPhase.SKIDDING
        recovered.skidded shouldBe 0
        recovered.objective!!.target("bundle_1")!!.status shouldBe ObjectiveTargetStatus.AVAILABLE
    }
})

private object FullFlowStackingEffects : LumberStackingEffects {
    override fun showPallet(runtime: LumberRuntime, targetId: String, position: WorksitePosition) = Unit
    override fun hidePallet(runtime: LumberRuntime, targetId: String) = Unit
    override fun showCarried(runtime: LumberRuntime, player: org.bukkit.entity.Player) = Unit
    override fun moveCarried(player: org.bukkit.entity.Player) = Unit
    override fun hideCarried(playerId: UUID) = Unit
    override fun cleanupZone(zoneId: String) = Unit
}
