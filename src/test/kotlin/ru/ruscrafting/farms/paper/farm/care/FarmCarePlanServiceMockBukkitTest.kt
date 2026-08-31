package ru.ruscrafting.farms.paper.farm.care

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmLocationOverrides
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorld
import ru.ruscrafting.farms.paper.farm.placement.FarmPlacementService
import java.util.random.RandomGenerator
import kotlin.math.abs

class FarmCarePlanServiceMockBukkitTest : FunSpec({
    test("scarecrow targets occupy the middle field ring and remain spaced") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("sp11")
            for (chunkX in 0..6) world.getChunkAt(chunkX, 0).load()
            val beds = (0..96 step 4).map { x ->
                world.getBlockAt(x, 64, 4).type = Material.FARMLAND
                FarmPlotPosition(world.name, x, 64, 4)
            }
            val registry = mockk<FarmBlockRegistry> {
                every { beds("communal_farm") } returns beds.toSet()
            }
            val settings = mockk<FarmZoneSettings> {
                every { id } returns "communal_farm"
                every { careTargetsPerPlayer } returns 15
                every { careTargetsMax } returns 45
                every { scarecrowTargetCount } returns 5
                every { scarecrowMinSpacing } returns 12.0
            }
            val runtime = FarmRuntime(
                settings = settings,
                region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 60, 0, 100, 70, 8)),
                orders = emptyMap(),
                orderList = emptyList(),
                rules = FarmRules(listOf(50), 1, 1_000),
                state = FarmShiftState(
                    phase = FarmPhase.HARVESTING,
                    sequence = 7,
                    placementSequence = 11,
                    preparationPatch = beds.take(3),
                ),
            )
            val service = FarmCarePlanService(
                debug = ArcFarmsDebug({ false }) {},
                registry = registry,
                placement = mockk<FarmPlacementService>(relaxed = true),
                points = FarmPointProvider { _, _ -> error("No explicit point expected") },
                overrides = { FarmLocationOverrides() },
                random = mockk<RandomGenerator>(relaxed = true),
                moleBurrow = mockk<FarmMoleBurrowWorld>(relaxed = true),
                participantCount = { 1 },
                log = { _, _ -> },
            )

            val targets = requireNotNull(service.targets(runtime, FarmCareType.SCARECROWS, null))

            targets.size shouldBe 5
            targets.all { it.role == FarmCareRole.SCARECROW } shouldBe true
            val xs = targets.map { it.position.x.toInt() }
            targets.any { target -> beds.take(3).none { it.x == target.position.x.toInt() } } shouldBe true
            xs.none { it in 40..56 } shouldBe true
            (xs.count { it in 8..88 } >= 4) shouldBe true
            xs.indices.all { first ->
                (first + 1 until xs.size).all { second -> abs(xs[first] - xs[second]) >= 12 }
            } shouldBe true
        } finally {
            paper.close()
        }
    }
})
