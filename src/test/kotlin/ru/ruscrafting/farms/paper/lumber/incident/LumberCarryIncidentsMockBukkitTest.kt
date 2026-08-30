package ru.ruscrafting.farms.paper.lumber.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.lumber.ImmediateLumberJournal
import ru.ruscrafting.farms.paper.lumber.LumbermillComponentGraph
import ru.ruscrafting.farms.paper.lumber.RecordingBundleEffects
import ru.ruscrafting.farms.paper.lumber.lumberSliceSettings
import ru.ruscrafting.farms.paper.lumber.lumberTestPort
import ru.ruscrafting.farms.paper.worksite.LateBoundWorksiteServiceItems
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItemController

class LumberCarryIncidentsMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("conveyor kits and lost loads return after participant release") {
        val world = paper.server.addSimpleWorld("world")
        for (x in 0..12) for (z in 0..12) world.getBlockAt(x, 63, z).type = Material.STONE
        val player = paper.server.addPlayer("Repairer")
        val plugin = paper.createSimplePlugin("LumberCarryIncidentTest")
        val lateItems = LateBoundWorksiteServiceItems()
        val lostEffects = RecordingBundleEffects()
        val graph = LumbermillComponentGraph(
            plugin,
            CuboidRegionGateway(),
            lumberTestPort(),
            clock = { 1_000L },
            journal = ImmediateLumberJournal(),
            serviceItems = lateItems,
            bundleEffects = RecordingBundleEffects(),
            lostLoadEffects = lostEffects,
        )
        graph.module.rebuild(listOf(lumberSliceSettings()), emptyMap(), cooldownMillis = 5_000L)
        val serviceItems = WorksiteServiceItemController(plugin, graph.module).also(lateItems::bind)
        val runtime = graph.registry.byId("sawmill")!!
        runtime.state = LumberShiftEngine.start(runtime.state, runtime.nextOrder().domain(), runtime.rules(), 1_000L).state

        graph.conveyor.start(runtime, required = 2, now = 2_000L) shouldBe true
        graph.conveyor.pickupKit(runtime, player) shouldBe true
        player.inventory.storageContents.filterNotNull().count(serviceItems::isServiceItem) shouldBe 1
        graph.conveyor.availableKits(runtime) shouldBe 1
        serviceItems.cleanupPlayer(player, WorksitePlayerReleaseReason.QUIT)
        player.inventory.storageContents.filterNotNull().count(serviceItems::isServiceItem) shouldBe 0
        graph.conveyor.availableKits(runtime) shouldBe 2

        runtime.state = LumberShiftEngine.resolveIncident(
            runtime.state.copy(incident = runtime.state.incident!!.copy(progress = 2)),
        ).state
        graph.lostLoad.start(runtime, required = 2, now = 3_000L) shouldBe true
        val target = runtime.state.objective!!.targets.first().id
        graph.lostLoad.pickup(runtime, target, player) shouldBe true
        graph.module.releasePlayer(player, WorksitePlayerReleaseReason.ZONE_EXIT)
        runtime.state.objective!!.target(target)!!.leasedBy shouldBe null
        lostEffects.ground.contains(target) shouldBe true
        runtime.state.phase shouldBe LumberPhase.INCIDENT
    }
})
