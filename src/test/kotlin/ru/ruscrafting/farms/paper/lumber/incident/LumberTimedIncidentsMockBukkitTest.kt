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
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItemController

class LumberTimedIncidentsMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("zero players pause fire and rush expiry removes only its bonus") {
        val world = paper.server.addSimpleWorld("world")
        for (x in 0..20) for (z in 0..20) world.getBlockAt(x, 63, z).type = Material.STONE
        world.getChunkAt(0, 0).load()
        world.getChunkAt(1, 0).load()
        world.getChunkAt(0, 1).load()
        world.getChunkAt(1, 1).load()
        val plugin = paper.createSimplePlugin("LumberTimedIncidentTest")
        val journal = ImmediateLumberJournal()
        val lateItems = LateBoundWorksiteServiceItems()
        val graph = LumbermillComponentGraph(
            plugin,
            CuboidRegionGateway(),
            lumberTestPort(),
            clock = { 1_000L },
            journal = journal,
            serviceItems = lateItems,
            bundleEffects = RecordingBundleEffects(),
        )
        val settings = lumberSliceSettings().let { base ->
            base.copy(orders = base.orders.map { it.copy(stackingRequired = 2) })
        }
        graph.module.rebuild(listOf(settings), emptyMap(), cooldownMillis = 5_000L)
        val serviceItems = WorksiteServiceItemController(plugin, graph.module).also(lateItems::bind)
        val firefighter = paper.server.addPlayer("Firefighter")
        val runtime = graph.registry.byId("sawmill")!!
        runtime.state = LumberShiftEngine.start(runtime.state, runtime.nextOrder().domain(), runtime.rules(), 1_000L).state

        graph.fire.start(runtime, required = 2, now = 2_000L) shouldBe true
        val initialObjective = runtime.state.objective
        val initialJournal = journal.records().map { it.positionKey }.toSet()
        initialObjective!!.targets.size shouldBe 4
        initialJournal.size shouldBe 8

        graph.fire.tick(runtime, onlineParticipants = 0, now = 32_000L)
        runtime.state.objective shouldBe initialObjective
        journal.records().map { it.positionKey }.toSet() shouldBe initialJournal

        graph.fire.pickupWater(runtime, firefighter) shouldBe true
        initialObjective.targets.take(2).forEach { target ->
            graph.fire.extinguish(runtime, target.id, firefighter).join() shouldBe true
        }
        runtime.state.phase shouldBe LumberPhase.FELLING
        journal.records().size shouldBe 0
        firefighter.inventory.storageContents.filterNotNull().count(serviceItems::isServiceItem) shouldBe 0

        runtime.state = runtime.state.copy(phase = LumberPhase.STACKING, stacked = 1)
        graph.rush.start(runtime, now = 40_000L, durationMillis = 5_000L) shouldBe true
        graph.rush.tick(runtime, now = 46_000L)

        runtime.state.phase shouldBe LumberPhase.STACKING
        runtime.state.stacked shouldBe 1
        runtime.state.rushOrder!!.bonusAvailable shouldBe false
        runtime.state.rushOrder!!.bonusEarned shouldBe false
        LumberShiftEngine.stack(runtime.state, runtime.rules(), paper.server.addPlayer("Stacker").uniqueId).accepted shouldBe true
    }
})
