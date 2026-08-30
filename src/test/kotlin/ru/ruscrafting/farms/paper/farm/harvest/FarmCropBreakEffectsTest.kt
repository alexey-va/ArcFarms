package ru.ruscrafting.farms.paper.farm.harvest

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Color
import org.bukkit.Material

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
})
