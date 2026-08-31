package ru.ruscrafting.farms.paper.farm.placement

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmDeliverySettings
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmDeliveryPosition
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import java.util.Random

class FarmPlacementServiceMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var placement: FarmPlacementService

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
        placement = FarmPlacementService(
            plugin = paper.createSimplePlugin("FarmPlacementTest"),
            blockRegistry = mockk<FarmBlockRegistry>(relaxed = true),
            points = mockk<FarmPointProvider>(relaxed = true),
            debug = mockk<ArcFarmsDebug>(relaxed = true),
            random = Random(1L),
        )
    }

    afterEach { paper.close() }

    test("open sky accepts surface positions and rejects a roof") {
        world.getBlockAt(0, 64, 0).type = Material.GRASS_BLOCK
        val spawn = Location(world, 0.5, 65.0, 0.5)

        placement.isOpenToSky(spawn) shouldBe true

        world.getBlockAt(0, 70, 0).type = Material.OAK_PLANKS
        placement.isOpenToSky(spawn) shouldBe false
    }

    test("outdoor bed accepts its crop but rejects terrain above it") {
        val soil = world.getBlockAt(0, 64, 0).apply { type = Material.FARMLAND }
        world.getBlockAt(0, 65, 0).type = Material.WHEAT

        FarmSurfacePolicy.isOutdoorBed(soil) shouldBe true

        world.getBlockAt(0, 70, 0).type = Material.STONE
        FarmSurfacePolicy.isOutdoorBed(soil) shouldBe false
    }

    test("delivery crates fall back to safe loaded ground when the bed index is empty") {
        for (x in 0..15) for (z in 0..15) world.getBlockAt(x, 64, z).type = Material.STONE
        val delivery = mockk<FarmDeliverySettings> {
            every { crates } returns 3
            every { spawnRadius } returns 6
            every { minCrateSpacing } returns 3.0
            every { radius } returns 2.0
        }
        val zone = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "communal_farm"
            every { this@mockk.delivery } returns delivery
            every { placementSearchRadius } returns 8
            every { placementReceivingExclusionPadding } returns 1.5
            every { crops } returns setOf("WHEAT")
        }
        val runtime = FarmRuntime(
            settings = zone,
            region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = FarmRules(listOf(50), 1, 1_000L),
            state = FarmShiftState(sequence = 9L),
        )
        val service = FarmPlacementService(
            plugin = paper.createSimplePlugin("FarmPlacementFallbackTest"),
            blockRegistry = mockk(relaxed = true),
            points = FarmPointProvider { _, kind ->
                check(kind == FarmPointKind.RECEIVING)
                FarmPointPosition(world.name, 14.5, 65.0, 14.5)
            },
            debug = mockk(relaxed = true),
            random = Random(1L),
        )

        val locations = service.deliveryCrateLocations(
            runtime,
            FarmDeliveryPosition(world.name, 7.5, 65.0, 7.5),
        )

        locations.size shouldBe 3
        locations.all(service::isOpenToSky) shouldBe true
    }
})
