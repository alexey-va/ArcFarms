package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import org.bukkit.Material

class FarmGroundSpreadPolicyTest : FunSpec({
    context("inside a farm") {
        withData(
            nameFn = { it.name.lowercase() },
            Material.GRASS_BLOCK,
            Material.MYCELIUM,
            Material.PODZOL,
        ) { spreadingMaterial ->
            FarmGroundSpreadPolicy.blocks(
                insideFarm = true,
                source = Material.DIRT,
                result = spreadingMaterial,
            ) shouldBe true
        }
    }

    test("a blocked farm source cannot spread through an unusual resulting state") {
        FarmGroundSpreadPolicy.blocks(
            insideFarm = true,
            source = Material.MYCELIUM,
            result = Material.DIRT,
        ) shouldBe true
    }

    test("ordinary block changes inside a farm remain untouched") {
        FarmGroundSpreadPolicy.blocks(
            insideFarm = true,
            source = Material.DIRT,
            result = Material.FARMLAND,
        ) shouldBe false
    }

    test("spread outside a farm remains untouched") {
        FarmGroundSpreadPolicy.blocks(
            insideFarm = false,
            source = Material.GRASS_BLOCK,
            result = Material.GRASS_BLOCK,
        ) shouldBe false
    }
})
