package ru.ruscrafting.farms.paper.farm.harvest

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import ru.ruscrafting.farms.config.FarmCropBreakEffectsSettings

class FarmCropBreakEffectsTest : FunSpec({
    test("pumpkins use a warm rind burst with a leafy accent") {
        FarmCropBreakEffects.palette(Material.PUMPKIN) shouldBe FarmCropBreakEffectPalette(
            Color.fromRGB(230, 128, 37),
            Color.fromRGB(92, 126, 49),
        )
    }

    test("melons use rind and pulp colors instead of a generic tnt burst") {
        FarmCropBreakEffects.palette(Material.MELON) shouldBe FarmCropBreakEffectPalette(
            Color.fromRGB(121, 168, 75),
            Color.fromRGB(217, 79, 79),
        )
    }

    test("ordinary fixed crops share the designed break burst") {
        Material.entries.filter(FarmCropBreakEffects::supportsHarvestBurst).toSet() shouldBe setOf(
            Material.MELON,
            Material.PUMPKIN,
        )
    }

    test("zero particle counts disable every harvest burst layer") {
        val world = mockk<World>(relaxed = true)
        val block = mockk<Block> {
            every { location } returns Location(world, 2.0, 64.0, 2.0)
        }

        FarmCropBreakEffects.emitHarvest(
            block = block,
            crop = Material.MELON,
            particles = true,
            sounds = false,
            effectSettings = FarmCropBreakEffectsSettings(0, 0, 0, 0),
        )

        confirmVerified(world)
    }
})
