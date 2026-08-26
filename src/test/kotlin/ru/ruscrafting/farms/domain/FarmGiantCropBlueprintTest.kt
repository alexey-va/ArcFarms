package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

class FarmGiantCropBlueprintTest : FunSpec({
    test("every blueprint has unique bounded coordinates") {
        listOf("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "SWEET_BERRY_BUSH", "PUMPKIN", "MELON")
            .forEach { crop ->
                val voxels = FarmGiantCropBlueprint.voxels(crop)
                voxels.map { Triple(it.dx, it.dy, it.dz) }.toSet().size shouldBe voxels.size
                voxels.all { it.dx in -2..2 && it.dy in 0..4 && it.dz in -2..2 } shouldBe true
            }
    }

    test("large fruit sculptures remain within the recovery journal bound") {
        FarmGiantCropBlueprint.voxels("PUMPKIN").size shouldBe 85
        FarmGiantCropBlueprint.voxels("MELON").size shouldBe 85
    }

    test("unsupported crops fail before any world mutation") {
        shouldThrow<IllegalArgumentException> { FarmGiantCropBlueprint.voxels("CACTUS") }
    }
})
