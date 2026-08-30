package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.MineIncidentState
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminHandler

class MineGuidanceAdminRewardMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("every mine phase and incident exposes a concrete next action") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        val player = paper.server.addPlayer("GuidedMiner")
        player.teleport(Location(world, 4.5, 64.0, 4.5))
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineGuidanceTest"), CuboidRegionGateway(), immediateMinePort(),
            clock = { 1_000L }, journal = ImmediateMineJournal(), random = java.util.Random(4),
        )
        graph.module.rebuild(listOf(mineV2Settings()), emptyMap(), 5_000L)
        val runtime = graph.registry.byId("old_shafts")!!
        val base = MineShiftEngine.start(runtime.state, runtime.nextOrder().domain(), runtime.rules(), 1_000L).state
        val plain = PlainTextComponentSerializer.plainText()

        listOf(MinePhase.PROSPECTING, MinePhase.MINING, MinePhase.LOADING, MinePhase.EXTRACTION).forEach { phase ->
            runtime.state = base.copy(phase = phase)
            val view = graph.guidance.view(player, runtime)
            plain.serialize(view.title).length shouldBeGreaterThan 0
            plain.serialize(view.subtitle).length shouldBeGreaterThan 0
            plain.serialize(view.barName).length shouldBeGreaterThan 0
        }
        MineIncidentType.entries.forEach { type ->
            runtime.state = base.copy(
                phase = MinePhase.INCIDENT,
                resumePhase = MinePhase.MINING,
                incident = MineIncidentState(type, required = 2),
            )
            plain.serialize(graph.guidance.view(player, runtime).subtitle).length shouldBeGreaterThan 0
        }
    }

    test("mine admin boundary reports status incidents and bounded reindex without leaking runtimes") {
        val world = paper.server.addSimpleWorld("world")
        world.getBlockAt(1, 64, 1).type = Material.STONE
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineAdminTest"), CuboidRegionGateway(), immediateMinePort(),
            clock = { 1_000L }, journal = ImmediateMineJournal(), random = java.util.Random(5),
        )
        graph.module.rebuild(listOf(mineV2Settings()), emptyMap(), 5_000L)
        val admin: WorksiteAdminHandler = graph.admin

        admin.kind shouldBe ActivityKind.MINE
        admin.zoneIds() shouldBe listOf("old_shafts")
        admin.incidentIds().shouldContainExactlyInAnyOrder(MineIncidentType.entries.map(Enum<*>::name))
        admin.status("old_shafts")!!.phase shouldBe MinePhase.IDLE.name
        admin.startReindex("old_shafts") shouldBe true
        admin.startReindex("old_shafts") shouldBe false
        admin.tickReindex("old_shafts", 262_144)!!.scannedBlocks.shouldBeGreaterThan(0L)
    }
})
