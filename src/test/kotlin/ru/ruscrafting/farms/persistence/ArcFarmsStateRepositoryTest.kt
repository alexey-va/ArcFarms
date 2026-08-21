package ru.ruscrafting.farms.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.FarmDeliveryPosition
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmShiftState
import java.nio.file.Files

class ArcFarmsStateRepositoryTest : FunSpec({
    test("preparation incident type and delivery fields are additive to schema one") {
        val root = Files.createTempDirectory("arcfarms-state-legacy-test")
        val data = root.resolve("data")
        Files.createDirectories(data)
        Files.writeString(
            data.resolve("state.json"),
            """
            {
              "schemaVersion": 1,
              "farms": {
                "legacy_farm": {
                  "phase": "HARVESTING",
                  "sequence": 7,
                  "orderId": "legacy_order",
                  "progress": {"WHEAT": 3},
                  "incidentCrop": null,
                  "incidentProgress": 0,
                  "incidentRequired": 0,
                  "incidentResolved": false,
                  "goldenCrop": null,
                  "goldenUsed": false,
                  "startedAt": 1000,
                  "goldenEndsAt": 0,
                  "cooldownEndsAt": 0,
                  "outcome": "NONE",
                  "contributors": {}
                }
              },
              "lumbermills": {},
              "mines": {},
              "stats": {}
            }
            """.trimIndent(),
        )

        val loaded = ArcFarmsStateRepository(root).use { repository ->
            repository.load().also(repository::saveBlocking)
        }
        val farm = loaded.farms.getValue("legacy_farm")
        farm.phase shouldBe FarmPhase.HARVESTING
        farm.preparationProgress shouldBe 0
        farm.preparationRequired shouldBe 0
        farm.incidentType shouldBe null
        farm.deliveryPosition shouldBe null

        ArcFarmsStateRepository(root).use { repository -> repository.load() shouldBe loaded }
    }

    test("current farm state fields survive an atomic round trip") {
        val root = Files.createTempDirectory("arcfarms-state-current-test")
        val expected = ArcFarmsState(
            farms = mapOf(
                "delivery_farm" to FarmShiftState(
                    phase = FarmPhase.DELIVERY,
                    sequence = 8,
                    orderId = "current_order",
                    progress = mapOf("WHEAT" to 4),
                    preparationProgress = 3,
                    preparationRequired = 3,
                    deliveryPosition = FarmDeliveryPosition("world", 10.5, 64.0, -3.5),
                ),
                "drought_farm" to FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 9,
                    orderId = "current_order",
                    progress = mapOf("WHEAT" to 1),
                    preparationProgress = 3,
                    preparationRequired = 3,
                    incidentCrop = "WHEAT",
                    incidentType = FarmIncidentType.DROUGHT,
                    incidentRequired = 4,
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }
})
