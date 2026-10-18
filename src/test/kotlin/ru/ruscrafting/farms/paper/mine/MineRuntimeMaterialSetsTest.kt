package ru.ruscrafting.farms.paper.mine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.mockk.mockk
import org.bukkit.Material
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.MineOrderSettings
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.config.ZoneReference
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.paper.ActivityRegion

class MineRuntimeMaterialSetsTest : FunSpec({
    test("one settings snapshot reuses immutable material sets with the existing mining-only semantics") {
        val runtime = MineRuntime(materialSettings(), mockk<ActivityRegion>(), 1_000, MineShiftState())
        val rails = runtime.railMaterials
        val mineable = runtime.mineableMaterials
        val ceiling = runtime.caveCeilingMaterials

        rails.toList() shouldBe listOf(Material.RAIL, Material.POWERED_RAIL)
        mineable.toList() shouldBe listOf(Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE)
        ceiling.toList() shouldBe listOf(Material.STONE, Material.COAL_ORE, Material.IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COAL_ORE, Material.DEEPSLATE_IRON_ORE)
        runtime.railMaterials shouldBeSameInstanceAs rails
        runtime.mineableMaterials shouldBeSameInstanceAs mineable
        runtime.caveCeilingMaterials shouldBeSameInstanceAs ceiling
        listOf(rails, mineable, ceiling).forEach { materials ->
            shouldThrow<UnsupportedOperationException> { (materials as MutableSet<Material>).add(Material.BEDROCK) }
        }
    }

    test("reload rebuilds material sets for each replacement settings identity") {
        val settings = materialSettings()
        val runtime = MineRuntime(settings, mockk<ActivityRegion>(), 1_000, MineShiftState())
        val registry = MineRuntimeRegistry().also { it.replace(listOf(runtime)) }
        val rails = runtime.railMaterials
        val mineable = runtime.mineableMaterials
        val ceiling = runtime.caveCeilingMaterials

        registry.reconfigure(listOf(runtime.copy(settings = settings.copy())))

        runtime.railMaterials shouldNotBeSameInstanceAs rails
        runtime.mineableMaterials shouldNotBeSameInstanceAs mineable
        runtime.caveCeilingMaterials shouldNotBeSameInstanceAs ceiling
        val replacement = settings.copy(
            miningOnly = false,
            materialWeights = linkedMapOf("DIORITE" to 80, "TUFF" to 20),
            baseMaterial = "ANDESITE",
            extractionRailMaterials = setOf("DETECTOR_RAIL"),
        )

        registry.reconfigure(listOf(runtime.copy(settings = replacement)))

        registry.snapshot().single() shouldBeSameInstanceAs runtime
        runtime.railMaterials shouldBe setOf(Material.DETECTOR_RAIL)
        runtime.mineableMaterials.toList() shouldBe listOf(Material.DIORITE, Material.TUFF)
        runtime.caveCeilingMaterials.toList() shouldBe listOf(Material.ANDESITE, Material.DIORITE, Material.TUFF)
        runtime.railMaterials shouldBeSameInstanceAs runtime.railMaterials
        runtime.mineableMaterials shouldBeSameInstanceAs runtime.mineableMaterials
        runtime.caveCeilingMaterials shouldBeSameInstanceAs runtime.caveCeilingMaterials
        rails shouldBe setOf(Material.RAIL, Material.POWERED_RAIL)
        mineable shouldBe setOf(Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE)
        ceiling shouldBe setOf(Material.STONE, Material.COAL_ORE, Material.IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COAL_ORE, Material.DEEPSLATE_IRON_ORE)
    }
})

private fun materialSettings() = MineZoneSettings(
    id = "mine",
    priority = 1,
    reference = ZoneReference("mine", null, CuboidBounds(0, 0, 0, 10, 10, 10)),
    permission = "arcfarms.mine",
    cartQuota = 16,
    hazardTrigger = 6,
    supportsRequired = 1,
    restoreSeconds = 60,
    temporaryMaterial = "COBBLESTONE",
    baseMaterial = "STONE",
    materialWeights = linkedMapOf("STONE" to 80, "COAL_ORE" to 10, "IRON_ORE" to 10, "COPPER_ORE" to 10),
    miningOnly = true,
    engineVersion = 2,
    orders = listOf(MineOrderSettings("ore_order", 1, 10, 1, listOf(MineIncidentType.CAVE_IN),
        miningResources = linkedSetOf("COAL", "IRON"))),
    incidentCountMin = 1,
    incidentCountMax = 1,
    extractionRailMaterials = linkedSetOf("RAIL", "POWERED_RAIL"),
)
