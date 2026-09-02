package ru.ruscrafting.farms.paper.farm.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import ru.ruscrafting.farms.domain.FARM_CHANNEL_ROUTE_NAME
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture
import ru.ruscrafting.farms.paper.fixtures.requiredMockBukkitScenario
import kotlin.math.abs

class FarmChannelsLifecycleMockBukkitIntegrationTest : FunSpec({
    test("a marked connected trench can be dug in any order and fills with real water before completion") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = (10 until 62).map { x -> FarmPlotPosition(fixture.world.name, x, 64, 10) }
            beds.forEach { plot ->
                val soil = fixture.world.getBlockAt(plot.x, plot.y, plot.z)
                soil.type = Material.FARMLAND
                val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
                crop.type = Material.WHEAT
                val age = crop.blockData as Ageable
                age.age = age.maximumAge
                crop.blockData = age
            }
            val runtime = fixture.runtime(
                FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 71,
                    placementSequence = 9,
                    orderId = "bakery_supply",
                    incidentType = FarmIncidentType.CHANNELS,
                ),
            )
            val controller = fixture.channels(
                runtime,
                beds,
                FarmPointPosition(fixture.world.name, 9.5, 65.0, 10.5),
            )
            val worker = fixture.paper.addPlayer("DitchDigger")

            controller.ensure(runtime)
            val planned = requireNotNull(runtime.state.specialIncident)
            planned.routeName shouldBe FARM_CHANNEL_ROUTE_NAME
            planned.points shouldHaveSize fixture.zone.specialIncidents.channelSegmentCount
            planned.points.zipWithNext().all { (left, right) ->
                abs(left.x - right.x) + abs(left.z - right.z) == 1.0
            } shouldBe true
            worker.inventory.itemInMainHand.type shouldBe Material.IRON_SHOVEL
            val source = planned.points.first()
            fixture.world.getBlockAt(source.x.toInt(), source.y.toInt() - 1, source.z.toInt()).type shouldBe Material.WATER
            planned.points.drop(1).forEach { point ->
                fixture.world.getBlockAt(point.x.toInt(), point.y.toInt() - 1, point.z.toInt()).type shouldBe Material.DIRT
            }
            planned.solution shouldBe setOf(0)
            planned.active shouldBe setOf(0)

            fun dig(index: Int) {
                val point = requireNotNull(runtime.state.specialIncident).points[index]
                val soil = fixture.world.getBlockAt(point.x.toInt(), point.y.toInt() - 1, point.z.toInt())
                controller.handleChannelBreak(runtime, worker, soil) shouldBe true
                soil.type shouldBe Material.AIR
            }

            dig(2)
            fixture.runDelayedTasks() shouldBe emptyList()
            dig(1)
            fixture.runDelayedTasks() shouldBe listOf(fixture.zone.specialIncidents.channelFlowIntervalTicks.toLong())
            requireNotNull(runtime.state.specialIncident).active shouldBe setOf(0, 1)
            (3 until planned.points.size).forEach(::dig)

            while (requireNotNull(runtime.state.specialIncident).active.size < planned.points.size) {
                fixture.runDelayedTasks() shouldBe listOf(fixture.zone.specialIncidents.channelFlowIntervalTicks.toLong())
            }
            requireNotNull(runtime.state.specialIncident).points.forEach { point ->
                fixture.world.getBlockAt(point.x.toInt(), point.y.toInt() - 1, point.z.toInt()).type shouldBe Material.WATER
            }
            fixture.runDelayedTasks() shouldBe listOf(fixture.zone.specialIncidents.channelCompletionDelayTicks.toLong())
            runtime.state.phase shouldBe FarmPhase.HARVESTING
            runtime.state.incidentType shouldBe null
        }
    }
    }
})
