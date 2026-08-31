package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.lumber.index.LumberIndexDefinition
import ru.ruscrafting.farms.paper.lumber.index.LumberLogTarget
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminHandler

class LumberGuidanceAdminRewardMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("lumber admin starts an indexed objective and exposes the shared typed surface") {
        val world = paper.server.addSimpleWorld("world")
        for (x in 0..20) for (z in 0..20) world.getBlockAt(x, 63, z).type = Material.STONE
        val logs = listOf(world.getBlockAt(2, 64, 2), world.getBlockAt(4, 64, 2)).onEach { it.type = Material.OAK_LOG }
        val player = paper.server.addPlayer("AdminForester")
        val graph = testLumbermillComponentGraph(
            paper.createSimplePlugin("LumberAdminTest"), CuboidRegionGateway(), lumberTestPort(listOf(player)),
            clock = { 1_000L }, journal = ImmediateLumberJournal(), bundleEffects = RecordingBundleEffects(),
        )
        graph.module.rebuild(listOf(lumberSliceSettings()), emptyMap(), 5_000L)
        val runtime = graph.registry.byId("sawmill")!!
        graph.index.replaceZone(
            LumberIndexDefinition("sawmill", runtime.region, setOf("OAK")),
            listOf(world.getChunkAt(0, 0)),
            logs.map { LumberLogTarget(WorksitePosition("world", it.x, it.y, it.z), "OAK") },
        )
        val admin: WorksiteAdminHandler = graph.admin

        admin.kind shouldBe ActivityKind.LUMBER
        admin.zoneIds() shouldBe listOf("sawmill")
        admin.incidentIds().shouldContainExactlyInAnyOrder(LumberIncidentType.entries.map(Enum<*>::name))
        admin.status("sawmill")!!.phase shouldBe LumberPhase.IDLE.name
        admin.startReindex("sawmill") shouldBe true
        graph.module.beforeReload("config_reload")
        admin.tickReindex("sawmill", 1) shouldBe null
        admin.startReindex("sawmill") shouldBe true
        admin.cancelReindex("sawmill") shouldBe true
        admin.start("sawmill", player) shouldBe true
        admin.status("sawmill")!!.phase shouldBe LumberPhase.FELLING.name
        admin.start("sawmill", player) shouldBe false
    }
})
