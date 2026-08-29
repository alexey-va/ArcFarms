package ru.ruscrafting.farms.paper.farm.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmHarvestCropIndex
import ru.ruscrafting.farms.paper.FarmRuntime

class FarmHarvestGuidanceMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
    }

    afterEach { paper.close() }

    test("harvest guidance selects the nearest indexed mature crop of every unfinished type") {
        val nearestWheat = plantBed(world, 4, 0, Material.WHEAT, mature = true)
        val fartherWheat = plantBed(world, 8, 0, Material.WHEAT, mature = true)
        val immaturePotato = plantBed(world, 2, 1, Material.POTATOES, mature = false)
        val maturePotato = plantBed(world, 6, 1, Material.POTATOES, mature = true)
        val missingBerry = plantBed(world, 3, 2, Material.SWEET_BERRY_BUSH, mature = true).also { position ->
            world.getBlockAt(position.x, position.y + 1, position.z).type = Material.AIR
        }
        val matureBerry = plantBed(world, 7, 2, Material.SWEET_BERRY_BUSH, mature = true)
        val melon = FarmPlotPosition(world.name, 9, 65, 3).also { position ->
            world.getBlockAt(position.x, position.y, position.z).type = Material.MELON
        }
        plantBed(world, 1, 1, Material.POTATOES, mature = true) // Mature but deliberately not indexed.
        val runtime = runtime(
            world,
            required = linkedMapOf("WHEAT" to 5, "POTATOES" to 5, "SWEET_BERRY_BUSH" to 5, "MELON" to 1),
        )
        val guidance = FarmHarvestGuidance(
            FakeHarvestCropIndex(
                beds = setOf(nearestWheat, fartherWheat, immaturePotato, maturePotato, missingBerry, matureBerry),
                fixedCrops = setOf(melon),
            ),
        )

        val targets = guidance.targets(runtime, Location(world, 0.5, 65.0, 0.5))

        targets.associate { target -> target.crop.name to target.position } shouldContainExactly mapOf(
            "MELON" to melon,
            "POTATOES" to maturePotato,
            "SWEET_BERRY_BUSH" to matureBerry,
            "WHEAT" to nearestWheat,
        )
        targets.map { it.color.asRGB() }.shouldBeUnique()
        guidance.targets(runtime, Location(world, 0.5, 65.0, 0.5)).associate { it.crop to it.color } shouldBe
            targets.associate { it.crop to it.color }
    }

    test("harvest guidance omits completed quotas and indexed crops that are absent or immature") {
        val completedWheat = plantBed(world, 2, 0, Material.WHEAT, mature = true)
        val immaturePotato = plantBed(world, 3, 0, Material.POTATOES, mature = false)
        val missingBerry = FarmPlotPosition(world.name, 4, 64, 0).also { position ->
            world.getBlockAt(position.x, position.y, position.z).type = Material.FARMLAND
        }
        val runtime = runtime(
            world,
            required = linkedMapOf("WHEAT" to 5, "POTATOES" to 5, "SWEET_BERRY_BUSH" to 5),
            progress = mapOf("WHEAT" to 5),
        )
        val guidance = FarmHarvestGuidance(
            FakeHarvestCropIndex(setOf(completedWheat, immaturePotato, missingBerry), emptySet()),
        )

        guidance.targets(runtime, Location(world, 0.5, 65.0, 0.5)) shouldBe emptyList()
    }

    test("harvest guidance does not load chunks to inspect indexed crops") {
        val cropChunk = world.getChunkAt(2, 0)
        cropChunk.load()
        val matureWheat = plantBed(world, 40, 0, Material.WHEAT, mature = true)
        cropChunk.unload() shouldBe true
        world.isChunkLoaded(2, 0) shouldBe false
        val guidance = FarmHarvestGuidance(FakeHarvestCropIndex(setOf(matureWheat), emptySet()))

        guidance.targets(
            runtime(world, required = linkedMapOf("WHEAT" to 5)),
            Location(world, 0.5, 65.0, 0.5),
        ) shouldBe emptyList()
        world.isChunkLoaded(2, 0) shouldBe false
    }
})

private class FakeHarvestCropIndex(
    private val beds: Set<FarmPlotPosition>,
    private val fixedCrops: Set<FarmPlotPosition>,
) : FarmHarvestCropIndex {
    override fun beds(zoneId: String): Set<FarmPlotPosition> = beds
    override fun fixedCrops(zoneId: String): Set<FarmPlotPosition> = fixedCrops
}

private fun plantBed(
    world: WorldMock,
    x: Int,
    z: Int,
    crop: Material,
    mature: Boolean,
): FarmPlotPosition = FarmPlotPosition(world.name, x, 64, z).also { position ->
    world.getBlockAt(x, position.y, z).type = Material.FARMLAND
    val block = world.getBlockAt(x, position.y + 1, z)
    block.type = crop
    val age = block.blockData as Ageable
    age.age = if (mature) age.maximumAge else 0
    block.blockData = age
}

private fun runtime(
    world: WorldMock,
    required: LinkedHashMap<String, Int>,
    progress: Map<String, Int> = emptyMap(),
): FarmRuntime {
    val order = FarmOrder("harvest", required)
    val settings = mockk<FarmZoneSettings>(relaxed = true) {
        every { id } returns "communal_farm"
        every { crops } returns required.keys
    }
    return FarmRuntime(
        settings = settings,
        region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 31, 128, 31)),
        orders = mapOf(order.id to order),
        orderList = listOf(order),
        rules = FarmRules(listOf(50), 1, 1_000L),
        state = FarmShiftState(
            phase = FarmPhase.HARVESTING,
            orderId = order.id,
            progress = progress,
        ),
    )
}
