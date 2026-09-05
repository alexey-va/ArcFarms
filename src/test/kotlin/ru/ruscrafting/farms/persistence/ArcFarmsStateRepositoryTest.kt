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
import ru.ruscrafting.farms.domain.FarmProcessingStage
import ru.ruscrafting.farms.domain.FarmProcessingState
import ru.ruscrafting.farms.domain.FarmPerkType
import ru.ruscrafting.farms.domain.FarmPlayerPerks
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.FarmSpecialIncidentState
import ru.ruscrafting.farms.domain.FarmSeederStage
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.FarmRewardItem
import ru.ruscrafting.farms.domain.PendingFarmReward
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseReservation
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseCapitalCompany
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseCapitalPhase
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseFinancingSnapshot
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseSnapshot
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseTerms
import ru.ruscrafting.farms.domain.PlayerActivityStats
import ru.ruscrafting.farms.domain.WeeklyActivityContribution
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ExecutionException

class ArcFarmsStateRepositoryTest : FunSpec({
    test("persistence health tracks completed asynchronous requests without retaining backlog") {
        val root = Files.createTempDirectory("arcfarms-state-health-test")
        ArcFarmsStateRepository(root).use { repository ->
            repository.saveAsync(ArcFarmsState()).get()

            repository.health().also { health ->
                health.pendingRequests shouldBe 0
                health.completedRequests shouldBe 1
                health.failedRequests shouldBe 0
            }
        }
    }

    test("identical durable state does not rewrite the atomic file") {
        val root = Files.createTempDirectory("arcfarms-state-distinct-write-test")
        val expected = ArcFarmsState(
            stats = mapOf(UUID(0, 1) to PlayerActivityStats(contributions = mapOf(ActivityKind.FARM to 42L))),
        )

        ArcFarmsStateRepository(root).use { repository ->
            repository.saveBlocking(expected)
            val stateFile = root.resolve("data/state.json")
            val firstModified = Files.getLastModifiedTime(stateFile)

            Thread.sleep(100)
            repository.saveBlocking(expected.copy())

            Files.getLastModifiedTime(stateFile) shouldBe firstModified
        }
    }

    test("legacy state loads with an empty temporary perk ledger") {
        val root = Files.createTempDirectory("arcfarms-state-perks-legacy-test")
        val data = root.resolve("data")
        Files.createDirectories(data)
        Files.writeString(
            data.resolve("state.json"),
            """{"schemaVersion":1,"farms":{},"lumbermills":{},"mines":{},"stats":{}}""",
        )

        val loaded = ArcFarmsStateRepository(root).use(ArcFarmsStateRepository::load)
        loaded.farmPerks shouldBe emptyMap()
        loaded.worksiteEnterprise shouldBe WorksiteEnterpriseSnapshot()
    }

    test("worksite enterprise reservation survives an atomic state round trip") {
        val root = Files.createTempDirectory("arcfarms-state-enterprise-roundtrip-test")
        val reservation = WorksiteEnterpriseReservation(
            operationId = "enterprise:farm:communal_farm:8",
            activity = ActivityKind.FARM,
            companyId = "communal_farm",
            worksiteId = "communal_farm",
            orderId = "bakery_supply",
            sequence = 8,
            grossTariffCents = 1_000_000,
            reservedAt = 1_800_000_000_000,
            businessWeekStartEpochDay = 20_695,
            terms = WorksiteEnterpriseTerms(
                operatingCostPercent = 20,
                workerBonusPercent = 30,
                dividendPercent = 50,
                weeklyUpkeepCents = 100_000,
            ),
        )
        val expected = ArcFarmsState(
            worksiteEnterprise = WorksiteEnterpriseSnapshot(
                reservations = mapOf("farm:communal_farm" to reservation),
            ),
        )

        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }

        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("enterprise ownership and offline investment credit survive an atomic state round trip") {
        val root = Files.createTempDirectory("arcfarms-state-enterprise-capital-roundtrip-test")
        val owner = UUID(0, 71)
        val company = WorksiteEnterpriseCapitalCompany(
            activity = ActivityKind.FARM,
            companyId = "communal_farm",
            phase = WorksiteEnterpriseCapitalPhase.FUNDING,
            fundingOpenedAt = 1_000,
            fundingClosesAt = 2_000,
            totalShares = 100,
            sharePriceCents = 5_000_000,
            maxSharesPerOwner = 20,
            licenseBurnPercent = 50,
            licenseWeeks = 12,
            reserveTargetWeeks = 1,
            issuedShares = 3,
            shareholdings = mapOf(owner to 3),
            escrowCents = 15_000_000,
        )
        val expected = ArcFarmsState(
            worksiteEnterprise = WorksiteEnterpriseSnapshot(
                financing = WorksiteEnterpriseFinancingSnapshot(
                    companies = mapOf("farm:communal_farm" to company),
                    investmentCreditsCents = mapOf(owner to 42_000),
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("state persistence rejects a negative placement sequence from stored json") {
        val root = Files.createTempDirectory("arcfarms-state-placement-sequence-test")
        val data = root.resolve("data")
        Files.createDirectories(data)
        Files.writeString(
            data.resolve("state.json"),
            """{"schemaVersion":1,"farms":{"farm":{"placementSequence":-1}},"lumbermills":{},"mines":{},"stats":{}}""",
        )

        shouldThrow<IllegalArgumentException> {
            ArcFarmsStateRepository(root).use(ArcFarmsStateRepository::load)
        }
    }

    test("temporary perk purchase survives an atomic state round trip") {
        val root = Files.createTempDirectory("arcfarms-state-perks-roundtrip-test")
        val playerId = UUID(0, 44)
        val expected = ArcFarmsState(
            farmPerks = mapOf(
                playerId to FarmPlayerPerks(
                    weekStartEpochDay = 20_690,
                    spentPoints = 120,
                    activeUntil = FarmPerkType.entries.associateWith { 1_800_000_000_000L },
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("bird and food delivery incidents survive an atomic state round trip") {
        val root = Files.createTempDirectory("arcfarms-state-new-incidents-roundtrip-test")
        val expected = ArcFarmsState(
            farms = mapOf(
                "bird_farm" to FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 3,
                    orderId = "farm_order",
                    startedAt = 1,
                    incidentType = FarmIncidentType.BIRDS,
                    incidentCrop = "WHEAT",
                    incidentRequired = 1,
                    specialIncident = FarmSpecialIncidentState(
                        plots = listOf(FarmPlotPosition("sp11", 10, 64, 10)),
                    ),
                ),
                "route_farm" to FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 4,
                    orderId = "farm_order",
                    startedAt = 1,
                    incidentType = FarmIncidentType.FOOD_DELIVERY,
                    incidentProgress = 1,
                    incidentRequired = 3,
                    specialIncident = FarmSpecialIncidentState(routeName = "orchard"),
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("tornado incident survives an atomic state round trip") {
        val root = Files.createTempDirectory("arcfarms-state-tornado-roundtrip-test")
        val player = UUID(0, 77)
        val expected = ArcFarmsState(
            farms = mapOf(
                "tornado_farm" to FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 12,
                    orderId = "farm_order",
                    startedAt = 1,
                    incidentType = FarmIncidentType.TORNADO,
                    incidentProgress = 17,
                    incidentRequired = 45,
                    contributors = mapOf(player to 17),
                    specialIncident = FarmSpecialIncidentState(
                        points = listOf(
                            FarmPointPosition("world", 0.5, 64.0, 0.5),
                            FarmPointPosition("world", 3.5, 64.0, 2.5),
                        ),
                    ),
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("tornado persistence rejects empty or oversized funnel points and invalid quota") {
        val root = Files.createTempDirectory("arcfarms-state-tornado-invalid-test")
        val cases = listOf(
            FarmSpecialIncidentState(points = emptyList()) to 45,
            FarmSpecialIncidentState(points = (0 until 33).map { FarmPointPosition("world", it.toDouble(), 64.0, 0.5) }) to 45,
            FarmSpecialIncidentState(points = listOf(FarmPointPosition("world", 0.5, 64.0, 0.5))) to 9,
        )

        cases.forEach { (special, required) ->
            val invalid = ArcFarmsState(
                farms = mapOf(
                    "farm" to FarmShiftState(
                        phase = FarmPhase.INCIDENT,
                        sequence = 1,
                        orderId = "farm_order",
                        incidentType = FarmIncidentType.TORNADO,
                        incidentRequired = required,
                        specialIncident = special,
                    ),
                ),
            )
            ArcFarmsStateRepository(root).use { repository ->
                val failure = shouldThrow<ExecutionException> { repository.saveBlocking(invalid) }
                (failure.cause is IllegalArgumentException) shouldBe true
            }
        }
    }

    test("channels incident without a crop survives an atomic state round trip") {
        val root = Files.createTempDirectory("arcfarms-state-channels-roundtrip-test")
        val points = listOf(
            FarmPointPosition("sp11", 10.5, 64.0, 10.5),
            FarmPointPosition("sp11", 11.5, 64.0, 10.5),
            FarmPointPosition("sp11", 12.5, 64.0, 10.5),
        )
        val expected = ArcFarmsState(
            farms = mapOf(
                "farm" to FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 5,
                    orderId = "farm_order",
                    startedAt = 1,
                    incidentType = FarmIncidentType.CHANNELS,
                    incidentRequired = points.size,
                    specialIncident = FarmSpecialIncidentState(points = points),
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("processing incident survives an atomic state round trip") {
        val root = Files.createTempDirectory("arcfarms-state-processing-roundtrip-test")
        val processing = FarmProcessingState(
            crop = "WHEAT",
            stage = FarmProcessingStage.LOADING,
            inputLoaded = 2,
            inputRequired = 4,
            loadedInputSlots = setOf(0, 2),
            cyclesRequired = 6,
            outputRequired = 4,
        )
        val expected = ArcFarmsState(
            farms = mapOf(
                "farm" to FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 8,
                    orderId = "farm_order",
                    startedAt = 1,
                    incidentType = FarmIncidentType.PROCESSING,
                    incidentCrop = "WHEAT",
                    incidentProgress = processing.completed,
                    incidentRequired = processing.required,
                    processing = processing,
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("partially extinguished barn fire survives an atomic state round trip") {
        val root = Files.createTempDirectory("arcfarms-state-barn-fire-roundtrip-test")
        val points = listOf(
            FarmPointPosition("sp11", 10.5, 65.02, 10.5),
            FarmPointPosition("sp11", 13.5, 65.02, 10.5),
            FarmPointPosition("sp11", 16.5, 65.02, 10.5),
        )
        val expected = ArcFarmsState(
            farms = mapOf(
                "farm" to FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 9,
                    placementSequence = 4,
                    orderId = "farm_order",
                    startedAt = 1,
                    incidentType = FarmIncidentType.BARN_FIRE,
                    incidentCrop = "WHEAT",
                    incidentProgress = 1,
                    incidentRequired = points.size,
                    specialIncident = FarmSpecialIncidentState(points = points, active = setOf(0, 2)),
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("gradually spreading barn fire persists before reaching its hotspot cap") {
        val root = Files.createTempDirectory("arcfarms-state-growing-barn-fire-test")
        val points = (0 until 6).map { index ->
            FarmPointPosition("sp11", 10.5 + index, 65.02, 10.5)
        }
        val expected = ArcFarmsState(
            farms = mapOf(
                "farm" to FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 10,
                    placementSequence = 5,
                    orderId = "farm_order",
                    startedAt = 1,
                    incidentType = FarmIncidentType.BARN_FIRE,
                    incidentCrop = "WHEAT",
                    incidentProgress = 0,
                    incidentRequired = points.size,
                    specialIncident = FarmSpecialIncidentState(points = points, active = setOf(0, 1)),
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("state persistence rejects processing progress inconsistent with its stage") {
        val root = Files.createTempDirectory("arcfarms-state-processing-stage-test")
        val processing = FarmProcessingState(
            crop = "WHEAT",
            stage = FarmProcessingStage.PACKING,
            inputRequired = 4,
            cyclesRequired = 6,
            outputRequired = 4,
        )
        val invalid = ArcFarmsState(
            farms = mapOf(
                "farm" to FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 8,
                    orderId = "farm_order",
                    startedAt = 1,
                    incidentType = FarmIncidentType.PROCESSING,
                    incidentCrop = "WHEAT",
                    incidentProgress = processing.completed,
                    incidentRequired = processing.required,
                    processing = processing,
                ),
            ),
        )

        ArcFarmsStateRepository(root).use { repository ->
            val failure = shouldThrow<ExecutionException> { repository.saveBlocking(invalid) }
            (failure.cause is IllegalArgumentException) shouldBe true
        }
    }

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

    test("accepted market from the pre-timer format loads for runtime deadline migration") {
        val root = Files.createTempDirectory("arcfarms-state-market-timer-legacy-test")
        val data = root.resolve("data")
        Files.createDirectories(data)
        Files.writeString(
            data.resolve("state.json"),
            """
            {
              "schemaVersion": 1,
              "farms": {
                "legacy_market": {
                  "phase": "INCIDENT",
                  "sequence": 12,
                  "orderId": "legacy_order",
                  "progress": {"SWEET_BERRY_BUSH": 2},
                  "incidentCrop": "SWEET_BERRY_BUSH",
                  "incidentType": "MARKET",
                  "incidentProgress": 1,
                  "incidentRequired": 8,
                  "incidentResolved": false,
                  "specialIncident": {
                    "plots": [{"world": "world", "x": 1, "y": 63, "z": 1}],
                    "crop": "SWEET_BERRY_BUSH",
                    "marketAccepted": true
                  },
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
            .farms.getValue("legacy_market")

        loaded.specialIncident?.marketAccepted shouldBe true
        loaded.specialIncident?.marketDeadlineAt shouldBe 0L
        loaded.incidentProgress shouldBe 1
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
                    placementSequence = 27,
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
                "market_farm" to FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 11,
                    orderId = "current_order",
                    progress = mapOf("SWEET_BERRY_BUSH" to 3),
                    preparationPatch = patch,
                    preparationCrop = "WHEAT",
                    preparationReleased = true,
                    tilledPlots = patch.toSet(),
                    plantedPlots = patch.toSet(),
                    preparationProgress = 2,
                    plantingProgress = 2,
                    preparationRequired = 2,
                    incidentCrop = "SWEET_BERRY_BUSH",
                    incidentType = FarmIncidentType.MARKET,
                    incidentProgress = 1,
                    incidentRequired = 8,
                    specialIncident = FarmSpecialIncidentState(
                        plots = patch,
                        crop = "SWEET_BERRY_BUSH",
                        marketAccepted = true,
                        marketDeadlineAt = 145_000,
                    ),
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

    test("large orchard scene and smaller completion quota survive an atomic round trip") {
        val root = Files.createTempDirectory("arcfarms-state-orchard-test")
        val patch = listOf(FarmPlotPosition("world", 0, 63, 0))
        val targets = (0 until 200).map { index ->
            FarmCareTarget(
                index,
                FarmCareRole.APPLE,
                FarmPointPosition("world", index.toDouble(), 72.0, 0.0),
            )
        }
        val expected = ArcFarmsState(
            farms = mapOf(
                "orchard" to FarmShiftState(
                    phase = FarmPhase.CARE,
                    sequence = 13,
                    orderId = "current_order",
                    progress = mapOf("WHEAT" to 0),
                    preparationPatch = patch,
                    preparationCrop = "WHEAT",
                    preparationReleased = true,
                    tilledPlots = patch.toSet(),
                    plantedPlots = patch.toSet(),
                    preparationProgress = 1,
                    plantingProgress = 1,
                    preparationRequired = 1,
                    careType = FarmCareType.APPLE_HARVEST,
                    careTargets = targets,
                    careGoal = 50,
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
