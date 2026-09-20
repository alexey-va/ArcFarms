package ru.ruscrafting.farms.paper.mine.incident.rescue

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Villager
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.mine.*
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityKind
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityEffects
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteExpeditionTravel
import java.nio.file.Path

class MineLostMinerClickMockBukkitTest : FunSpec({
    test("nearby native miner click completes after flying back without a portal lease") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("world")
            val plugin = paper.createSimplePlugin("RescueClick")
            val player = paper.server.addPlayer("Rescuer")
            val port = immediateMinePort()
            val graph = testMineComponentGraph(plugin, CuboidRegionGateway(), port, { 1_000L }, ImmediateMineJournal())
            graph.module.rebuild(listOf(mineV2Settings()), mapOf("old_shafts" to MineShiftState(
                engineVersion = 2, phase = MinePhase.MINING, sequence = 1, orderId = "ore_run")), 5_000L)
            val runtime = requireNotNull(graph.registry.byId("old_shafts"))
            val surface = Location(world, 2.5, 64.0, 2.5)
            val target = Location(world, 105.5, 64.0, 105.5)
            val records = (100..108).flatMap { x -> (100..108).flatMap { z -> (64..68).map { y ->
                MineLostMinerMazeJournalRecord(world.name, runtime.settings.id, 1, x, y, z,
                    "minecraft:air", "minecraft:air", MineLostMinerMazeMarker.NONE, 405)
            } } }
            val scene = MineLostMinerMazeScene(world, runtime.settings.id, 1, surface, target, target, records)
            val maze = mockk<MineLostMinerMazeWorld>(relaxed = true)
            every { maze.scene(runtime) } returns scene
            val effects = mockk<MineIncidentEntityEffects>(relaxed = true)
            val travel = WorksiteExpeditionTravel(plugin, port, port, port, Path.of("data/test-rescue-returns"))
            val coordinator = ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator(
                MineTransitionCoordinator(port, port, port, null), port)
            val incident = MineLostMinerIncident(graph.registry, graph.index, coordinator, effects,
                maze, travel, mockk(relaxed = true), port)
            coordinator.start(runtime, MineIncidentType.LOST_MINER, 1, 1_000L,
                listOf(ObjectiveTargetCandidate("miner", WorksitePosition(world.name, 2, 63, 2), ObjectiveTargetRole("lost_miner"), 0))) shouldBe true
            val miner = world.spawn(target, Villager::class.java)
            every { effects.identity(miner) } returns MineIncidentEntityIdentity(
                MineIncidentEntityKind.MINER, runtime.settings.id, runtime.state.sequence, "miner")

            fun click() = incident.onInteractEntity(PlayerInteractEntityEvent(player, miner, EquipmentSlot.HAND))
            player.teleport(surface)
            click() shouldBe true
            runtime.state.phase shouldBe MinePhase.INCIDENT
            player.teleport(target)
            player.gameMode = GameMode.SPECTATOR
            click() shouldBe true
            runtime.state.phase shouldBe MinePhase.INCIDENT
            player.gameMode = GameMode.CREATIVE
            player.teleport(target.clone().add(0.0, 2.0, 0.0))
            scene.contains(player.location) shouldBe true
            travel.retains(player) shouldBe false
            click() shouldBe true
            runtime.state.phase shouldBe MinePhase.MINING
            player.location shouldBe surface
            val contribution = runtime.state.contributors[player.uniqueId]
            click() shouldBe true // Retained scene consumes stale clicks without counting them.
            runtime.state.contributors[player.uniqueId] shouldBe contribution
        } catch (error: Throwable) { throw AssertionError("Rescue click regression must execute completely", error) } finally { paper.close() }
    }
})
