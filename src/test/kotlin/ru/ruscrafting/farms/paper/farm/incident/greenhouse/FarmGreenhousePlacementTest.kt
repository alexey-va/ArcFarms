package ru.ruscrafting.farms.paper.farm.incident.greenhouse

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture

class FarmGreenhousePlacementTest : FunSpec({
    test("prepares indexed uneven beds and restores their crop journal without touching nearby structure") {
        FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(FarmShiftState(
                phase = FarmPhase.INCIDENT,
                incidentType = FarmIncidentType.HELL_GREENHOUSE,
            ))
            val soil = fixture.world.getBlockAt(24, 64, 24).also {
                it.type = Material.FARMLAND
                it.getRelative(org.bukkit.block.BlockFace.UP).type = Material.WHEAT
            }
            val upperSoil = fixture.world.getBlockAt(25, 65, 24).also {
                it.type = Material.FARMLAND
                it.getRelative(org.bukkit.block.BlockFace.UP).type = Material.CARROTS
            }
            val lowerSoil = fixture.world.getBlockAt(26, 63, 24).also {
                it.type = Material.FARMLAND
                it.getRelative(org.bukkit.block.BlockFace.UP).type = Material.POTATOES
            }
            val structure = fixture.world.getBlockAt(30, 65, 30).also { it.type = Material.OAK_PLANKS }
            val ledger = FarmBlockLedger(fixture.plugin)
            ledger.replaceZoneIndex(soil.chunk, runtime.settings.id, listOf(soil, upperSoil, lowerSoil), emptyList(), emptyList())
            val placement = FarmGreenhousePlacement(ledger)
            val site = placement.inspect(runtime, Location(fixture.world, 24.5, 65.0, 24.5))
            site.failure shouldBe null
            placement.prepare(runtime, site)
            soil.getRelative(org.bukkit.block.BlockFace.UP).type shouldBe Material.AIR
            upperSoil.type shouldBe Material.AIR
            lowerSoil.getRelative(org.bukkit.block.BlockFace.UP).type shouldBe Material.FARMLAND
            ledger.record(lowerSoil)?.temporaryMutation shouldBe "greenhouse:${runtime.settings.id}"
            structure.type shouldBe Material.OAK_PLANKS
            placement.clear(runtime.settings.id)
            soil.type shouldBe Material.FARMLAND
            soil.getRelative(org.bukkit.block.BlockFace.UP).type shouldBe Material.WHEAT
            upperSoil.type shouldBe Material.FARMLAND
            upperSoil.getRelative(org.bukkit.block.BlockFace.UP).type shouldBe Material.CARROTS
            lowerSoil.getRelative(org.bukkit.block.BlockFace.UP).type shouldBe Material.POTATOES
            // Admin unmanage must repair temporary terrain before discarding its journal.
            placement.prepare(runtime, placement.inspect(runtime, Location(fixture.world, 24.5, 65.0, 24.5)))
            ledger.remove(soil) shouldBe true
            soil.getRelative(org.bukkit.block.BlockFace.UP).type shouldBe Material.WHEAT
            ledger.record(soil) shouldBe null
            placement.clear(runtime.settings.id)
        }
    }

    test("rejects a greenhouse footprint with a foreign temporary journal") {
        FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(FarmShiftState(
                phase = FarmPhase.INCIDENT,
                incidentType = FarmIncidentType.HELL_GREENHOUSE,
            ))
            val soil = fixture.world.getBlockAt(24, 64, 24).also { it.type = Material.FARMLAND }
            val ledger = FarmBlockLedger(fixture.plugin)
            ledger.replaceZoneIndex(soil.chunk, runtime.settings.id, listOf(soil), emptyList(), emptyList())
            ledger.beginTemporaryRemoval(listOf(soil), runtime.settings.id, "other:incident", 100L)
            val site = FarmGreenhousePlacement(ledger).inspect(runtime, Location(fixture.world, 24.5, 65.0, 24.5))
            site.failure?.reason shouldBe "journal"
        }
    }
})
