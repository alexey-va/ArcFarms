package ru.ruscrafting.farms.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.MineIncidentType

class MineConfigTest : FunSpec({
    val order = MineOrderSettings(
        "deep_vein",
        prospectingRequired = 3,
        miningRequired = 12,
        loadingRequired = 4,
        incidentTypes = listOf(MineIncidentType.CAVE_IN, MineIncidentType.GAS_LEAK, MineIncidentType.FLOODING),
    )

    test("mine V2 order exposes bounded phase quotas") {
        order.domain().id shouldBe "deep_vein"
        order.miningRequired shouldBe 12
    }

    test("mine V2 zone requires at least one complete order") {
        shouldThrow<IllegalArgumentException> {
            MineZoneSettings(
                id = "mine",
                priority = 1,
                reference = ZoneReference("world", null, CuboidBounds(0, 0, 0, 10, 10, 10)),
                permission = "arcfarms.mine",
                cartQuota = 8,
                hazardTrigger = 4,
                supportsRequired = 1,
                restoreSeconds = 60,
                temporaryMaterial = "COBBLESTONE",
                baseMaterial = "STONE",
                materialWeights = linkedMapOf("STONE" to 1),
                engineVersion = 2,
            )
        }
    }
})
