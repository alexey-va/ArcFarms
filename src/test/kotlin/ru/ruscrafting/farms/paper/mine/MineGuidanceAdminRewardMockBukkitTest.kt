package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.MineIncidentState
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineScenarioPlacement
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveState
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.paper.asWorksitePorts
import ru.ruscrafting.farms.paper.mine.incident.scenario.MineScenarioRooms
import ru.ruscrafting.farms.paper.mine.presentation.MineGuidanceSource
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

    test("scenario guidance outside its room points to the entrance and exposes stage progress") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("ScenarioMiner")
        player.teleport(Location(world, 4.5, 64.0, 4.5))
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineScenarioGuidanceTest"), CuboidRegionGateway(), immediateMinePort(),
            clock = { 1_000L }, journal = ImmediateMineJournal(), random = java.util.Random(4),
        )
        graph.module.rebuild(listOf(mineV2Settings()), emptyMap(), 5_000L)
        val runtime = graph.registry.byId("old_shafts")!!
        val placement = MineScenarioPlacement(
            origin = WorksitePosition("world", 20, 64, 20),
            entrance = WorksitePosition("world", 20, 64, 24),
            floorId = "old_shafts",
            destination = WorksitePosition("world", 47, 88, 78),
            destinationFloorId = "upper",
        )
        runtime.state = runtime.state.copy(
            phase = MinePhase.INCIDENT,
            incident = MineIncidentState(MineIncidentType.CAVE_IN, required = 3, progress = 1, scenarioPlacement = placement),
        )

        val view = graph.guidance.view(player, runtime)
        view.targets.map { it.id } shouldBe listOf("scenario_entrance")
        view.targets.single().position shouldBe Location(world, 20.5, 64.35, 24.5)
        view.barProgress shouldBe (1f / 3f)
        view.sidebarRows.size shouldBe 4
    }

    test("leased multi-floor carry guidance keeps its destination and passes placeholders to the locale") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("CarryMiner")
        player.teleport(Location(world, 30.5, 88.0, 30.5))
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineScenarioCarryGuidanceTest"), CuboidRegionGateway(), immediateMinePort(),
            clock = { 1_000L }, journal = ImmediateMineJournal(), random = java.util.Random(4),
        )
        graph.module.rebuild(listOf(mineV2Settings()), emptyMap(), 5_000L)
        val runtime = graph.registry.byId("old_shafts")!!
        val destination = WorksitePosition("world", 47, 88, 78)
        val placement = MineScenarioPlacement(
            origin = WorksitePosition("world", 20, 64, 20),
            entrance = WorksitePosition("world", 20, 64, 24),
            floorId = "old_shafts",
            destination = destination,
            destinationFloorId = "upper",
        )
        runtime.state = runtime.state.copy(
            phase = MinePhase.INCIDENT,
            incident = MineIncidentState(MineIncidentType.INJURED_MINER, required = 2, progress = 1, scenarioPlacement = placement),
            objective = WorksiteObjectiveState(
                WorksiteObjectiveKey("old_shafts", "rescue", 1), 1,
                listOf(ObjectiveTargetState("stretcher", WorksitePosition("world", 21, 64, 21), ObjectiveTargetRole("stretcher"), 0,
                    ObjectiveTargetStatus.LEASED, player.uniqueId)),
            ),
        )
        val rooms = mockk<MineScenarioRooms>(relaxed = true) {
            every { at(any()) } returns null
        }
        val port = immediateMinePort()
        val locale = mockk<ArcFarmsLocale>(relaxed = true) {
            every { text(any()) } answers { Component.text(firstArg<Any?>()?.toString().orEmpty()) }
            every { renderPath(any(), any(), any()) } answers {
                val values = thirdArg<Map<String, Component>>()
                Component.text(firstArg<String>() + values.entries.joinToString("|") { "${it.key}=${PlainTextComponentSerializer.plainText().serialize(it.value)}" })
            }
        }
        val source = MineGuidanceSource(graph.registry, port.asWorksitePorts().audience, locale, rooms = rooms)

        val view = requireNotNull(source.view(player.uniqueId))
        view.targets.single().position.blockX shouldBe destination.x
        view.targets.single().position.blockY shouldBe destination.y
        view.targets.single().position.blockZ shouldBe destination.z
        PlainTextComponentSerializer.plainText().serialize(view.subtitle).contains("destination=mine-lift.floors.upper") shouldBe true
        source.participants().map { it.uniqueId } shouldBe listOf(player.uniqueId)
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
        graph.module.beforeReload("config_reload")
        admin.tickReindex("old_shafts", 262_144) shouldBe null
        admin.startReindex("old_shafts") shouldBe true
        admin.tickReindex("old_shafts", 262_144)!!.scannedBlocks.shouldBeGreaterThan(0L)
    }
})
