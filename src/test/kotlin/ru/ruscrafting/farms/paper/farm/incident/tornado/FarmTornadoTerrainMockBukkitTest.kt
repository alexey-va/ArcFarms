package ru.ruscrafting.farms.paper.farm.incident.tornado

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.FallingBlock
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture

class FarmTornadoTerrainMockBukkitTest : FunSpec({
    test("mutates indexed crop beds and restores their active crop snapshot") {
        FarmIncidentScenarioFixture.open().use { fixture ->
            val soil = fixture.world.getBlockAt(24, 64, 24).also {
                it.type = Material.FARMLAND
                it.getRelative(org.bukkit.block.BlockFace.UP).type = Material.WHEAT
            }
            val untouched = fixture.world.getBlockAt(26, 64, 24).also { it.type = Material.FARMLAND }
            val plot = FarmPlotPosition(fixture.world.name, soil.x, soil.y, soil.z)
            val ledger = FarmBlockLedger(fixture.plugin)
            ledger.replaceZoneIndex(soil.chunk, fixture.zone.id, listOf(soil), emptyList(), emptyList())
            val runtime = fixture.runtime(FarmShiftState(
                phase = FarmPhase.INCIDENT,
                incidentType = FarmIncidentType.TORNADO,
            ))
            runtime.settings = runtime.settings.copy(specialIncidents = runtime.settings.specialIncidents.copy(
                tornado = runtime.settings.specialIncidents.tornado.copy(radius = 8.0, debrisCount = 8),
            ))
            val terrain = FarmTornadoTerrain(
                fixture.plugin, ledger, FarmIncidentBedProvider { setOf(plot, FarmPlotPosition(fixture.world.name, 26, 64, 24)) },
            )
            terrain.update(runtime, Location(fixture.world, 24.5, 65.0, 24.5), tick = 10, pursuing = true)

            soil.type shouldBe Material.AIR
            soil.getRelative(org.bukkit.block.BlockFace.UP).type shouldBe Material.AIR
            untouched.type shouldBe Material.FARMLAND
            untouched.getRelative(org.bukkit.block.BlockFace.UP).type shouldBe Material.AIR
            ledger.record(soil)?.temporaryMutation shouldBe "tornado:${fixture.zone.id}"
            fixture.world.entities.filterIsInstance<FallingBlock>().size shouldBe 2
            fixture.world.entities.filterIsInstance<FallingBlock>().all { !it.dropItem && it.cancelDrop } shouldBe true

            terrain.clear(fixture.zone.id)
            soil.type shouldBe Material.FARMLAND
            soil.getRelative(org.bukkit.block.BlockFace.UP).type shouldBe Material.WHEAT
            fixture.world.entities.count(terrain::owns) shouldBe 0

            terrain.update(runtime, Location(fixture.world, 24.5, 65.0, 24.5), tick = 20, pursuing = true)
            soil.type shouldBe Material.AIR

            fixture.paper.performTicks(120)
            terrain.update(runtime, Location(fixture.world, 24.5, 65.0, 24.5), tick = 140, pursuing = false)
            soil.type shouldBe Material.FARMLAND
            soil.getRelative(org.bukkit.block.BlockFace.UP).type shouldBe Material.WHEAT
            ledger.record(soil)?.temporaryMutation shouldBe null
            terrain.cleanup()
        }
    }
})
