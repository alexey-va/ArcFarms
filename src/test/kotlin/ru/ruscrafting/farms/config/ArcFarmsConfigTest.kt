package ru.ruscrafting.farms.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.assertions.throwables.shouldThrow
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.opentest4j.TestAbortedException
import ru.arc.config.Config
import ru.arc.redis.RedisModuleConfig
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmContractRarity
import ru.ruscrafting.farms.domain.FarmCustomerType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmSeederStage
import ru.ruscrafting.farms.paper.FarmScoreboardRenderer
import ru.ruscrafting.farms.paper.FarmScoreboardView
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import kotlin.io.path.writeText

class ArcFarmsConfigTest : FunSpec({
    test("bundled menu visuals are typed, complete, and portable") {
        val settings = ArcFarmsConfig.inspect(resourceTree())

        settings.mainMenuItems.apply {
            farm.material shouldBe "WHEAT"
            lumber.material shouldBe "IRON_AXE"
            mine.material shouldBe "MINECART"
            workday.material shouldBe "WRITABLE_BOOK"
            stats.material shouldBe "BOOK"
        }
        settings.enterpriseMenuItems.apply {
            companies.material shouldBe "EMERALD"
            overviewFarm.material shouldBe "WHEAT"
            overviewLumber.material shouldBe "IRON_AXE"
            overviewMine.material shouldBe "MINECART"
            farmHeader.material shouldBe "WHEAT"
            report.material shouldBe "PAPER"
            workers.material shouldBe "WHEAT"
            policy.material shouldBe "WRITABLE_BOOK"
            license.material shouldBe "BOOK"
            shares.material shouldBe "EMERALD"
            market.material shouldBe "GOLD_INGOT"
            shareStatus.material shouldBe "HONEYCOMB"
            shareHolding.material shouldBe "PAPER"
            shareAccount.material shouldBe "GOLD_INGOT"
            shareBuy.material shouldBe "EMERALD"
            shareConfirm.material shouldBe "LIME_DYE"
            shareWithdraw.material shouldBe "SUNFLOWER"
        }
        settings.configuredMenuItems.size shouldBe 23
        settings.configuredMenuItems.values.all { it.customModelData == 0 } shouldBe true
    }

    test("shift-start persistence retries use a bounded live policy") {
        val settings = ArcFarmsConfig.inspect(resourceTree()).shiftStartPersistence

        settings.initialRetrySeconds shouldBe 1
        settings.maximumRetrySeconds shouldBe 30
        settings.logEveryAttempts shouldBe 5
        settings.retryDelayTicks(1) shouldBe 20L
        settings.retryDelayTicks(5) shouldBe 320L
        settings.retryDelayTicks(30) shouldBe 600L

        val invalid = resourceTree()
        invalid.resolve("config.yml").writeText(
            Files.readString(invalid.resolve("config.yml"))
                .replace("initial-retry-seconds: 1", "initial-retry-seconds: 60"),
        )
        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(invalid) }
            .message shouldContain "initial retry must not exceed its maximum"
    }

    test("bundled and runtime profiles physically declare reloadable visuals, retry policy, and business week") {
        val repositoryRoot = opsRoot()
        val profiles = linkedMapOf(
            "bundled" to resourceTree(),
            "classic" to repositoryRoot.resolve("classic/plugins/ArcFarms"),
            "classic_survival" to repositoryRoot.resolve("classic_survival/plugins/ArcFarms"),
            "parkour" to repositoryRoot.resolve("parkour/plugins/ArcFarms"),
            "lab" to repositoryRoot.resolve("scripts/lab/plugin-configs/ArcFarms"),
        )
        val itemPaths = listOf(
            "ui.menu-background",
            "ui.menu-back",
            "ui.main-menu.items.farm",
            "ui.main-menu.items.lumber",
            "ui.main-menu.items.mine",
            "ui.main-menu.items.workday",
            "ui.main-menu.items.stats",
            "ui.enterprise-menu.items.companies",
            "ui.enterprise-menu.items.overview-farm",
            "ui.enterprise-menu.items.overview-lumber",
            "ui.enterprise-menu.items.overview-mine",
            "ui.enterprise-menu.items.farm-header",
            "ui.enterprise-menu.items.report",
            "ui.enterprise-menu.items.workers",
            "ui.enterprise-menu.items.policy",
            "ui.enterprise-menu.items.license",
            "ui.enterprise-menu.items.shares",
            "ui.enterprise-menu.items.market",
            "ui.enterprise-menu.items.share-status",
            "ui.enterprise-menu.items.share-holding",
            "ui.enterprise-menu.items.share-account",
            "ui.enterprise-menu.items.share-buy",
            "ui.enterprise-menu.items.share-confirm",
            "ui.enterprise-menu.items.share-withdraw",
        )

        profiles.forEach { (profile, root) ->
            val config = Config(root, "config.yml")
            itemPaths.forEach { path ->
                withClue("$profile:$path") {
                    config.stringOrNull("$path.material")?.isNotBlank() shouldBe true
                    (config.intOrNull("$path.custom-model-data") != null) shouldBe true
                }
            }
            withClue("$profile:ui.menu-background.enabled") {
                (config.booleanOrNull("ui.menu-background.enabled") != null) shouldBe true
            }
            config.intOrNull("state.shift-start-persistence.initial-retry-seconds") shouldBe 1
            config.intOrNull("state.shift-start-persistence.maximum-retry-seconds") shouldBe 30
            config.intOrNull("state.shift-start-persistence.log-every-attempts") shouldBe 5
            config.stringOrNull("enterprises.farm.business-week.zone") shouldBe "Europe/Moscow"
            config.stringOrNull("enterprises.farm.business-week.day") shouldBe "SUNDAY"
            config.stringOrNull("enterprises.farm.business-week.time") shouldBe "20:00"
            config.intOrNull("enterprises.farm.capital.total-shares") shouldBe 100
            config.stringOrNull("enterprises.farm.capital.share-price")?.toDouble() shouldBe 50_000.0
            config.intOrNull("enterprises.farm.capital.max-shares-per-owner") shouldBe 20
            config.intOrNull("enterprises.farm.capital.funding-duration-days") shouldBe 7
            config.intOrNull("enterprises.farm.capital.license-burn-percent") shouldBe 50
            config.intOrNull("enterprises.farm.capital.license-weeks") shouldBe 12
            config.intOrNull("enterprises.farm.capital.reserve-target-weeks") shouldBe 1
        }
    }

    test("menu visual config rejects custom model data outside the Paper range") {
        val root = resourceTree()
        root.resolve("config.yml").writeText(
            Files.readString(root.resolve("config.yml")).replace(
                "farm: {material: WHEAT, custom-model-data: 0}",
                "farm: {material: WHEAT, custom-model-data: -1}",
            ),
        )

        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(root) }
            .message shouldContain "ui.main-menu.items.farm.custom-model-data"
    }

    test("bundled farm enterprise is safe by default and keeps complete tariff examples") {
        val settings = ArcFarmsConfig.inspect(resourceTree()).enterprises.getValue(ActivityKind.FARM)

        settings.mode shouldBe WorksiteEnterpriseMode.OFF
        settings.companyId shouldBe "communal_farm"
        settings.worksiteId shouldBe "communal_farm"
        settings.licenseGrossEnvelopeCents shouldBe 200_000_000L
        settings.grossTariffCents("bakery_supply") shouldBe 1_000_000L
        settings.grossTariffCents("harvest_festival") shouldBe 1_800_000L
        settings.operatingCostPercent shouldBe 20
        settings.workerBonusPercent shouldBe 30
        settings.dividendPercent shouldBe 50
        settings.businessWeek.zoneId shouldBe ZoneId.of("Europe/Moscow")
        settings.businessWeek.startDay shouldBe DayOfWeek.SUNDAY
        settings.businessWeek.startTime shouldBe LocalTime.of(20, 0)
        settings.capital.totalShares shouldBe 100
        settings.capital.sharePriceCents shouldBe 5_000_000L
        settings.capital.maxSharesPerOwner shouldBe 20
        settings.capital.fundingDurationDays shouldBe 7
        settings.capital.licenseBurnPercent shouldBe 50
        settings.capital.licenseWeeks shouldBe 12
        settings.capital.reserveTargetWeeks shouldBe 1
        settings.capital.purchaseOptions shouldContainExactly listOf(1, 5, 10, 20)
    }

    test("yaml boolean false remains compatible with the OFF enterprise mode") {
        val root = resourceTree()
        Config(root, "config.yml").also { config ->
            config.setBoolean("enterprises.farm.mode", false)
            config.saveStrict()
        }

        ArcFarmsConfig.inspect(root).enterprises.getValue(ActivityKind.FARM).mode shouldBe
            WorksiteEnterpriseMode.OFF
    }

    test("farm enterprise rejects unsafe modes, policy steps, unknown tariffs, and underfunded licenses") {
        val invalidMode = resourceTree()
        invalidMode.resolve("config.yml").writeText(
            Files.readString(invalidMode.resolve("config.yml")).replace("mode: \"OFF\"", "mode: ACTIVE"),
        )
        shouldThrow<IllegalStateException> { ArcFarmsConfig.inspect(invalidMode) }
            .message shouldContain "mode must be OFF, SHADOW or LIVE"

        val invalidPolicy = resourceTree()
        invalidPolicy.resolve("config.yml").writeText(
            Files.readString(invalidPolicy.resolve("config.yml"))
                .replace("worker-bonus-percent: 30", "worker-bonus-percent: 20"),
        )
        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(invalidPolicy) }
            .message shouldContain "worker-bonus-percent"

        val unknownTariff = resourceTree()
        unknownTariff.resolve("config.yml").writeText(
            Files.readString(unknownTariff.resolve("config.yml"))
                .replace("miners_rations: 10000.00", "unknown_order: 10000.00"),
        )
        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(unknownTariff) }
            .message shouldContain "unknown order"

        val underfunded = resourceTree()
        underfunded.resolve("config.yml").writeText(
            Files.readString(underfunded.resolve("config.yml"))
                .replace("license-gross-envelope: 2000000.00", "license-gross-envelope: 1000.00"),
        )
        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(underfunded) }
            .message shouldContain "must cover one complete order tariff"

        val oversizedEnvelope = resourceTree()
        oversizedEnvelope.resolve("config.yml").writeText(
            Files.readString(oversizedEnvelope.resolve("config.yml"))
                .replace("license-gross-envelope: 2000000.00", "license-gross-envelope: 2000001.00"),
        )
        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(oversizedEnvelope) }
            .message shouldContain "must not exceed 80% of the upfront license burn"
    }

    test("farm enterprise rejects invalid business-week boundaries") {
        val invalidZone = resourceTree()
        invalidZone.resolve("config.yml").writeText(
            Files.readString(invalidZone.resolve("config.yml"))
                .replace("zone: Europe/Moscow", "zone: Mars/Olympus"),
        )
        shouldThrow<IllegalStateException> { ArcFarmsConfig.inspect(invalidZone) }
            .message shouldContain "business-week.zone"

        val invalidDay = resourceTree()
        invalidDay.resolve("config.yml").writeText(
            Files.readString(invalidDay.resolve("config.yml"))
                .replace("day: SUNDAY", "day: MARKETDAY"),
        )
        shouldThrow<IllegalStateException> { ArcFarmsConfig.inspect(invalidDay) }
            .message shouldContain "business-week.day"

        val invalidTime = resourceTree()
        invalidTime.resolve("config.yml").writeText(
            Files.readString(invalidTime.resolve("config.yml"))
                .replace("time: '20:00'", "time: eventually"),
        )
        shouldThrow<IllegalStateException> { ArcFarmsConfig.inspect(invalidTime) }
            .message shouldContain "business-week.time"
    }

    test("bundled production config owns all three activities without ARC compatibility") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val repositoryRoot = opsRoot()

        settings.farms.map { it.id } shouldContainExactly listOf("communal_farm")
        settings.lumbermills.map { it.id } shouldContainExactly listOf("communal_lumbermill")
        settings.mines.map { it.id } shouldContainExactly listOf("infernal_working", "lush_depths", "sand_quarry", "old_shafts")
        settings.farms.single().permission shouldStartWith "arcfarms."
        settings.lumbermills.single().permission shouldStartWith "arcfarms."
        settings.mines.all { it.permission.startsWith("arcfarms.") } shouldBe true
        settings.farms.single().orders.maxOf { order -> order.required.values.sum() } shouldBe 2_208
        settings.farms.single().orders.associate { it.id to it.required } shouldBe mapOf(
            "miners_rations" to mapOf("WHEAT" to 640, "CARROTS" to 320, "POTATOES" to 320),
            "bakery_supply" to mapOf("WHEAT" to 960, "BEETROOTS" to 320),
            "market_crates" to mapOf(
                "CARROTS" to 320,
                "POTATOES" to 320,
                "BEETROOTS" to 320,
                "SWEET_BERRY_BUSH" to 50,
                "MELON" to 48,
                "PUMPKIN" to 48,
            ),
            "harvest_festival" to mapOf(
                "WHEAT" to 800,
                "CARROTS" to 480,
                "POTATOES" to 480,
                "BEETROOTS" to 320,
                "MELON" to 64,
                "PUMPKIN" to 64,
            ),
            "deep_mine_relief" to mapOf("WHEAT" to 800, "CARROTS" to 480, "POTATOES" to 640),
            "master_baker_request" to mapOf("WHEAT" to 1_440, "BEETROOTS" to 480),
        )
        settings.farms.single().orders.associate { it.id to it.careTypes } shouldBe mapOf(
            "miners_rations" to listOf(
                FarmCareType.MOLES,
                FarmCareType.IRRIGATION,
                FarmCareType.ANIMAL_RESCUE,
                FarmCareType.WEEDS,
            ),
            "bakery_supply" to listOf(
                FarmCareType.POLLINATION,
                FarmCareType.APPLE_HARVEST,
                FarmCareType.DISEASE,
                FarmCareType.IRRIGATION,
                FarmCareType.SCARECROWS,
                FarmCareType.MOLES,
            ),
            "market_crates" to listOf(
                FarmCareType.WEEDS,
                FarmCareType.APPLE_HARVEST,
                FarmCareType.SCARECROWS,
                FarmCareType.ANIMAL_RESCUE,
                FarmCareType.STORM_COVERS,
            ),
            "harvest_festival" to listOf(
                FarmCareType.POLLINATION,
                FarmCareType.APPLE_HARVEST,
                FarmCareType.STORM_COVERS,
                FarmCareType.SCARECROWS,
            ),
            "deep_mine_relief" to listOf(
                FarmCareType.MOLES,
                FarmCareType.IRRIGATION,
                FarmCareType.DISEASE,
            ),
            "master_baker_request" to listOf(
                FarmCareType.POLLINATION,
                FarmCareType.APPLE_HARVEST,
                FarmCareType.DISEASE,
                FarmCareType.STORM_COVERS,
            ),
        )
        settings.farms.single().rareOrderChancePercent shouldBe 20
        settings.farms.single().orders.count { it.rarity == FarmContractRarity.RARE } shouldBe 3
        settings.farms.single().orders.first { it.id == "bakery_supply" }.customerType shouldBe FarmCustomerType.BAKER
        settings.farms.single().orders.first { it.id == "harvest_festival" }.cartLoadMaterial shouldBe "HAY_BLOCK"
        settings.serverId shouldBe "spawn"
        settings.network.allowedOrigins shouldBe setOf("spawn", "survival", "parkour")
        settings.network.workdayEnabled shouldBe true
        settings.network.playerAnnouncementsEnabled shouldBe false
        settings.farmScoreboard.provider shouldBe FarmScoreboardProvider.BUKKIT
        settings.missingBedHighlightThreshold shouldBe 10
        settings.farmScoreboard.enabled shouldBe true
        settings.farmScoreboard.replaceExisting shouldBe false
        settings.menuBackground.enabled shouldBe false
        settings.menuBack.material shouldBe "ARROW"
        settings.menuBack.customModelData shouldBe 0
        settings.enterprises.getValue(ActivityKind.FARM).apply {
            mode shouldBe WorksiteEnterpriseMode.OFF
            companyId shouldBe "communal_farm"
            worksiteId shouldBe "communal_farm"
            licenseGrossEnvelopeCents shouldBe 200_000_000L
            grossTariffCents("bakery_supply") shouldBe 1_000_000L
            grossTariffCents("harvest_festival") shouldBe 1_800_000L
            operatingCostPercent shouldBe 20
            workerBonusPercent shouldBe 30
            dividendPercent shouldBe 50
        }
        settings.destinations.getValue("farm").server shouldBe "spawn"
        settings.destinations.getValue("farm").world shouldBe "sp11"
        settings.requiresWorldGuard shouldBe true
        settings.farms.single().incidentQuota shouldBe 4
        settings.farms.single().incidentTriggerPercents shouldContainExactly listOf(15, 32, 50, 68, 85)
        settings.farms.single().incidentCountMin shouldBe 3
        settings.farms.single().incidentCountMax shouldBe 5
        settings.farms.single().droughtPatches shouldBe 3
        settings.farms.single().droughtCoveragePercent shouldBe 35
        settings.farms.single().droughtMinBeds shouldBe 30
        settings.farms.single().droughtMaxBeds shouldBe 40
        settings.farms.single().droughtTargetBeds(100) shouldBe 35
        settings.farms.single().droughtTargetBeds(60) shouldBe 30
        settings.farms.single().droughtTargetBeds(20) shouldBe 20
        settings.farms.single().preparationPatchSize shouldBe 100
        settings.farms.single().preparationPatchMaxSize shouldBe 256
        settings.farms.single().fieldCompletionPercent shouldBe 90
        settings.farms.single().seederPatchSize shouldBe 3_840
        settings.farms.single().seederPatchMaxSize shouldBe 6_144
        settings.farms.single().seederComponentGap shouldBe 16
        settings.farms.single().seederComponentLimit shouldBe 8
        settings.farms.single().seederWorkingRadius shouldBe 8.0
        settings.farms.single().seederBlocksPerUpdate shouldBe 128
        settings.farms.single().seederPigSpeed shouldBe 0.46
        settings.farms.single().seederPigCatchupDistance shouldBe 12.0
        settings.farms.single().preparationSearchRadius shouldBe 64
        settings.farms.single().fixedCropRespawnSeconds shouldBe 20
        settings.farms.single().restoreBlocksPerTick shouldBe 24
        settings.farms.single().blockReindexBlocksPerTick shouldBe 131_072
        settings.farms.single().blockReindexMaxBlocks shouldBe 20_000_000
        settings.farms.single().cropLayout.enabled shouldBe true
        settings.farms.single().cropLayout.weights shouldBe mapOf(
            "WHEAT" to 30,
            "CARROTS" to 25,
            "POTATOES" to 25,
            "BEETROOTS" to 15,
            "SWEET_BERRY_BUSH" to 1,
        )
        settings.farms.single().cropLayout.smallComponentMaxSize shouldBe 16
        settings.farms.single().cropLayout.smallComponentMergeDistance shouldBe 10
        settings.farms.single().backupBlocksPerTick shouldBe 2_048
        settings.farms.single().backupMaxBlocks shouldBe 4_000_000
        settings.farms.single().crops shouldBe
            setOf("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "SWEET_BERRY_BUSH", "MELON", "PUMPKIN")
        settings.farms.single().careTypes shouldContainExactly FarmCareType.entries.filterNot { it == FarmCareType.SEEDER }
        settings.farms.single().careTargetsPerPlayer shouldBe 15
        settings.farms.single().careTargetsMax shouldBe 45
        settings.farms.single().careSpawnsPerUpdate shouldBe 10
        settings.farms.single().scarecrowTargetCount shouldBe 5
        settings.farms.single().pollinationCharges shouldBe 5
        settings.farms.single().irrigation.dryBlocksPerTick shouldBe 24
        settings.farms.single().irrigation.waveBlocksPerTick shouldBe 24
        settings.farms.single().irrigation.waveStartDelayTicks shouldBe 4
        settings.farms.single().irrigation.ringIntervalTicks shouldBe 2
        settings.farms.single().irrigation.ringWidth shouldBe 1.25
        settings.farms.single().irrigation.particleSpacing shouldBe 1.25
        settings.farms.single().irrigation.particleHeight shouldBe 2.0
        settings.farms.single().irrigation.particleSpread shouldBe 0.38
        settings.farms.single().irrigation.particleCount shouldBe 3
        settings.farms.single().appleTargetCount shouldBe 10
        settings.farms.single().applePlacementCount shouldBe 40
        settings.farms.single().appleSpawnsPerUpdate shouldBe 20
        settings.farms.single().appleMinSpacing shouldBe 4.0
        settings.farms.single().appleDisplayScale shouldBe 1.35f
        settings.farms.single().appleDisplayYOffset shouldBe -0.82
        settings.farms.single().appleInteractionYOffset shouldBe -0.88
        settings.farms.single().appleLeafIndexLimit shouldBe 8_192
        settings.farms.single().animalRescueTargetCount shouldBe 6
        settings.farms.single().animalRescueMinSpacing shouldBe 8.0
        settings.farms.single().animalRescueMaxPlayerDistance shouldBe 28
        settings.farms.single().animalDeliveryRadius shouldBe 3.0
        settings.farms.single().supplyNearbyViewDistance shouldBe 15.0f
        settings.farms.single().displayViewRange shouldBe 2.0f
        settings.farms.single().seederEveryShifts shouldBe 2
        settings.farms.single().diseaseInitialSpots shouldBe 2
        settings.farms.single().diseaseMaxSpots shouldBe 10
        settings.farms.single().diseaseSpreadSeconds shouldBe 3
        settings.farms.single().diseaseSpreadRadius shouldBe 4.0
        settings.farms.single().diseaseKillSeconds shouldBe 16
        settings.farms.single().moleBurrow.cells shouldBe 8
        settings.farms.single().moleBurrow.maxBurrows shouldBe 3
        settings.farms.single().moleBurrow.minDepth shouldBe 10
        settings.farms.single().moleBurrow.maxDepth shouldBe 56
        settings.farms.single().moleBurrow.candidateAttempts shouldBe 96
        settings.farms.single().moleBurrow.tunnelWidth shouldBe 2
        settings.farms.single().moleBurrow.entranceMinBoundaryDistance shouldBe 10
        settings.farms.single().moleBurrow.blocksPerTick shouldBe 256
        settings.farms.single().moleBurrow.chamberCount shouldBe 3
        settings.farms.single().moleBurrow.moleCount shouldBe 8
        settings.farms.single().moleBurrow.guidanceCloseDistance shouldBe 6
        settings.farms.single().moleBurrow.guidanceFarDistance shouldBe 14
        settings.farms.single().moleBurrow.guidanceIntervalTicks shouldBe 20
        settings.farms.single().moleBurrow.decorationPercent shouldBe 28
        settings.farms.single().moleBurrow.lairVisual.material shouldBe "MUD"
        settings.farms.single().proceduralCareFixtures shouldBe true
        settings.farms.single().careAnimalEntities shouldContainExactly listOf("CHICKEN", "SHEEP")
        settings.farms.single().music.enabled shouldBe false
        settings.farms.single().contractCartVisual.material shouldBe "MINECART"
        settings.farms.single().contractCartVisual.customModelData shouldBe 0
        settings.farms.single().contractCartVisual.displayTransform shouldBe FarmItemDisplayTransform.GROUND
        settings.farms.single().contractCartVisual.scale shouldBe 1.0f
        settings.farms.single().contractCartVisual.yOffset shouldBe 0.15
        settings.farms.single().contractCartVisual.viewRange shouldBe 2.0f
        settings.titleStaySeconds shouldBe 4
        settings.taskHintCooldownMillis shouldBe 900L
        settings.taskTitleCooldownMillis shouldBe 8_000L
        settings.farms.single().placementMinObjectiveDistance shouldBe 10
        settings.farms.single().placementMaxPlayerDistance shouldBe 28
        settings.farms.single().placementSearchRadius shouldBe 32
        settings.farms.single().careVisuals.getValue(FarmCareRole.HIVE).material shouldBe "BEE_NEST"
        settings.farms.single().careVisuals.getValue(FarmCareRole.APPLE).material shouldBe "APPLE"
        settings.farms.single().incidentTypes shouldContainExactly listOf(
            FarmIncidentType.GIANT_CROP,
            FarmIncidentType.PROCESSING,
            FarmIncidentType.BARN_FIRE,
            FarmIncidentType.FROST,
            FarmIncidentType.BOAR_BREAKOUT,
            FarmIncidentType.RIVAL_RAID,
            FarmIncidentType.NIGHT_SHIFT,
            FarmIncidentType.MARKET,
            FarmIncidentType.BIRDS,
            FarmIncidentType.FOOD_DELIVERY,
            FarmIncidentType.PESTS,
            FarmIncidentType.DROUGHT,
        )
        settings.farms.single().processing.inputPackages shouldBe 4
        settings.farms.single().processing.machineCycles shouldBe 3
        settings.farms.single().processing.outputPackages shouldBe 4
        settings.farms.single().processing.proximityPickupRadius shouldBe 1.75
        settings.farms.single().processing.carriedForwardOffset shouldBe 0.6
        settings.farms.single().processing.crankInnerRadius shouldBe 2.4
        settings.farms.single().processing.crankOuterRadius shouldBe 4.0
        settings.farms.single().processing.crankRadiusTolerance shouldBe 1.0
        settings.farms.single().processing.crankMaxStepDistance shouldBe 1.2
        settings.farms.single().processing.crankTitleReminderSeconds shouldBe 8
        settings.farms.single().processing.spawnPerTick shouldBe 4
        settings.farms.single().processing.cargoReminderSeconds shouldBe 12
        settings.farms.single().processing.cargoReturnSeconds shouldBe 30
        settings.farms.single().processing.cargoProgressDistance shouldBe 1.0
        settings.farms.single().processing.crankVerticalTolerance shouldBe 2.5
        settings.farms.single().processing.visuals.getValue(FarmProcessingVisualRole.MACHINE).material shouldBe
            "CRAFTING_TABLE"
        settings.farms.single().processing.visuals.getValue(FarmProcessingVisualRole.OUTPUT_PALLET).material shouldBe
            "BARREL"
        settings.farms.single().processing.visuals.getValue(FarmProcessingVisualRole.RAW_PACKAGE).material shouldBe
            "WHEAT"
        settings.farms.single().routeDelivery.portalWidth shouldBe 3.6f
        settings.farms.single().routeDelivery.portalLabelScale shouldBe 1.8f
        settings.farms.single().delivery.proximityPickupRadius shouldBe 1.75
        settings.farms.single().scarecrowPickupRadius shouldBe 1.75
        settings.farms.single().barnFire.hotspotCount shouldBe 36
        settings.farms.single().barnFire.initialHotspotCount shouldBe 6
        settings.farms.single().barnFire.spreadIntervalTicks shouldBe 13
        settings.farms.single().barnFire.spreadHotspotsPerPulse shouldBe 1
        settings.farms.single().barnFire.spawnPerTick shouldBe 8
        settings.farms.single().barnFire.placementRadius shouldBe 16
        settings.farms.single().barnFire.particleHotspotLimit shouldBe 32
        settings.farms.single().barnFire.sprayRange shouldBe 32.0
        settings.farms.single().barnFire.sprayHitRadius shouldBe 3.2
        settings.farms.single().supplies.fireEquipmentMaterial shouldBe "SPYGLASS"
        settings.farms.single().droughtWaterRadius shouldBe 5
        settings.farms.single().droughtWaterSettleTicks shouldBe 21L
        settings.farms.single().placementReceivingExclusionPadding shouldBe 1.5
        settings.farms.single().careAnimalFollowDistance shouldBe 1.5
        settings.farms.single().careAnimalFollowSpeed shouldBe 1.25
        settings.farms.single().deliveryCarriedForwardOffset shouldBe 0.65
        settings.farms.single().scarecrowCarriedForwardOffset shouldBe 0.7
        settings.lumbermills.single().rushOrderDurationMillis shouldBe 75_000L
        settings.lumbermills.single().forestFireCandidateMultiplier shouldBe 4
        settings.lumbermills.single().sawInteractionCooldownMillis shouldBe 150L
        settings.lumbermills.single().bundleInteractionCooldownMillis shouldBe 350L
        settings.lumbermills.single().plankInteractionCooldownMillis shouldBe 350L
        settings.lumbermills.single().dispatchInteractionCooldownMillis shouldBe 500L
        settings.mines.all { mine ->
            mine.lostMinerDeliveryRadius == 2.5 && mine.lostMinerFollowSnapDistance == 6.0 &&
                mine.lostMinerFollowOffsetZ == -1.0 && mine.extractionCheckpointRadius == 1.6 &&
                mine.loadingDeliveryRadius == 2.0
        } shouldBe true
        settings.farms.single().specialIncidents.channelAutomaticEnabled shouldBe false
        settings.farms.single().specialIncidents.channelSegmentCount shouldBe 50
        settings.farms.single().specialIncidents.channelMarkerColumnStride shouldBe 10
        settings.farms.single().boarBreakout.requiredDeflections shouldBe 8
        settings.farms.single().boarBreakout.activeBoars shouldBe 3
        settings.farms.single().boarBreakout.cropDamageMaximum shouldBe 4_096
        settings.farms.single().boarBreakout.shieldMaterial shouldBe "SHIELD"
        settings.farms.single().rivalRaid.requiredKills shouldBe 32
        settings.farms.single().rivalRaid.workerEntity shouldBe "HUSK"
        settings.farms.single().rivalRaid.workerCount shouldBe 120
        settings.farms.single().rivalRaid.workerSpawnBatchSize shouldBe 12
        settings.farms.single().rivalRaid.workerPatrolBatchSize shouldBe 32
        settings.farms.single().rivalRaid.workerPatrolIntervalTicks shouldBe 10
        settings.farms.single().rivalRaid.workerPatrolSpeed shouldBe 1.8
        settings.farms.single().rivalRaid.workerFocusRadius shouldBe 34.0
        settings.farms.single().rivalRaid.workerRetireRadius shouldBe 48.0
        settings.farms.single().rivalRaid.workerLightStride shouldBe 10
        settings.farms.single().rivalRaid.gunMaterial shouldBe "PAPER"
        settings.farms.single().rivalRaid.gunCustomModelData shouldBe 2_100_006
        settings.farms.single().rivalRaid.grenadeCustomModelData shouldBe 2_100_009
        settings.farms.single().rivalRaid.grenadeDamage shouldBe 16.0
        settings.farms.single().rivalRaid.grenadeRadius shouldBe 7.0
        settings.farms.single().rivalRaid.grenadeCooldownTicks shouldBe 12
        settings.farms.single().rivalRaid.grenadePreviewBlocks shouldBe 72
        settings.farms.single().rivalRaid.grenadePreviewTicks shouldBe 80
        settings.farms.single().rivalRaid.grenadePreviewSoilMaterial shouldBe "COARSE_DIRT"
        settings.farms.single().rivalRaid.maximumRiders shouldBe 4
        settings.farms.single().rivalRaid.workerRadius shouldBe 64.0
        settings.farms.single().rivalRaid.flightHeight shouldBe 14.0
        settings.farms.single().rivalRaid.flightSpeed shouldBe 0.30
        settings.farms.single().rivalRaid.flightSteering shouldBe 0.65
        settings.farms.single().rivalRaid.orbitRadius shouldBe 36.0
        settings.farms.single().rivalRaid.orbitLookAheadDegrees shouldBe 12.0
        settings.farms.single().rivalRaid.seatSpacing shouldBe 1.6
        settings.farms.single().rivalRaid.seatYOffset shouldBe -2.6
        settings.farms.single().rivalRaid.portalWidth shouldBe 3.6f
        settings.farms.single().rivalRaid.portalHeight shouldBe 3.2f
        settings.farms.single().rivalRaid.portalLabelHeight shouldBe 3.35
        settings.farms.single().rivalRaid.portalLabelScale shouldBe 1.8f
        settings.farms.single().rivalRaid.portalActivationSeconds shouldBe 3
        settings.farms.single().music.rivalRaidSound shouldBe "minecraft:music_disc.pigstep"
        settings.farms.single().specialIncidents.nightCropPlacementCount shouldBe 90
        settings.farms.single().specialIncidents.nightCropTargetCount shouldBe 24
        settings.farms.single().specialIncidents.nightCropMinSpacing shouldBe 6.0
        settings.farms.single().specialIncidents.nightTimeTransitionSeconds shouldBe 12
        settings.farms.single().specialIncidents.giantCropParticleStride shouldBe 4
        settings.farms.single().specialIncidents.birdMinCount shouldBe 6
        settings.farms.single().specialIncidents.birdMaxCount shouldBe 14
        settings.farms.single().specialIncidents.birdSpawnMultiplier shouldBe 2
        settings.farms.single().specialIncidents.birdFlyingSpeed shouldBe 0.65
        settings.farms.single().specialIncidents.birdCount(1_000) shouldBe 6
        settings.farms.single().specialIncidents.nightPatrolMinCount shouldBe 3
        settings.farms.single().specialIncidents.nightPatrolMaxCount shouldBe 10
        settings.farms.single().specialIncidents.nightPatrolBedsPerPatrol shouldBe 250
        settings.farms.single().specialIncidents.nightPatrolCount(0) shouldBe 0
        settings.farms.single().specialIncidents.nightPatrolCount(100) shouldBe 3
        settings.farms.single().specialIncidents.nightPatrolCount(1_251) shouldBe 6
        settings.farms.single().specialIncidents.nightPatrolCount(10_000) shouldBe 10
        settings.farms.single().specialIncidents.nightPatrolEntity shouldBe "HUSK"
        settings.farms.single().specialIncidents.nightPatrolMinSpacing shouldBe 12.0
        settings.farms.single().specialIncidents.nightPatrolRoamRadius shouldBe 14.0
        settings.farms.single().specialIncidents.nightPatrolPathRefreshSeconds shouldBe 5
        settings.farms.single().specialIncidents.nightPatrolSpawnMinPlayerDistance shouldBe 8.0
        settings.farms.single().specialIncidents.nightPatrolReceivingSafeRadius shouldBe 15.0
        settings.farms.single().specialIncidents.nightPatrolMovementSpeed shouldBe 0.27
        settings.farms.single().specialIncidents.nightPatrolFollowRange shouldBe 8.0
        settings.farms.single().specialIncidents.nightPatrolAttackDamage shouldBe 2.0
        settings.farms.single().specialIncidents.nightPatrolHeldItem shouldBe "TORCH"
        settings.farms.single().specialIncidents.nightPatrolLightLevel shouldBe 15
        settings.farms.single().specialIncidents.marketMoneyBonusPercent shouldBe 25
        settings.farms.single().specialIncidents.marketCropCount shouldBe 256
        settings.farms.single().specialIncidents.marketBaseSeconds shouldBe 30
        settings.farms.single().specialIncidents.marketSecondsPerCrop shouldBe 0.3
        settings.farms.single().specialIncidents.marketMinimumSeconds shouldBe 90
        settings.farms.single().specialIncidents.marketMaximumSeconds shouldBe 180
        settings.farms.single().specialIncidents.frost.carriedForwardOffset shouldBe 0.65
        settings.farms.single().specialIncidents.frost.campfireMarkerMaterial shouldBe "SOUL_LANTERN"
        settings.farms.single().specialIncidents.frost.campfireMarkerParticleHeight shouldBe 4.0
        settings.farms.single().specialIncidents.frost.playerTime shouldBe 13_000L
        settings.farms.single().specialIncidents.frost.timeTransitionSeconds shouldBe 12
        settings.farms.single().specialIncidents.frost.downfall shouldBe true
        settings.farms.single().pestNestCount shouldBe 3
        settings.farms.single().pestNestHealth shouldBe 3
        settings.farms.single().pestSpawnsPerNest shouldBe 3
        settings.farms.single().pestMaxAlive shouldBe 6
        settings.farms.single().pestEatRadius shouldBe 3
        settings.farms.single().delivery.world shouldBe "sp11"
        settings.farms.single().delivery.x shouldBe 201.65
        settings.farms.single().delivery.crates shouldBe 3
        settings.farms.single().delivery.spawnRadius shouldBe 24
        settings.farms.single().delivery.minCrateSpacing shouldBe 5.0
        settings.farms.single().delivery.pickup.z shouldBe 463.5
        settings.farms.single().delivery.itemMaterial shouldBe "BARREL"
        settings.farms.single().delivery.itemCustomModelData shouldBe 0
        settings.farms.single().delivery.displayTransform shouldBe FarmItemDisplayTransform.GROUND
        settings.farms.single().delivery.displayScale shouldBe 2.0f
        settings.farms.single().delivery.displayYOffset shouldBe 0.55
        settings.farms.single().delivery.carriedScale shouldBe 1.5f
        settings.farms.single().delivery.carriedYOffset shouldBe 0.65
        settings.farms.single().delivery.displayViewRange shouldBe 2.0f
        settings.farms.single().routeDelivery.corridorRadius shouldBe 5.0
        settings.farms.single().routeDelivery.hardResetDistance shouldBe 9.0
        settings.farms.single().routeDelivery.trailLookaheadPoints shouldBe 28
        settings.farms.single().routeDelivery.trailHeight shouldBe 0.35
        settings.farms.single().routeDelivery.trailParticleSize shouldBe 1.15f
        settings.farms.single().routeDelivery.trailSpacing shouldBe 0.7
        settings.farms.single().routeDelivery.horseSpeed shouldBe 0.17
        settings.farms.single().routeDelivery.horseJumpStrength shouldBe 0.45
        settings.farms.single().routeDelivery.portalArrivalSideOffset shouldBe 3.0
        settings.farms.single().routeDelivery.portalActivationSeconds shouldBe 3
        settings.farms.single().routeDelivery.cartBackOffset shouldBe 2.15
        settings.farms.single().routeDelivery.cartYOffset shouldBe 0.875
        settings.farms.single().routeDelivery.gunnerSeatYOffset shouldBe -0.15
        settings.farms.single().routeDelivery.gunnerSeatBackOffset shouldBe 0.65
        settings.farms.single().routeDelivery.gunnerInteractionWidth shouldBe 2.8f
        settings.farms.single().routeDelivery.gunnerInteractionHeight shouldBe 0.7f
        settings.farms.single().routeDelivery.cartLoadCount shouldBe 4
        settings.farms.single().routeDelivery.cartLoadSpacing shouldBe 0.24
        settings.farms.single().routeDelivery.ambushDistance shouldBe 120.0
        settings.farms.single().routeDelivery.ambushMaxCount shouldBe 3
        settings.farms.single().routeDelivery.ambushAfterFarmDistance shouldBe 20.0
        settings.farms.single().routeDelivery.ambushEndSafeDistance shouldBe 20.0
        settings.farms.single().routeDelivery.monsterWaveMin shouldBe 5
        settings.farms.single().routeDelivery.monsterWaveMax shouldBe 8
        settings.farms.single().routeDelivery.monsterMaxAlive shouldBe 12
        settings.farms.single().routeDelivery.monsterTypes shouldBe listOf("HUSK", "ZOMBIE", "SKELETON", "SPIDER", "PHANTOM")
        settings.farms.single().routeDelivery.monsterLightLevel shouldBe 15
        settings.farms.single().routeDelivery.rifleMaterial shouldBe "CROSSBOW"
        settings.farms.single().routeDelivery.rifleCustomModelData shouldBe 2_100_103
        settings.farms.single().routeDelivery.rifleItemModel shouldBe null
        settings.farms.single().routeDelivery.rifleDamage shouldBe 7.0
        settings.farms.single().routeDelivery.rifleRange shouldBe 42.0
        settings.farms.single().routeDelivery.rifleCooldownTicks shouldBe 6
        settings.farms.single().routeDelivery.rifleRaySize shouldBe 0.65
        settings.farms.single().routeDelivery.playerTime shouldBe 18_000L
        settings.farms.single().routeDelivery.timeTransitionSeconds shouldBe 18
        settings.farms.single().routeDelivery.inactivityReminderSeconds shouldBe 20
        settings.farms.single().routeDelivery.inactivityResetSeconds shouldBe 45
        settings.farms.single().routeDelivery.inactivityMovementDistance shouldBe 2.0
        settings.farms.single().damageSafety.maximumPercent shouldBe 18
        settings.farms.single().damageSafety.minimumRemaining shouldBe 128
        settings.farms.single().perks.harvestArea.price shouldBe 250L
        settings.farms.single().perks.rewardBoost.durationHours shouldBe 72
        settings.farms.single().perks.rewardBonusPercent shouldBe 25
        settings.farms.single().supplies.tool.x shouldBe 212.5
        settings.farms.single().supplies.tool.z shouldBe 448.5
        settings.farms.single().supplies.seeds.z shouldBe 453.5
        settings.farms.single().supplies.water.z shouldBe 458.5
        settings.farms.single().supplies.archery.z shouldBe 468.5
        settings.farms.single().rewards.experience.amount shouldBe 100
        settings.farms.single().rewards.experience.chancePercent shouldBe 100
        settings.farms.single().rewards.money.amountCents shouldBe 0
        settings.lumbermills.single().fellingQuota shouldBe 16
        settings.mines.all { it.cartQuota == 16 && it.supportsRequired == 1 } shouldBe true
        ArcFarmsRedisBootstrap.load(root, settings).serverName shouldBe "spawn"
        ArcFarmsLocale.validateFiles(root, settings)
        listOf("lang/ru.yml", "lang/en.yml").forEach { path ->
            listOf("classic", "classic_survival", "parkour").forEach { runtime ->
                Files.readString(repositoryRoot.resolve("$runtime/plugins/ArcFarms/$path")) shouldBe
                    Files.readString(root.resolve(path))
            }
        }
        val classicSettings = ArcFarmsConfig.inspect(repositoryRoot.resolve("classic/plugins/ArcFarms"))
        classicSettings.farmScoreboard.provider shouldBe FarmScoreboardProvider.TAB
        classicSettings.farms.single().delivery.itemMaterial shouldBe "PAPER"
        classicSettings.farms.single().delivery.itemCustomModelData shouldBe 10_774
        classicSettings.farms.single().delivery.spawnRadius shouldBe 24
        classicSettings.farms.single().delivery.minCrateSpacing shouldBe 5.0
        classicSettings.farms.single().delivery.displayScale shouldBe 1.8f
        classicSettings.farms.single().delivery.displayYOffset shouldBe 0.55
        classicSettings.farms.single().delivery.carriedScale shouldBe 1.4f
        classicSettings.farms.single().careVisuals.getValue(FarmCareRole.VALVE).customModelData shouldBe 11_859
        classicSettings.farms.single().careVisuals.getValue(FarmCareRole.SCARECROW).let { scarecrow ->
            scarecrow.customModelData shouldBe 11_874
            scarecrow.displayTransform shouldBe FarmItemDisplayTransform.GROUND
            scarecrow.displayScale shouldBe 2.0f
            scarecrow.displayYOffset shouldBe 0.0
        }
        classicSettings.farms.single().careVisuals.getValue(FarmCareRole.PEN).customModelData shouldBe 11_864
        classicSettings.farms.single().moleBurrow.lairVisual.material shouldBe "PAPER"
        classicSettings.farms.single().moleBurrow.lairVisual.customModelData shouldBe 11_875
        classicSettings.farms.single().routeDelivery.horseSpeed shouldBe 0.26
        classicSettings.farms.single().routeDelivery.cartYOffset shouldBe 0.875
        classicSettings.farms.single().routeDelivery.monsterWaveMin shouldBe 5
        classicSettings.farms.single().routeDelivery.monsterWaveMax shouldBe 8
        classicSettings.farms.single().damageSafety.minimumRemaining shouldBe 128
        classicSettings.farms.single().music.enabled shouldBe true
        classicSettings.farms.single().music.sound shouldBe "arc:farm_valley_comes_alive"
        classicSettings.farms.single().music.durationSeconds shouldBe 262
        classicSettings.farms.single().music.volume shouldBe 0.65f
        classicSettings.farms.single().backupMaxBlocks shouldBe 10_000_000
        classicSettings.farms.single().contractCartVisual.material shouldBe "PAPER"
        classicSettings.farms.single().contractCartVisual.customModelData shouldBe 10_747
        classicSettings.farms.single().contractCartVisual.displayTransform shouldBe FarmItemDisplayTransform.GROUND
        classicSettings.farms.single().contractCartVisual.scale shouldBe 4.4f
        classicSettings.farms.single().contractCartVisual.yOffset shouldBe 0.00
        classicSettings.farms.single().contractCartVisual.loadScale shouldBe 1.55f
        classicSettings.farms.single().contractCartVisual.viewRange shouldBe 2.0f
        classicSettings.farms.single().rewards.money.amountCents shouldBe 50_000
        classicSettings.farms.single().rewards.money.chancePercent shouldBe 100
        classicSettings.farms.single().rewards.items.single().id shouldBe "golden_apple"
        classicSettings.farms.single().rewards.items.single().chancePercent shouldBe 5
        classicSettings.farms.single().rewards.randomBundles.rolls shouldBe 1
        classicSettings.farms.single().rewards.randomBundles.chancePercent shouldBe 85
        classicSettings.farms.single().rewards.randomBundles.entries.map { it.id } shouldContainExactly listOf(
            "beekeeper_basket",
            "field_lunch",
            "hearty_rations",
        )
        classicSettings.menuBackground.enabled shouldBe true
        classicSettings.menuBackground.material shouldBe "GRAY_STAINED_GLASS_PANE"
        classicSettings.menuBackground.customModelData shouldBe 11_000
        classicSettings.menuBack.material shouldBe "BLUE_STAINED_GLASS_PANE"
        classicSettings.menuBack.customModelData shouldBe 11_013
        classicSettings.enterprises.getValue(ActivityKind.FARM).mode shouldBe WorksiteEnterpriseMode.SHADOW
        classicSettings.farmScoreboard.enabled shouldBe true
        classicSettings.farmScoreboard.replaceExisting shouldBe true

        Files.readString(
            repositoryRoot.resolve(
                "classic/plugins/ItemsAdder/contents/elitecreatures/configs/medieval/pack_medieval_market_decoration_v1.yml",
            ),
        ) shouldContain "medieval_market_decoration_v1_cart_2:"
        Files.readString(
            repositoryRoot.resolve(
                "classic/plugins/ItemsAdder/contents/elitecreatures/configs/medieval/pack_medieval_market_decoration_v2.yml",
            ),
        ) shouldContain "medieval_market_decoration_v2_crate_3:"
        Files.readString(repositoryRoot.resolve("classic/plugins/ItemsAdder/storage/items_ids_cache.yml")) shouldContain
            "elitecreatures:medieval_market_decoration_v1_cart_2: 10747"
        Files.readString(repositoryRoot.resolve("classic/plugins/ItemsAdder/storage/items_ids_cache.yml")) shouldContain
            "elitecreatures:medieval_market_decoration_v2_crate_3: 10774"
    }

    test("survival and parkour profiles are standalone network relays") {
        val repositoryRoot = opsRoot()
        mapOf(
            "classic" to "spawn",
            "classic_survival" to "survival",
            "parkour" to "parkour",
        ).forEach { (runtime, expectedServerId) ->
            val root = repositoryRoot.resolve("$runtime/plugins/ArcFarms")
            val settings = ArcFarmsConfig.inspect(root)
            val redis = RedisModuleConfig(Config(root, "modules/redis.yml"))

            settings.serverId shouldBe expectedServerId
            settings.network.enabled shouldBe true
            redis.enabled shouldBe true
            redis.serverName shouldBe expectedServerId
            if (runtime != "classic") {
                settings.requiresWorldGuard shouldBe false
                settings.farms shouldBe emptyList()
                settings.lumbermills shouldBe emptyList()
                settings.mines shouldBe emptyList()
                settings.farmScoreboard.provider shouldBe FarmScoreboardProvider.TAB
                settings.farmScoreboard.enabled shouldBe (runtime == "classic_survival")
                settings.destinations.values.all { it.server == "spawn" } shouldBe true
            }
            ArcFarmsLocale.validateFiles(root, settings)
            settings.menuBackground.enabled shouldBe true
            settings.menuBackground.material shouldBe "GRAY_STAINED_GLASS_PANE"
            settings.menuBackground.customModelData shouldBe 11_000
            settings.menuBack.material shouldBe "BLUE_STAINED_GLASS_PANE"
            settings.menuBack.customModelData shouldBe 11_013
            settings.enterprises.getValue(ActivityKind.FARM).mode shouldBe
                if (runtime == "classic") WorksiteEnterpriseMode.SHADOW else WorksiteEnterpriseMode.OFF
        }
    }

    test("invalid mine contract is rejected before startup") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        configPath.writeText(
            Files.readString(configPath).replace("hazard-trigger: 6", "hazard-trigger: 16"),
        )

        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(root) }
    }

    test("farm delivery point outside an explicit farm cuboid is rejected") {
        val repositoryRoot = opsRoot()
        val root = resourceTree(repositoryRoot.resolve("scripts/lab/plugin-configs/ArcFarms/config.yml"))
        val configPath = root.resolve("config.yml")
        configPath.writeText(
            Files.readString(configPath).replace(
                "      x: -3.5",
                "      x: 50.0",
            ),
        )

        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(root) }
    }

    test("ItemsAdder custom model data may use the full positive integer range") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        configPath.writeText(
            Files.readString(configPath).replace(
                "fire-equipment-custom-model-data: 0",
                "fire-equipment-custom-model-data: 2100104",
            ),
        )

        ArcFarmsConfig.inspect(root).farms.single().supplies.fireEquipmentCustomModelData shouldBe 2_100_104
    }

    test("supply nearby visibility distance is configurable in blocks") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        configPath.writeText(
            Files.readString(configPath).replace(
                "supply-nearby-view-distance: 15.0",
                "supply-nearby-view-distance: 45.0",
            ),
        )

        ArcFarmsConfig.inspect(root).farms.single().supplyNearbyViewDistance shouldBe 45.0f
    }

    test("farm gameplay and presentation tunables load from the bundled config") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        configPath.writeText(
            Files.readString(configPath)
                .replace("vertical-tolerance: 2.5", "vertical-tolerance: 3.25")
                .replace("animal-follow-impulse-base: 0.16", "animal-follow-impulse-base: 0.2")
                .replace("animal-follow-impulse-per-block: 0.025", "animal-follow-impulse-per-block: 0.04")
                .replace("animal-follow-impulse-max: 0.42", "animal-follow-impulse-max: 0.6")
                .replace("animal-follow-impulse-smoothing: 0.25", "animal-follow-impulse-smoothing: 0.4")
                .replace("hard-correction-strength: 0.42", "hard-correction-strength: 0.55")
                .replace("corridor-correction-strength: 0.24", "corridor-correction-strength: 0.3")
                .replace("spawn-min-multiplier: 0.8", "spawn-min-multiplier: 0.7")
                .replace("spawn-max-multiplier: 1.15", "spawn-max-multiplier: 1.2")
                .replace("phantom-spawn-height: 7.0", "phantom-spawn-height: 9.0")
                .replace("radius: 1}", "radius: 2}")
                .replace("amplifier: 0, refresh-ticks: 60", "amplifier: 1, refresh-ticks: 80")
                .replace("food: 2, saturation: 1.0, health: 1.0", "food: 3, saturation: 1.5, health: 0.5")
                .replace("supply-millis: 500", "supply-millis: 550")
                .replace("delivery-millis: 500", "delivery-millis: 600")
                .replace("patch-miss-millis: 500", "patch-miss-millis: 650")
                .replace("care-millis: 100", "care-millis: 150")
                .replace("care-target-millis: 250", "care-target-millis: 300")
                .replace("mole-millis: 500", "mole-millis: 700")
                .replace("frost-pickup-millis: 750", "frost-pickup-millis: 800")
                .replace("contract-scene-millis: 700", "contract-scene-millis: 900"),
        )
        val farm = ArcFarmsConfig.inspect(root).farms.single()

        farm.pestEatIntervalMillis shouldBe 1_000L
        farm.pestNestMinSpacing shouldBe 16.0
        farm.pestNestDisplayScale shouldBe 2.0f
        farm.scarecrowMinSpacing shouldBe 12.0
        farm.specialIncidents.giantCropHitCooldownMillis shouldBe 90L
        farm.cropEffects.blockParticleCount shouldBe 14
        farm.cropEffects.dustParticleCount shouldBe 7
        farm.cropEffects.composterParticleCount shouldBe 3
        farm.cropEffects.poofParticleCount shouldBe 5
        farm.barnFire.waterSideStreams shouldBe 4
        farm.supplies.itemScale shouldBe 1.35f
        farm.processing.crankVerticalTolerance shouldBe 3.25
        farm.careAnimalFollowImpulseBase shouldBe 0.2
        farm.careAnimalFollowImpulsePerBlock shouldBe 0.04
        farm.careAnimalFollowImpulseMax shouldBe 0.6
        farm.careAnimalFollowImpulseSmoothing shouldBe 0.4
        farm.routeDelivery.hardCorrectionStrength shouldBe 0.55
        farm.routeDelivery.corridorCorrectionStrength shouldBe 0.3
        farm.routeDelivery.monsterSpawnMinMultiplier shouldBe 0.7
        farm.routeDelivery.monsterSpawnMaxMultiplier shouldBe 1.2
        farm.routeDelivery.phantomSpawnHeight shouldBe 9.0
        farm.perks.harvestAreaRadius shouldBe 2
        farm.perks.speedAmplifier shouldBe 1
        farm.perks.speedRefreshTicks shouldBe 80
        farm.perks.sustenanceFood shouldBe 3
        farm.perks.sustenanceSaturation shouldBe 1.5f
        farm.perks.sustenanceHealth shouldBe 0.5
        farm.inputCooldowns.supplyMillis shouldBe 550L
        farm.inputCooldowns.deliveryMillis shouldBe 600L
        farm.inputCooldowns.patchMissMillis shouldBe 650L
        farm.inputCooldowns.careMillis shouldBe 150L
        farm.inputCooldowns.careTargetMillis shouldBe 300L
        farm.inputCooldowns.moleMillis shouldBe 700L
        farm.inputCooldowns.frostPickupMillis shouldBe 800L
        farm.inputCooldowns.contractSceneMillis shouldBe 900L
    }

    test("farm gameplay tunables reject unsafe values before startup") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        val config = Files.readString(configPath)
            .replace("pest-eat-interval-millis: 1000", "pest-eat-interval-millis: 99")
            .replace("side-streams: 4", "side-streams: 9")
            .replace("water-radius: 5", "water-radius: 0")
            .replace("follow-snap-distance: 6.0", "follow-snap-distance: 1.0")
            .replace("animal-follow-impulse-max: 0.42", "animal-follow-impulse-max: 0.01")
        configPath.writeText(config)

        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(root) }
    }

    test("farm patch size is bounded before runtime scanning") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        configPath.writeText(
            Files.readString(configPath).replace("preparation-patch-size: 100", "preparation-patch-size: 513"),
        )

        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(root) }
    }

    test("mole maze dimensions are bounded before journal planning") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        configPath.writeText(
            Files.readString(configPath).replace("      cells: 8", "      cells: 11"),
        )

        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(root) }
    }

    test("farm contract cart transform is validated before entity creation") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        configPath.writeText(
            Files.readString(configPath).replace("display-transform: GROUND", "display-transform: SIDEWAYS"),
        )

        shouldThrow<IllegalStateException> { ArcFarmsConfig.inspect(root) }
            .message shouldContain "display-transform"
    }

    test("rare contract chance is rejected when the farm has no rare order") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        configPath.writeText(Files.readString(configPath).replace("rarity: RARE", "rarity: COMMON"))

        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(root) }
    }

    test("farm reward chances and money precision are validated before startup") {
        val chanceRoot = resourceTree()
        chanceRoot.resolve("config.yml").writeText(
            Files.readString(chanceRoot.resolve("config.yml"))
                .replace("chance-percent: 100\n      money:", "chance-percent: 101\n      money:"),
        )
        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(chanceRoot) }

        val moneyRoot = resourceTree()
        moneyRoot.resolve("config.yml").writeText(
            Files.readString(moneyRoot.resolve("config.yml")).replace("amount: 0\n        chance-percent", "amount: 1.001\n        chance-percent"),
        )
        shouldThrow<IllegalStateException> { ArcFarmsConfig.inspect(moneyRoot) }
    }

    test("farm command rewards reject unknown placeholders") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        configPath.writeText(
            Files.readString(configPath).replace(
                "      commands: {}",
                """
                      commands:
                        broken:
                          command: 'crate give %nickname% farm'
                          chance-percent: 100
                """.trimIndent(),
            ),
        )

        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(root) }
    }

    test("farm patch expansion cap cannot be smaller than its target") {
        val configPath = resourceTree().resolve("config.yml")
        Files.writeString(
            configPath,
            Files.readString(configPath).replace("preparation-patch-max-size: 256", "preparation-patch-max-size: 80"),
        )

        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(configPath.parent) }
            .message shouldContain "preparation-patch-max-size"
    }

    test("night shift completion target cannot exceed its visible crop count") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        configPath.writeText(
            Files.readString(configPath).replace("target-count: 24", "target-count: 100"),
        )

        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(root) }
            .message shouldContain "crop target"
    }

    test("mechanized fieldwork explains mounted pig-team control without checkpoints") {
        val repositoryRoot = Path.of(requireNotNull(System.getProperty("arcfarms.projectDir")))
        val root = repositoryRoot.resolve("src/main/resources")
        val ru = Config(root, "lang/ru.yml")
        val en = Config(root, "lang/en.yml")

        ru.string("farm.care-seeder-following") shouldContain "свиньи"
        ru.string("scoreboard.hint.care.seeder-tilling") shouldNotContain "метк"
        en.string("farm.care-seeder-following") shouldContain "pigs"
        en.string("scoreboard.hint.care.seeder-planting") shouldNotContain "marker"
    }

    test("movable processing points are never described by left or right directions") {
        val repositoryRoot = Path.of(requireNotNull(System.getProperty("arcfarms.projectDir")))
        val root = repositoryRoot.resolve("src/main/resources")
        val keys = listOf(
            "farm.processing.loading-subtitle",
            "farm.processing.packing-subtitle",
            "farm.processing.loading-hint",
            "farm.processing.packing-hint",
            "farm.processing.product-picked-up-subtitle",
            "scoreboard.hint.processing-loading",
            "scoreboard.hint.processing-packing",
        )
        val ru = Config(root, "lang/ru.yml")
        val en = Config(root, "lang/en.yml")

        val russianGuidance = keys.joinToString(" ") { ru.string(it).lowercase() }
        listOf("слева", "справа", "левый", "правый").forEach(russianGuidance::shouldNotContain)
        val englishGuidance = keys.joinToString(" ") { en.string(it).lowercase() }
        listOf("left", "right").forEach(englishGuidance::shouldNotContain)
    }

    test("mechanized patch remains bounded before runtime scanning") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        configPath.writeText(
            Files.readString(configPath).replace("seeder-patch-max-size: 6144", "seeder-patch-max-size: 6145"),
        )

        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(root) }
            .message shouldContain "seeder-patch-max-size"
    }

    test("mechanized pig team remains bounded and can catch up with the horse") {
        val pigCountRoot = resourceTree()
        val pigCountConfig = pigCountRoot.resolve("config.yml")
        pigCountConfig.writeText(
            Files.readString(pigCountConfig).replace("seeder-pig-count: 3", "seeder-pig-count: 6"),
        )
        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(pigCountRoot) }
            .message shouldContain "seeder-pig-count"

        val catchupRoot = resourceTree()
        val catchupConfig = catchupRoot.resolve("config.yml")
        catchupConfig.writeText(
            Files.readString(catchupConfig).replace("seeder-pig-catchup-distance: 12.0", "seeder-pig-catchup-distance: 2.5"),
        )
        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(catchupRoot) }
            .message shouldContain "seeder-pig-catchup-distance"
    }

    test("rival raid portal geometry and countdown stay bounded") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        configPath.writeText(
            Files.readString(configPath).replace(
                "          activation-seconds: 3\n        player-time: 18000",
                "          activation-seconds: 16\n        player-time: 18000",
            ),
        )

        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(root) }
            .message shouldContain "special-incidents.rival-raid.portal.activation-seconds"
    }

    test("locale parity includes dynamic order route and phase paths") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val english = root.resolve("lang/en.yml")
        english.writeText(Files.readString(english).replace("    old_shafts: '<aqua>Old Shafts</aqua>'\n", ""))

        shouldThrow<IllegalArgumentException> { ArcFarmsLocale.validateFiles(root, settings) }
    }

    test("locale validation rejects placeholder typos and placeholder closing tags") {
        val typoRoot = resourceTree()
        val typoSettings = ArcFarmsConfig.inspect(typoRoot)
        typoRoot.resolve("lang/en.yml").writeText(
            Files.readString(typoRoot.resolve("lang/en.yml")).replace(
                "<color:#707a76>Accrued:</color> <color:#ffc857><amount></color>",
                "<color:#707a76>Accrued:</color> <color:#ffc857><amout></color>",
            ),
        )
        shouldThrow<IllegalArgumentException> { ArcFarmsLocale.validateFiles(typoRoot, typoSettings) }
            .message shouldContain "unknown placeholder"

        val closingRoot = resourceTree()
        val closingSettings = ArcFarmsConfig.inspect(closingRoot)
        listOf("ru", "en").forEach { language ->
            val path = closingRoot.resolve("lang/$language.yml")
            path.writeText(Files.readString(path).replace("<amount></color>", "<amount></amount>"))
        }
        shouldThrow<IllegalArgumentException> { ArcFarmsLocale.validateFiles(closingRoot, closingSettings) }
            .message shouldContain "unsupported closing tag"

        val misspelledClosingRoot = resourceTree()
        val misspelledClosingSettings = ArcFarmsConfig.inspect(misspelledClosingRoot)
        listOf("ru", "en").forEach { language ->
            val path = misspelledClosingRoot.resolve("lang/$language.yml")
            path.writeText(Files.readString(path).replace("<amount></color>", "<amount></amout>"))
        }
        shouldThrow<IllegalArgumentException> {
            ArcFarmsLocale.validateFiles(misspelledClosingRoot, misspelledClosingSettings)
        }.message shouldContain "unsupported closing tag"

        val standaloneClosingRoot = resourceTree()
        val standaloneClosingSettings = ArcFarmsConfig.inspect(standaloneClosingRoot)
        listOf("ru", "en").forEach { language ->
            val path = standaloneClosingRoot.resolve("lang/$language.yml")
            path.writeText(Files.readString(path).replace("reload-ok:", "reload-ok: '</amout>' #"))
        }
        shouldThrow<IllegalArgumentException> {
            ArcFarmsLocale.validateFiles(standaloneClosingRoot, standaloneClosingSettings)
        }.message shouldContain "unsupported closing tag"
    }

    test("locale validation rejects line-feed controls in screen titles") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val russian = root.resolve("lang/ru.yml")
        russian.writeText(
            Files.readString(russian).replace(
                "<color:#45c8f5><bold>Новый поливной канал</bold></color>",
                "<color:#45c8f5><bold>Новый поливной канал</bold></color><newline><white>Лишняя строка</white>",
            ),
        )

        shouldThrow<IllegalArgumentException> { ArcFarmsLocale.validateFiles(root, settings) }
            .message shouldContain "screen title contains a line break"
    }

    test("locale synchronization backfills bundled keys without replacing operator translations") {
        val root = resourceTree()
        val russian = root.resolve("lang/ru.yml")
        val english = root.resolve("lang/en.yml")
        russian.writeText(
            Files.readString(russian)
                .replace("prefix: '<color:#55d98b>Ферма</color> <color:#666666>•</color>'", "prefix: '<gold>Моя ферма</gold> '")
                .replace("  entry-frost: '<color:#f2fff7>Берите поленья в дровнице и разжигайте костры на поле</color>'\n", ""),
        )
        english.writeText(
            Files.readString(english)
                .replace("  entry-frost: '<color:#f2fff7>Carry logs from the woodpile and light the campfires in the field</color>'\n", ""),
        )

        ArcFarmsLocale.synchronizeFiles(root)
        val settings = ArcFarmsConfig.inspect(root)
        ArcFarmsLocale.validateFiles(root, settings)

        Config(root, "lang/ru.yml").string("prefix") shouldBe "<gold>Моя ферма</gold> "
        Config(root, "lang/ru.yml").string("farm.entry-frost") shouldContain "поленья"
        Config(root, "lang/en.yml").string("farm.entry-frost") shouldContain "logs"
        val synchronized = listOf(russian, english).map(Files::readString)
        ArcFarmsLocale.synchronizeFiles(root)
        listOf(russian, english).map(Files::readString) shouldBe synchronized
    }

    test("config synchronization persists bundled defaults without populating empty activity maps") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        configPath.writeText(
            Files.readString(configPath)
                .replace("bossbars: true", "bossbars: false")
                .replace("  title-stay-seconds: 4\n", "")
                .replace("    initial-retry-seconds: 1\n", ""),
        )

        val settings = ArcFarmsConfig.load(root)

        settings.bossbars shouldBe false
        settings.titleStaySeconds shouldBe 4
        settings.shiftStartPersistence.initialRetrySeconds shouldBe 1
        Files.readString(configPath) shouldContain "title-stay-seconds: 4"
        Files.readString(configPath) shouldContain "initial-retry-seconds: 1"

        val relayRoot = resourceTree()
        Config(relayRoot, "config.yml").also { relay ->
            relay.setStructured("farm-zones", emptyMap<String, Any>())
            relay.setStructured("lumber-zones", emptyMap<String, Any>())
            relay.setStructured("mine-zones", emptyMap<String, Any>())
            relay.saveStrict()
        }
        ArcFarmsConfig.load(relayRoot)
        Files.readString(relayRoot.resolve("config.yml")) shouldContain "farm-zones: {}"
        Files.readString(relayRoot.resolve("config.yml")) shouldContain "lumber-zones: {}"
        Files.readString(relayRoot.resolve("config.yml")) shouldContain "mine-zones: {}"
        ArcFarmsConfig.inspect(relayRoot).enterprises.getValue(ActivityKind.FARM).mode shouldBe WorksiteEnterpriseMode.OFF
    }

    test("config synchronization preserves the environment-owned enterprise tariff set") {
        val root = resourceTree()
        Config(root, "config.yml").also { config ->
            config.setStructured(
                "enterprises.farm.order-gross-tariffs",
                linkedMapOf("bakery_supply" to "10000.00"),
            )
            config.saveStrict()
        }

        val settings = ArcFarmsConfig.load(root)

        settings.enterprises.getValue(ActivityKind.FARM).orderGrossTariffsCents.keys shouldBe
            setOf("bakery_supply")
        Config(root, "config.yml").keys("enterprises.farm.order-gross-tariffs") shouldBe
            setOf("bakery_supply")
    }

    test("locale reload publishes one validated generation atomically") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }
        val plain = PlainTextComponentSerializer.plainText()
        val previous = plain.serialize(locale.render(MessageKey.RELOAD_OK))

        Config(root, "lang/ru.yml").also { russian ->
            russian.setString("reload-ok", "<prefix> <color:#f2fff7>Новая конфигурация</color>")
            russian.saveStrict()
        }
        val prepared = locale.prepareReload(settings)
        Config(root, "lang/ru.yml").also { russian ->
            russian.setString("reload-ok", "<prefix> <red>Непроверенная поздняя запись</red>")
            russian.saveStrict()
        }

        plain.serialize(locale.render(MessageKey.RELOAD_OK)) shouldBe previous
        locale.publish(prepared)
        plain.serialize(locale.render(MessageKey.RELOAD_OK)) shouldBe "Ферма • Новая конфигурация"

        Config(root, "lang/ru.yml").also { russian ->
            russian.setString("reload-ok", "")
            russian.saveStrict()
        }
        shouldThrow<IllegalArgumentException> { locale.prepareReload(settings) }
        plain.serialize(locale.render(MessageKey.RELOAD_OK)) shouldBe "Ферма • Новая конфигурация"
    }

    test("startup load reads the latest accepted disk config in the same JVM") {
        val root = resourceTree()
        ArcFarmsConfig.load(root).titleStaySeconds shouldBe 4

        Config(root, "config.yml").also { changed ->
            changed.setStructured("ui.title-stay-seconds", 3)
            changed.saveStrict()
        }

        ArcFarmsConfig.load(root).titleStaySeconds shouldBe 3
    }

    test("hex-colored farm bossbar renders without leaking MiniMessage tags") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }
        val rendered = locale.render(
            MessageKey.FARM_BOSSBAR,
            values = mapOf(
                "order" to Component.text("Заказ"),
                "crop" to Component.text("Пшеница"),
                "requirements" to Component.text("Пшеница 0/2, Морковь 0/2"),
                "done" to Component.text("0"),
                "total" to Component.text("4"),
            ),
        )

        PlainTextComponentSerializer.plainText().serialize(rendered) shouldBe
            "Пшеница 0/2, Морковь 0/2"
    }

    test("localized chat prefix identifies the farm instead of a shift") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }

        PlainTextComponentSerializer.plainText().serialize(locale.render(MessageKey.PREFIX)) shouldBe "Ферма •"
    }

    test("help never renders command arguments as html entities") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }

        listOf("ru.yml", "en.yml").forEach { fileName ->
            val source = Files.readString(root.resolve("lang").resolve(fileName))
            source shouldNotContain "&lt;"
            source shouldNotContain "&gt;"
        }

        val shortAdminHelp = PlainTextComponentSerializer.plainText().serialize(locale.render(MessageKey.ADMIN_HELP))
        shortAdminHelp shouldNotContain "&lt;"
        shortAdminHelp shouldNotContain "&gt;"
        shortAdminHelp shouldNotContain "<"
        shortAdminHelp shouldNotContain ">"

        listOf(
            locale.render(MessageKey.HELP),
            locale.render(MessageKey.ADMIN_HELP_BLOCKRESET),
            locale.render(MessageKey.ADMIN_DEBUG_HELP),
        ).forEach { component ->
            val text = PlainTextComponentSerializer.plainText().serialize(component)
            text shouldNotContain "&lt;"
            text shouldNotContain "&gt;"
            text shouldContain "<"
            text shouldContain ">"
        }
    }

    test("farm reward chat is a rare framed block with the exact delivered reward") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }
        val rendered = locale.render(
            MessageKey.FARM_REWARD_CHAT,
            values = mapOf("reward" to Component.text("75 опыта, 500 монет")),
        )

        PlainTextComponentSerializer.plainText().serialize(rendered) shouldBe
            "\n  Награда за заказ\n  • 75 опыта, 500 монет\n"
    }

    test("planting bossbar renders the exact next action without a chat prefix") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }
        val rendered = locale.render(
            MessageKey.FARM_PLANTING_BOSSBAR,
            values = mapOf(
                "order" to Component.text("Заказ"),
                "crop" to Component.text("Пшеница"),
                "done" to Component.text("37"),
                "total" to Component.text("100"),
            ),
        )

        PlainTextComponentSerializer.plainText().serialize(rendered) shouldBe
            "Посев Пшеница 37/100 • ПКМ семенами"
    }

    test("active farm bossbars omit the order name reserved for the scoreboard") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }
        val values = mapOf(
            "order" to Component.text("Заказ"),
            "crop" to Component.text("Пшеница"),
            "requirements" to Component.text("Пшеница 0/2"),
            "instruction" to Component.text("Откройте вентили"),
            "nests" to Component.text("3"),
            "pests" to Component.text("4"),
            "done" to Component.text("0"),
            "total" to Component.text("4"),
        )

        listOf(
            MessageKey.FARM_PREPARATION_BOSSBAR,
            MessageKey.FARM_PLANTING_BOSSBAR,
            MessageKey.FARM_CARE_BOSSBAR,
            MessageKey.FARM_BOSSBAR,
            MessageKey.FARM_INCIDENT_BOSSBAR,
            MessageKey.FARM_DROUGHT_BOSSBAR,
            MessageKey.FARM_DELIVERY_BOSSBAR,
            MessageKey.FARM_DELIVERY_CARRYING_BOSSBAR,
        ).forEach { key ->
            PlainTextComponentSerializer.plainText().serialize(locale.render(key, values = values)) shouldNotContain
                "Заказ"
        }
    }

    test("cooldown surfaces explain the automatic next order without a chat prefix") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }
        val values = mapOf("seconds" to Component.text("42"))

        val actionbar = PlainTextComponentSerializer.plainText().serialize(locale.render(MessageKey.COOLDOWN, values = values))
        val bossbar = PlainTextComponentSerializer.plainText().serialize(locale.renderPath("farm.cooldown-bossbar", values = values))

        actionbar shouldBe "Следующая работа через 42 сек."
        bossbar shouldBe "Новый заказ через 42 сек."
        actionbar shouldNotContain "Смена"
        actionbar shouldNotContain "•"
    }

    test("farm screen palette uses balanced bright colors instead of the retired muted pair") {
        listOf("ru", "en").forEach { language ->
            val raw = Files.readString(resourceTree().resolve("lang/$language.yml"))
            val farm = raw.substringAfter("\nfarm:\n").substringBefore("\nlumber:\n")

            farm shouldNotContain "#a8e6a3"
            farm shouldNotContain "#d6d6d6"
            farm shouldContain "#55d98b"
            farm shouldContain "#f2fff7"
            farm shouldContain "#c778ff"
        }
    }

    test("titles keep details in non-empty subtitles without bullet separators") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }
        val values = mapOf(
            "action" to Component.text("Действие"),
            "crop" to Component.text("Пшеница"),
            "total" to Component.text("100"),
            "seconds" to Component.text("45"),
            "players" to Component.text("1"),
            "experience" to Component.text("75"),
            "next" to Component.text("Морковь"),
            "amount" to Component.text("80"),
        )
        val titlePairs = listOf(
            MessageKey.FARM_ENTRY_TITLE to MessageKey.FARM_ENTRY_SUBTITLE,
            MessageKey.FARM_PLANTING_STARTED to MessageKey.FARM_PLANTING_STARTED_SUBTITLE,
            MessageKey.FARM_PREPARATION_COMPLETED to MessageKey.FARM_PREPARATION_COMPLETED_SUBTITLE,
            MessageKey.FARM_CARE_RESOLVED to MessageKey.FARM_CARE_RESOLVED_SUBTITLE,
            MessageKey.FARM_CARE_SCARECROW_PICKED_UP to MessageKey.FARM_CARE_SCARECROW_PICKED_UP_SUBTITLE,
            MessageKey.FARM_INCIDENT_STARTED to MessageKey.FARM_INCIDENT_STARTED_SUBTITLE,
            MessageKey.FARM_INCIDENT_RESOLVED to MessageKey.FARM_INCIDENT_RESOLVED_SUBTITLE,
            MessageKey.FARM_DROUGHT_STARTED to MessageKey.FARM_DROUGHT_STARTED_SUBTITLE,
            MessageKey.FARM_DELIVERY_STARTED to MessageKey.FARM_DELIVERY_STARTED_SUBTITLE,
            MessageKey.FARM_DELIVERY_PICKED_UP to MessageKey.FARM_DELIVERY_PICKED_UP_SUBTITLE,
            MessageKey.FARM_CROP_COMPLETED to MessageKey.FARM_CROP_COMPLETED_SUBTITLE,
            MessageKey.FARM_COMPLETED to MessageKey.FARM_COMPLETED_SUBTITLE,
            MessageKey.LUMBER_PROCESSING to MessageKey.LUMBER_PROCESSING_SUBTITLE,
            MessageKey.LUMBER_COMPLETED to MessageKey.LUMBER_COMPLETED_SUBTITLE,
            MessageKey.MINE_HAZARD_STARTED to MessageKey.MINE_HAZARD_STARTED_SUBTITLE,
            MessageKey.MINE_HAZARD_RESOLVED to MessageKey.MINE_HAZARD_RESOLVED_SUBTITLE,
            MessageKey.MINE_EXTRACTION_STARTED to MessageKey.MINE_EXTRACTION_STARTED_SUBTITLE,
            MessageKey.MINE_COMPLETED to MessageKey.MINE_COMPLETED_SUBTITLE,
        )

        titlePairs.forEach { (titleKey, subtitleKey) ->
            val title = PlainTextComponentSerializer.plainText().serialize(locale.render(titleKey, values = values))
            val subtitle = PlainTextComponentSerializer.plainText().serialize(locale.render(subtitleKey, values = values))
            title shouldNotContain "•"
            title.isNotBlank() shouldBe true
            subtitle.isNotBlank() shouldBe true
        }

        listOf(
            "farm.boar-breakout.started" to "farm.boar-breakout.started-subtitle",
            "farm.rival-raid.started" to "farm.rival-raid.started-subtitle",
        ).forEach { (titlePath, subtitlePath) ->
            val title = PlainTextComponentSerializer.plainText().serialize(locale.renderPath(titlePath, values = values))
            val subtitle = PlainTextComponentSerializer.plainText().serialize(locale.renderPath(subtitlePath, values = values))
            title shouldNotContain "\n"
            title shouldNotContain "\r"
            title shouldNotContain "␊"
            subtitle.isNotBlank() shouldBe true
        }
    }

    test("isolated lab profile is bounded and locale-complete") {
        val repositoryRoot = opsRoot()
        val root = resourceTree(repositoryRoot.resolve("scripts/lab/plugin-configs/ArcFarms/config.yml"))
        val settings = ArcFarmsConfig.inspect(root)

        settings.serverId shouldBe "lab"
        settings.network.allowedOrigins shouldBe setOf("lab", "lab2")
        settings.network.playerAnnouncementsEnabled shouldBe false
        settings.debug.enabled shouldBe true
        settings.destinations.getValue("farm").server shouldBe "lab"
        settings.destinations.getValue("farm").x shouldBe -5.5
        settings.requiresWorldGuard shouldBe false
        settings.farms.single().orders.map { it.id }.toSet() shouldBe setOf("lab_order", "lab_berry_order")
        settings.farms.single().rareOrderChancePercent shouldBe 0
        settings.farms.single().orders.forEach { order ->
            order.customerType shouldBe FarmCustomerType.MARKET_TRADER
        }
        settings.farms.single().orders.single { it.id == "lab_berry_order" }.required shouldBe
            mapOf("SWEET_BERRY_BUSH" to 2)
        settings.farms.single().pestEntity shouldBe "SILVERFISH"
        settings.farms.single().preparationPatchSize shouldBe 12
        settings.farms.single().preparationPatchMaxSize shouldBe 160
        settings.farms.single().seederPatchSize shouldBe 24
        settings.farms.single().seederPatchMaxSize shouldBe 48
        settings.farms.single().seederWorkingRadius shouldBe 4.0
        settings.farms.single().seederPigCatchupDistance shouldBe 12.0
        settings.farms.single().preparationSearchRadius shouldBe 4
        settings.farms.single().careTargetsPerPlayer shouldBe 15
        settings.farms.single().careTargetsMax shouldBe 45
        settings.farms.single().animalRescueTargetCount shouldBe 4
        settings.farms.single().animalRescueMinSpacing shouldBe 2.0
        settings.farms.single().incidentCountMin shouldBe 2
        settings.farms.single().incidentCountMax shouldBe 3
        settings.farms.single().specialIncidents.nightPatrolMinCount shouldBe 2
        settings.farms.single().specialIncidents.nightPatrolMaxCount shouldBe 4
        settings.farms.single().specialIncidents.nightPatrolCount(54) shouldBe 4
        settings.missingBedHighlightThreshold shouldBe 4
        settings.farms.single().delivery.x shouldBe -3.5
        settings.farms.single().delivery.spawnRadius shouldBe 4
        settings.farms.single().delivery.minCrateSpacing shouldBe 2.0
        settings.farms.single().rewards.experience.amount shouldBe 10
        settings.farms.single().rewards.money.amountCents shouldBe 1_000
        settings.farms.single().rewards.randomBundles.entries.single().id shouldBe "lab_snack"
        settings.menuBackground.enabled shouldBe true
        settings.menuBackground.material shouldBe "GRAY_STAINED_GLASS_PANE"
        settings.menuBackground.customModelData shouldBe 0
        settings.menuBack.material shouldBe "ARROW"
        settings.menuBack.customModelData shouldBe 0
        settings.enterprises.getValue(ActivityKind.FARM).apply {
            mode shouldBe WorksiteEnterpriseMode.SHADOW
            companyId shouldBe "lab_farm_company"
            worksiteId shouldBe "lab_farm"
            grossTariffCents("lab_order") shouldBe 1_000_000L
            grossTariffCents("lab_berry_order") shouldBe 1_200_000L
        }
        settings.lumbermills.single().fellingQuota shouldBe 2
        settings.mines.single().cartQuota shouldBe 4
        settings.farms.single().reference.bounds!!.volume shouldBe 34_668L
        ArcFarmsLocale.validateFiles(root, settings)
    }

    test("isolated lab profile is stable under merge-forward") {
        val root = resourceTree(opsRoot().resolve("scripts/lab/plugin-configs/ArcFarms/config.yml"))
        val config = root.resolve("config.yml")
        val reviewed = Files.readString(config)

        ArcFarmsConfig.synchronize(root)

        Files.readString(config) shouldBe reviewed
    }

    test("production farm sized bounds remain valid while excessive cuboids fail closed") {
        CuboidBounds(109, -64, 363, 298, 139, 575).volume shouldBe 8_255_880L
        shouldThrow<IllegalArgumentException> { CuboidBounds(0, 0, 0, 399, 399, 399) }
    }

    test("farm scoreboard renders a compact localized objective with every supported crop") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }
        val rows = FarmScoreboardRenderer(locale).rows(
            FarmScoreboardView(
                orderId = "miners_rations",
                phase = FarmPhase.HARVESTING,
                done = 1_250,
                total = 3_200,
                required = linkedMapOf(
                    "WHEAT" to 1_600,
                    "CARROTS" to 800,
                    "POTATOES" to 800,
                    "BEETROOTS" to 800,
                    "PUMPKIN" to 200,
                ),
                cropProgress = mapOf("WHEAT" to 1_000, "CARROTS" to 250),
            ),
            null,
        )
        val plain = rows.map(PlainTextComponentSerializer.plainText()::serialize)

        rows.size shouldBe 14
        plain[0] shouldBe "Заказ"
        plain[1].startsWith("| ") shouldBe true
        plain[2] shouldBe ""
        plain[3] shouldBe "Задача"
        plain[4] shouldBe "| Сбор урожая"
        plain[5] shouldBe "| 1250 / 3200"
        plain[6] shouldBe "| Собирайте культуры из списка"
        plain[7] shouldBe ""
        plain[8] shouldBe "Урожай"
        plain.drop(9) shouldBe listOf(
            "| Пшеница 1000/1600",
            "| Морковь 250/800",
            "| Картофель 0/800",
            "| Свёкла 0/800",
            "| Тыква 0/200",
        )
        plain.none { "телег" in it.lowercase() || "cart" in it.lowercase() } shouldBe true
    }

    test("farm scoreboard gives a concrete next action for every farm flow") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val renderer = FarmScoreboardRenderer(ArcFarmsLocale(root) { settings })
        val base = FarmScoreboardView(
            orderId = "miners_rations",
            phase = FarmPhase.PREPARATION,
            done = 0,
            total = 100,
            required = mapOf("WHEAT" to 100),
            cropProgress = emptyMap(),
        )
        val scenarios = listOf(
            base.copy(phase = FarmPhase.IDLE) to "Заказ появится автоматически",
            base to "Найдите поле под столбом",
            base.copy(phase = FarmPhase.PLANTING) to "Найдите поле под столбом",
            base.copy(phase = FarmPhase.CARE, careType = null) to "Следуйте к ближайшей метке",
            base.copy(phase = FarmPhase.HARVESTING) to "Собирайте культуры из списка",
            base.copy(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.PESTS) to
                "У красных меток ломайте гнёзда",
            base.copy(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.DROUGHT) to
                "У меток поливайте сухую землю",
            base.copy(
                phase = FarmPhase.INCIDENT,
                incidentType = FarmIncidentType.MARKET,
                incidentCrop = "WHEAT",
            ) to "Найдите светящегося покупателя",
            base.copy(
                phase = FarmPhase.INCIDENT,
                incidentType = FarmIncidentType.MARKET,
                incidentCrop = "WHEAT",
                marketAccepted = true,
            ) to "Собирайте зрелую культуру: Пшеница",
            base.copy(phase = FarmPhase.DELIVERY) to "Берите ящики у телеги",
            base.copy(phase = FarmPhase.DELIVERY, carrying = true) to "Несите ящик к фиолетовой метке",
            base.copy(phase = FarmPhase.COOLDOWN) to "Новый заказ появится позже",
            base.copy(
                phase = FarmPhase.CARE,
                careType = FarmCareType.SEEDER,
                seederStage = FarmSeederStage.TILLING,
            ) to "Ведите свиней над грядками",
            base.copy(
                phase = FarmPhase.CARE,
                careType = FarmCareType.SEEDER,
                seederStage = FarmSeederStage.PLANTING,
            ) to "Ведите свиней над грядками",
        ) + mapOf(
            FarmCareType.WEEDS to "Ищите подсвеченные корни",
            FarmCareType.IRRIGATION to "Открывайте любые подсвеченные вентили",
            FarmCareType.POLLINATION to "Пыльцу из улья несите к цветам",
            FarmCareType.STORM_COVERS to "Закрепите укрытие во всех метках",
            FarmCareType.SCARECROWS to "Берите пугала в хлеву и несите к меткам",
            FarmCareType.ANIMAL_RESCUE to "Зацепляйте животных в канаве служебной удочкой",
            FarmCareType.DISEASE to "Срезайте очаги мотыгой вовремя",
            FarmCareType.MOLES to "Бейте свежие холмики мотыгой",
            FarmCareType.APPLE_HARVEST to "Ищите светящиеся яблоки под кронами",
        ).map { (type, hint) -> base.copy(phase = FarmPhase.CARE, careType = type) to hint }

        scenarios.forEach { (view, expectedHint) ->
            val rows = renderer.rows(view, null)
            (rows.size in 10..11) shouldBe true
            PlainTextComponentSerializer.plainText().serialize(rows[6]) shouldBe "| $expectedHint"
        }
    }

    test("production locale renders every incident scoreboard instead of collapsing to section headers") {
        val root = opsRoot().resolve("classic/plugins/ArcFarms")
        val settings = ArcFarmsConfig.inspect(root)
        val renderer = FarmScoreboardRenderer(ArcFarmsLocale(root) { settings })
        val base = FarmScoreboardView(
            orderId = "miners_rations",
            phase = FarmPhase.INCIDENT,
            done = 0,
            total = 32,
            required = linkedMapOf("WHEAT" to 640),
            cropProgress = emptyMap(),
        )

        FarmIncidentType.entries.forEach { incident ->
            val plain = renderer.rows(base.copy(incidentType = incident), null)
                .map(PlainTextComponentSerializer.plainText()::serialize)
            withClue("incident=$incident rows=$plain") {
                plain[4].removePrefix("| ").isNotBlank() shouldBe true
                plain[6].removePrefix("| ").isNotBlank() shouldBe true
            }
        }
    }

    test("long field and canal cleanup instructions use separate compact scoreboard rows") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val renderer = FarmScoreboardRenderer(ArcFarmsLocale(root) { settings })
        val base = FarmScoreboardView(
            orderId = "miners_rations",
            phase = FarmPhase.PREPARATION,
            done = 0,
            total = 90,
            required = linkedMapOf("WHEAT" to 640),
            cropProgress = emptyMap(),
        )
        val preparation = renderer.rows(base, null).map(PlainTextComponentSerializer.plainText()::serialize)
        preparation[6] shouldBe "| Найдите поле под столбом"
        preparation[7] shouldBe "| Вспашите большую часть поля мотыгой"

        val channels = renderer.rows(
            base.copy(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.CHANNELS, done = 1, total = 4),
            null,
        ).map(PlainTextComponentSerializer.plainText()::serialize)
        channels[6] shouldBe "| Копайте русло по меткам от точки полива"
        channels[7] shouldBe "| Служебной лопатой разбейте каждый отмеченный блок земли"
        (channels.size <= FarmScoreboardRenderer.MAX_ROWS) shouldBe true
    }

    test("night shift scoreboard keeps patrol guidance on its own row") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val rows = FarmScoreboardRenderer(ArcFarmsLocale(root) { settings }).rows(
            FarmScoreboardView(
                orderId = "miners_rations",
                phase = FarmPhase.INCIDENT,
                done = 4,
                total = 24,
                required = linkedMapOf(
                    "WHEAT" to 640,
                    "CARROTS" to 320,
                    "POTATOES" to 320,
                    "BEETROOTS" to 320,
                    "PUMPKIN" to 48,
                ),
                cropProgress = emptyMap(),
                incidentType = FarmIncidentType.NIGHT_SHIFT,
            ),
            null,
        ).map(PlainTextComponentSerializer.plainText()::serialize)

        rows.size shouldBe 15
        rows[6] shouldBe "| Ищите подсвеченные культуры"
        rows[7] shouldBe "| Обходите дозор с факелами"
        rows[8] shouldBe ""
    }

    test("money reward uses the dedicated coin glyph without a redundant noun") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }
        val rendered = locale.render(
            MessageKey.FARM_REWARD_MONEY,
            values = mapOf("amount" to Component.text("500")),
        )

        PlainTextComponentSerializer.plainText().serialize(rendered) shouldBe "500 💰"
    }
}) {
    companion object {
        private fun opsRoot(): Path = System.getProperty("ruscrafting.opsRoot")?.let(Path::of)
            ?: throw TestAbortedException("RusCrafting ops checkout is not configured")

        private fun resourceTree(configSource: Path? = null): Path {
            val root = Files.createTempDirectory("arcfarms-config-test")
            Files.createDirectories(root.resolve("lang"))
            listOf("lang/ru.yml", "lang/en.yml").forEach { name ->
                val stream = requireNotNull(ArcFarmsConfigTest::class.java.classLoader.getResourceAsStream(name)) { "Missing $name" }
                stream.use { Files.copy(it, root.resolve(name)) }
            }
            if (configSource == null) {
                val stream = requireNotNull(ArcFarmsConfigTest::class.java.classLoader.getResourceAsStream("config.yml"))
                stream.use { Files.copy(it, root.resolve("config.yml")) }
                Files.createDirectories(root.resolve("modules"))
                val redis = requireNotNull(ArcFarmsConfigTest::class.java.classLoader.getResourceAsStream("modules/redis.yml"))
                redis.use { Files.copy(it, root.resolve("modules/redis.yml")) }
            } else {
                Files.copy(configSource, root.resolve("config.yml"))
            }
            return root
        }
    }
}
