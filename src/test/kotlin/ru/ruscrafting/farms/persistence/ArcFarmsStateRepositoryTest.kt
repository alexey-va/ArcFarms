package ru.ruscrafting.farms.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmCropDamage
import ru.ruscrafting.farms.domain.FarmDeliveryPosition
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.FarmSpecialIncidentState
import ru.ruscrafting.farms.domain.FarmSeederStage
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.FarmRewardItem
import ru.ruscrafting.farms.domain.PendingFarmReward
import ru.ruscrafting.farms.domain.PlayerActivityStats
import ru.ruscrafting.farms.domain.WeeklyActivityContribution
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ExecutionException

class ArcFarmsStateRepositoryTest : FunSpec({
    test("legacy state loads with no paused farm order cycles") {
        val root = Files.createTempDirectory("arcfarms-state-paused-legacy-test")
        val data = root.resolve("data")
        Files.createDirectories(data)
        Files.writeString(
            data.resolve("state.json"),
            """
            {
              "schemaVersion": 1,
              "farms": {},
              "lumbermills": {},
              "mines": {},
              "stats": {}
            }
            """.trimIndent(),
        )

        val loaded = ArcFarmsStateRepository(root).use(ArcFarmsStateRepository::load)

        loaded.pausedFarmZones shouldBe emptySet()
    }

    test("paused farm order cycles survive an atomic state round trip") {
        val root = Files.createTempDirectory("arcfarms-state-paused-roundtrip-test")
        val expected = ArcFarmsState(pausedFarmZones = setOf("communal_farm"))

        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("legacy player statistics load with an empty weekly contribution map") {
        val root = Files.createTempDirectory("arcfarms-state-weekly-legacy-test")
        val data = root.resolve("data")
        Files.createDirectories(data)
        val playerId = UUID(0, 42)
        Files.writeString(
            data.resolve("state.json"),
            """
            {
              "schemaVersion": 1,
              "farms": {},
              "lumbermills": {},
              "mines": {},
              "stats": {
                "$playerId": {
                  "contributions": {"FARM": 321},
                  "completedShifts": {"FARM": 2}
                }
              }
            }
            """.trimIndent(),
        )

        val loaded = ArcFarmsStateRepository(root).use(ArcFarmsStateRepository::load)

        loaded.stats.getValue(playerId) shouldBe PlayerActivityStats(
            contributions = mapOf(ActivityKind.FARM to 321L),
            completedShifts = mapOf(ActivityKind.FARM to 2),
            weeklyContributions = emptyMap(),
        )
    }

    test("weekly contribution survives an atomic state round trip") {
        val root = Files.createTempDirectory("arcfarms-state-weekly-roundtrip-test")
        val playerId = UUID(0, 43)
        val expected = ArcFarmsState(
            stats = mapOf(
                playerId to PlayerActivityStats(
                    contributions = mapOf(ActivityKind.FARM to 900L),
                    weeklyContributions = mapOf(
                        ActivityKind.FARM to WeeklyActivityContribution(
                            weekStartEpochDay = 20_690,
                            contribution = 75L,
                        ),
                    ),
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

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
                  "startedAt": 1000,
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

    test("legacy resolved incident becomes the first completed incident") {
        val root = Files.createTempDirectory("arcfarms-state-incident-legacy-test")
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
                  "incidentCrop": "WHEAT",
                  "incidentProgress": 1,
                  "incidentRequired": 1,
                  "incidentResolved": true,
                  "startedAt": 1000,
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

        val loaded = ArcFarmsStateRepository(root).use(ArcFarmsStateRepository::load)

        loaded.farms.getValue("legacy_farm").also { farm ->
            farm.incidentsResolved shouldBe 1
            farm.incidentCrop shouldBe null
            farm.incidentProgress shouldBe 0
            farm.incidentRequired shouldBe 0
        }
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
                    harvestMilestone = 4,
                    preparationPatch = patch,
                    preparationCrop = "WHEAT",
                    preparationReleased = true,
                    tilledPlots = patch.toSet(),
                    plantedPlots = patch.toSet(),
                    preparationProgress = 2,
                    plantingProgress = 2,
                    preparationRequired = 2,
                    rewardMoneyBonusPercent = 25,
                    deliveryPosition = FarmDeliveryPosition("world", 10.5, 64.0, -3.5),
                    contributors = mapOf(UUID(0, 1) to Int.MAX_VALUE),
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
                "night_farm" to FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 10,
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
                    incidentType = FarmIncidentType.NIGHT_SHIFT,
                    incidentProgress = 1,
                    incidentRequired = 2,
                    specialIncident = FarmSpecialIncidentState(plots = patch),
                    specialDamagedCrops = listOf(FarmCropDamage(patch.first(), "WHEAT")),
                ),
                "care_farm" to FarmShiftState(
                    phase = FarmPhase.CARE,
                    sequence = 11,
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

    test("large mechanized field state survives an atomic round trip") {
        val root = Files.createTempDirectory("arcfarms-state-machine-field-test")
        val patch = (0 until 64).flatMap { x ->
            (0 until 16).map { z -> FarmPlotPosition("world", x, 63, z) }
        }
        val expected = ArcFarmsState(
            farms = mapOf(
                "machine_farm" to FarmShiftState(
                    phase = FarmPhase.CARE,
                    sequence = 12,
                    orderId = "current_order",
                    progress = mapOf("WHEAT" to 0),
                    preparationPatch = patch,
                    preparationCrop = "WHEAT",
                    preparationReleased = true,
                    tilledPlots = patch.take(320).toSet(),
                    plantedPlots = patch.take(320).toSet(),
                    preparationProgress = 320,
                    plantingProgress = 320,
                    preparationRequired = patch.size,
                    careType = FarmCareType.SEEDER,
                    seederStage = FarmSeederStage.PLANTING,
                    careTargets = listOf(
                        FarmCareTarget(0, FarmCareRole.SEEDER_HORSE, FarmPointPosition("world", 0.5, 64.05, 0.5), progress = 1),
                        FarmCareTarget(1, FarmCareRole.SEEDER_WAYPOINT, FarmPointPosition("world", 63.5, 64.05, 7.5)),
                    ),
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("pending farm rewards and claim watermarks survive an atomic round trip") {
        val root = Files.createTempDirectory("arcfarms-state-reward-test")
        val playerId = UUID(0, 42)
        val reward = PendingFarmReward(
            id = "communal_farm:7:$playerId",
            zoneId = "communal_farm",
            sequence = 7,
            playerId = playerId,
            contribution = 321,
            experience = 75,
            moneyCents = 50_000,
            items = listOf(FarmRewardItem("BREAD", 8)),
            fixedItemUnits = 0,
            commands = listOf("crate give Farmer farm"),
            bundleIds = listOf("field_lunch"),
        )
        val expected = ArcFarmsState(
            pendingFarmRewards = listOf(reward),
            claimedFarmRewardSequences = mapOf("communal_farm:${UUID(0, 41)}" to 6),
        )

        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("state persistence rejects duplicate pending farm reward ids") {
        val root = Files.createTempDirectory("arcfarms-state-duplicate-reward-test")
        val playerId = UUID(0, 42)
        val reward = PendingFarmReward(
            id = "farm:1:$playerId",
            zoneId = "farm",
            sequence = 1,
            playerId = playerId,
            contribution = 1,
        )
        val invalid = ArcFarmsState(pendingFarmRewards = listOf(reward, reward))

        ArcFarmsStateRepository(root).use { repository ->
            val failure = shouldThrow<ExecutionException> { repository.saveBlocking(invalid) }
            (failure.cause is IllegalArgumentException) shouldBe true
        }
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

    test("state persistence rejects active lumber without a species") {
        val root = Files.createTempDirectory("arcfarms-state-invalid-lumber-test")
        val invalid = ArcFarmsState(
            lumbermills = mapOf(
                "lumber" to LumberShiftState(
                    phase = LumberPhase.FELLING,
                    sequence = 1,
                    startedAt = 1_000,
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { repository ->
            val failure = shouldThrow<ExecutionException> { repository.saveBlocking(invalid) }
            (failure.cause is IllegalArgumentException) shouldBe true
        }
    }

    test("state persistence rejects extraction before the mine hazard is resolved") {
        val root = Files.createTempDirectory("arcfarms-state-invalid-mine-test")
        val invalid = ArcFarmsState(
            mines = mapOf(
                "mine" to MineShiftState(
                    phase = MinePhase.EXTRACTION,
                    sequence = 1,
                    cart = 20,
                    startedAt = 1_000,
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { repository ->
            val failure = shouldThrow<ExecutionException> { repository.saveBlocking(invalid) }
            (failure.cause is IllegalArgumentException) shouldBe true
        }
    }

    test("idle farm state cannot discard pending world recovery") {
        val root = Files.createTempDirectory("arcfarms-state-idle-recovery-test")
        val invalid = ArcFarmsState(
            farms = mapOf(
                "farm" to FarmShiftState(
                    droughtDamagedPlots = setOf(FarmPlotPosition("world", 10, 63, 10)),
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { repository ->
            val failure = shouldThrow<ExecutionException> { repository.saveBlocking(invalid) }
            (failure.cause is IllegalArgumentException) shouldBe true
        }
    }
})
