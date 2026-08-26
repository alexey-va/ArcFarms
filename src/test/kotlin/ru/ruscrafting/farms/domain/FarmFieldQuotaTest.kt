package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmFieldQuotaTest : FunSpec({
    test("field quota rounds upward and never asks for the unreachable tail") {
        FarmFieldQuota.required(100, 90) shouldBe 90
        FarmFieldQuota.required(11, 90) shouldBe 10
        FarmFieldQuota.required(1, 90) shouldBe 1
    }

    test("giant crop blueprints are bounded and never overlap themselves") {
        listOf("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "PUMPKIN", "MELON")
            .forEach { crop ->
            val voxels = FarmGiantCropBlueprint.voxels(crop)
            voxels.size shouldBe voxels.map { Triple(it.dx, it.dy, it.dz) }.distinct().size
            (voxels.size in 32..128) shouldBe true
            }
    }
})
