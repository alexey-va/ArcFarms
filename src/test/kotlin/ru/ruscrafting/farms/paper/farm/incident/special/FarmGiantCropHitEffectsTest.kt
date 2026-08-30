package ru.ruscrafting.farms.paper.farm.incident.special

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Color
import org.bukkit.Material

class FarmGiantCropHitEffectsTest : FunSpec({
    test("pumpkins use a warm rind burst with a leafy accent") {
        FarmGiantCropHitEffects.palette(Material.PUMPKIN) shouldBe FarmGiantCropEffectPalette(
            Color.fromRGB(230, 128, 37),
            Color.fromRGB(92, 126, 49),
        )
    }

    test("melons use rind and pulp colors instead of a generic tnt burst") {
        FarmGiantCropHitEffects.palette(Material.MELON) shouldBe FarmGiantCropEffectPalette(
            Color.fromRGB(121, 168, 75),
            Color.fromRGB(217, 79, 79),
        )
    }
})
