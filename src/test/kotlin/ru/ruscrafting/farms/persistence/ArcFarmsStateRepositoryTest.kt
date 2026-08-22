package ru.ruscrafting.farms.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmDeliveryPosition
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import java.nio.file.Files
import java.util.concurrent.ExecutionException

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
        val patch = listOf(
            FarmPlotPosition("world", 10, 63, -3),
            FarmPlotPosition("world", 11, 63, -3),
        )
        val expected = ArcFarmsState(
            farms = mapOf(
                "delivery_farm" to FarmShiftState(
                    phase = FarmPhase.DELIVERY,
                    sequence = 8,
                    orderId = "current_order",
                    progress = mapOf("WHEAT" to 4),
                    preparationPatch = patch,
                    preparationCrop = "WHEAT",
                    preparationReleased = true,
                    tilledPlots = patch.toSet(),
                    plantedPlots = patch.toSet(),
                    preparationProgress = 2,
                    plantingProgress = 2,
                    preparationRequired = 2,
                    deliveryPosition = FarmDeliveryPosition("world", 10.5, 64.0, -3.5),
                ),
                "drought_farm" to FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 9,
                    orderId = "current_order",
                    progress = mapOf("WHEAT" to 1),
                    preparationPatch = patch,
                    preparationCrop = "WHEAT",
                    preparationReleased = true,
                    tilledPlots = patch.toSet(),
                    plantedPlots = patch.toSet(),
                    preparationProgress = 2,
                    plantingProgress = 2,
                    preparationRequired = 2,
                    incidentCrop = "WHEAT",
                    incidentType = FarmIncidentType.DROUGHT,
                    incidentRequired = 4,
                    droughtPlots = setOf(patch.first()),
                ),
                "care_farm" to FarmShiftState(
                    phase = FarmPhase.CARE,
                    sequence = 10,
                    orderId = "current_order",
                    progress = mapOf("WHEAT" to 0),
                    preparationPatch = patch,
                    preparationCrop = "WHEAT",
                    preparationReleased = true,
                    tilledPlots = patch.toSet(),
                    plantedPlots = patch.toSet(),
                    preparationProgress = 2,
                    plantingProgress = 2,
                    preparationRequired = 2,
                    careType = FarmCareType.WEEDS,
                    careTargets = listOf(
                        FarmCareTarget(0, FarmCareRole.WEED_ROOT, FarmPointPosition("world", 10.5, 64.0, -2.5), required = 2),
                    ),
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("state persistence rejects progress outside the selected patch") {
        val root = Files.createTempDirectory("arcfarms-state-invalid-patch-test")
        val patch = FarmPlotPosition("world", 10, 63, -3)
        val escaped = FarmPlotPosition("world", 20, 63, -3)
        val invalid = ArcFarmsState(
            farms = mapOf(
                "farm" to FarmShiftState(
                    phase = FarmPhase.PREPARATION,
                    orderId = "order",
                    preparationPatch = listOf(patch),
                    preparationCrop = "WHEAT",
                    tilledPlots = setOf(escaped),
                    preparationProgress = 1,
                    preparationRequired = 1,
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { repository ->
            val failure = shouldThrow<ExecutionException> { repository.saveBlocking(invalid) }
            (failure.cause is IllegalArgumentException) shouldBe true
        }
    }
})
