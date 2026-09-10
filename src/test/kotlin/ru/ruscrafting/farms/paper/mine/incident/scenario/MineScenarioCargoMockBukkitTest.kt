package ru.ruscrafting.farms.paper.mine.incident.scenario

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.ItemDisplay
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.MineIncidentState
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineScenarioAction
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.*
import ru.ruscrafting.farms.paper.mine.MineRuntime

class MineScenarioCargoMockBukkitTest : FunSpec({
    test("a leased target has one moving cargo display and release removes it") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("world")
            val player = paper.server.addPlayer("CargoMiner")
            player.teleport(world.spawnLocation)
            val cargo = MineScenarioCargo(paper.createSimplePlugin("MineCargoTest"))
            val runtime = mockk<MineRuntime>(relaxed = true)
            every { runtime.settings.id } returns "mine"
            val planned = ObjectiveTargetPool.plan(WorksiteObjectiveKey("mine", "cargo", 1), 1,
                listOf(ObjectiveTargetCandidate("crate", WorksitePosition("world", 0, 64, 0), ObjectiveTargetRole("carry"), 0)))
            val leased = ObjectiveTargetPool.lease(planned, "crate", player.uniqueId, 1, 1000).state
            var state = MineShiftState(sequence = 1, incident = MineIncidentState(MineIncidentType.OLD_WAREHOUSE, 3), objective = leased)
            every { runtime.state } answers { state }
            cargo.reconcile(runtime, MineScenarioAction.CARRY, "OAK_PLANKS")
            val display = world.entities.filterIsInstance<ItemDisplay>().single()
            display.itemStack.type shouldBe Material.OAK_PLANKS
            val start = display.location
            player.teleport(player.location.add(2.0, 0.0, 0.0))
            cargo.reconcile(runtime, MineScenarioAction.CARRY, "OAK_PLANKS")
            world.entities.filterIsInstance<ItemDisplay>().single().uniqueId shouldBe display.uniqueId
            (display.location.x - start.x) shouldBe 2.0
            state = state.copy(objective = ObjectiveTargetPool.release(leased, player.uniqueId).state)
            cargo.reconcile(runtime, MineScenarioAction.CARRY, "OAK_PLANKS")
            world.entities.filterIsInstance<ItemDisplay>().size shouldBe 0
            state = state.copy(objective = leased)
            cargo.reconcile(runtime, MineScenarioAction.CARRY, "OAK_PLANKS")
            cargo.release(player)
            world.entities.filterIsInstance<ItemDisplay>().size shouldBe 0
        } finally { paper.close() }
    }
})
