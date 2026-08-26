package ru.ruscrafting.farms.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.assertions.throwables.shouldThrow
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.Config
import ru.arc.redis.RedisModuleConfig
import ru.ruscrafting.farms.domain.FarmIncidentType
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
import kotlin.io.path.writeText

class ArcFarmsConfigTest : FunSpec({
    test("bundled production config owns all three activities without ARC compatibility") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val repositoryRoot = Path.of(System.getProperty("arcfarms.repositoryRoot"))

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
                "SWEET_BERRY_BUSH" to 320,
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
        settings.farms.single().seederPatchSize shouldBe 1_280
        settings.farms.single().seederPatchMaxSize shouldBe 2_048
        settings.farms.single().seederComponentGap shouldBe 16
        settings.farms.single().seederComponentLimit shouldBe 8
        settings.farms.single().seederWorkingRadius shouldBe 8.0
        settings.farms.single().seederBlocksPerUpdate shouldBe 128
        settings.farms.single().seederPigSpeed shouldBe 0.46
        settings.farms.single().seederPigCatchupDistance shouldBe 12.0
        settings.farms.single().preparationSearchRadius shouldBe 64
        settings.farms.single().fixedCropRespawnSeconds shouldBe 20
        settings.farms.single().restoreBlocksPerTick shouldBe 24
        settings.farms.single().blockReindexBlocksPerTick shouldBe 4_096
        settings.farms.single().blockReindexMaxBlocks shouldBe 20_000_000
        settings.farms.single().backupBlocksPerTick shouldBe 2_048
        settings.farms.single().backupMaxBlocks shouldBe 4_000_000
        settings.farms.single().crops shouldBe
            setOf("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "SWEET_BERRY_BUSH", "MELON", "PUMPKIN")
        settings.farms.single().careTypes shouldContainExactly FarmCareType.entries.filterNot { it == FarmCareType.SEEDER }
        settings.farms.single().careTargetCount shouldBe 4
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
        settings.farms.single().displayViewRange shouldBe 2.0f
        settings.farms.single().seederEveryShifts shouldBe 2
        settings.farms.single().diseaseInitialSpots shouldBe 2
        settings.farms.single().diseaseMaxSpots shouldBe 6
        settings.farms.single().diseaseSpreadSeconds shouldBe 12
        settings.farms.single().proceduralCareFixtures shouldBe true
        settings.farms.single().careAnimalEntities shouldContainExactly listOf("CHICKEN", "SHEEP")
        settings.farms.single().music.enabled shouldBe false
        settings.farms.single().contractCartVisual.material shouldBe "MINECART"
        settings.farms.single().contractCartVisual.customModelData shouldBe 0
        settings.farms.single().contractCartVisual.displayTransform shouldBe FarmItemDisplayTransform.GROUND
        settings.farms.single().contractCartVisual.scale shouldBe 1.0f
        settings.farms.single().contractCartVisual.yOffset shouldBe 0.15
        settings.farms.single().contractCartVisual.viewRange shouldBe 2.0f
        settings.farms.single().placementMinObjectiveDistance shouldBe 10
        settings.farms.single().placementMaxPlayerDistance shouldBe 28
        settings.farms.single().placementSearchRadius shouldBe 32
        settings.farms.single().careVisuals.getValue(FarmCareRole.HIVE).material shouldBe "BEE_NEST"
        settings.farms.single().careVisuals.getValue(FarmCareRole.APPLE).material shouldBe "APPLE"
        settings.farms.single().incidentTypes shouldContainExactly listOf(
            FarmIncidentType.GIANT_CROP,
            FarmIncidentType.CHANNELS,
            FarmIncidentType.NIGHT_SHIFT,
            FarmIncidentType.MARKET,
            FarmIncidentType.PESTS,
            FarmIncidentType.DROUGHT,
        )
        settings.farms.single().specialIncidents.channelBlockageCount shouldBe 5
        settings.farms.single().specialIncidents.channelBlockageMaterial shouldBe "MANGROVE_ROOTS"
        settings.farms.single().specialIncidents.channelBlockageDisplayYOffset shouldBe 0.8
        settings.farms.single().specialIncidents.nightCropPlacementCount shouldBe 90
        settings.farms.single().specialIncidents.nightCropTargetCount shouldBe 24
        settings.farms.single().specialIncidents.nightCropMinSpacing shouldBe 6.0
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
        settings.farms.single().supplies.tool.x shouldBe 212.5
        settings.farms.single().supplies.tool.z shouldBe 448.5
        settings.farms.single().supplies.seeds.z shouldBe 453.5
        settings.farms.single().supplies.water.z shouldBe 458.5
        settings.farms.single().rewards.experience.amount shouldBe 75
        settings.farms.single().rewards.experience.chancePercent shouldBe 100
        settings.farms.single().rewards.money.amountCents shouldBe 0
        settings.lumbermills.single().fellingQuota shouldBe 16
        settings.mines.all { it.cartQuota == 16 && it.supportsRequired == 1 } shouldBe true
        ArcFarmsRedisBootstrap.load(root, settings).serverName shouldBe "spawn"
        ArcFarmsLocale.validateFiles(root, settings)
        listOf("lang/ru.yml", "lang/en.yml").forEach { path ->
            Files.readString(repositoryRoot.resolve("classic/plugins/ArcFarms/$path")) shouldBe Files.readString(root.resolve(path))
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
        classicSettings.farms.single().careVisuals.getValue(FarmCareRole.SCARECROW).customModelData shouldBe 11_874
        classicSettings.farms.single().careVisuals.getValue(FarmCareRole.PEN).customModelData shouldBe 11_864
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
        val repositoryRoot = Path.of(System.getProperty("arcfarms.repositoryRoot"))
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
                settings.farmScoreboard.enabled shouldBe false
                settings.farmScoreboard.provider shouldBe FarmScoreboardProvider.TAB
                settings.destinations.values.all { it.server == "spawn" } shouldBe true
            }
            ArcFarmsLocale.validateFiles(root, settings)
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
        val repositoryRoot = Path.of(System.getProperty("arcfarms.repositoryRoot"))
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

    test("farm patch size is bounded before runtime scanning") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        configPath.writeText(
            Files.readString(configPath).replace("preparation-patch-size: 100", "preparation-patch-size: 513"),
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
        val repositoryRoot = Path.of(System.getProperty("arcfarms.repositoryRoot"))
        val root = repositoryRoot.resolve("ArcFarms/src/main/resources")
        val ru = Config(root, "lang/ru.yml")
        val en = Config(root, "lang/en.yml")

        ru.string("farm.care-seeder-following") shouldContain "свиньи"
        ru.string("scoreboard.hint.care.seeder-tilling") shouldNotContain "метк"
        en.string("farm.care-seeder-following") shouldContain "pigs"
        en.string("scoreboard.hint.care.seeder-planting") shouldNotContain "marker"
    }

    test("mechanized patch remains bounded before runtime scanning") {
        val root = resourceTree()
        val configPath = root.resolve("config.yml")
        configPath.writeText(
            Files.readString(configPath).replace("seeder-patch-max-size: 2048", "seeder-patch-max-size: 2049"),
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

    test("locale parity includes dynamic order route and phase paths") {
        val root = resourceTree()
        val settings = ArcFarmsConfig.inspect(root)
        val english = root.resolve("lang/en.yml")
        english.writeText(Files.readString(english).replace("    old_shafts: '<aqua>Old Shafts</aqua>'\n", ""))

        shouldThrow<IllegalArgumentException> { ArcFarmsLocale.validateFiles(root, settings) }
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
    }

    test("isolated lab profile is bounded and locale-complete") {
        val repositoryRoot = Path.of(System.getProperty("arcfarms.repositoryRoot"))
        val root = resourceTree(repositoryRoot.resolve("scripts/lab/plugin-configs/ArcFarms/config.yml"))
        val settings = ArcFarmsConfig.inspect(root)

        settings.serverId shouldBe "lab"
        settings.network.allowedOrigins shouldBe setOf("lab")
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
        settings.farms.single().careTargetCount shouldBe 3
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
        settings.lumbermills.single().fellingQuota shouldBe 2
        settings.mines.single().cartQuota shouldBe 4
        settings.farms.single().reference.bounds!!.volume shouldBe 17_334L
        ArcFarmsLocale.validateFiles(root, settings)
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
            FarmCareType.IRRIGATION to "Открывайте вентили по порядку",
            FarmCareType.POLLINATION to "Пыльцу из улья несите к цветам",
            FarmCareType.STORM_COVERS to "Закрепите отмеченные углы",
            FarmCareType.SCARECROWS to "Дважды почините каждое пугало",
            FarmCareType.ANIMAL_RESCUE to "Ведите животных к зелёной метке",
            FarmCareType.DISEASE to "Обработайте каждый очаг дважды",
            FarmCareType.MOLES to "Бейте свежие холмики мотыгой",
            FarmCareType.APPLE_HARVEST to "Ищите светящиеся яблоки под кронами",
        ).map { (type, hint) -> base.copy(phase = FarmPhase.CARE, careType = type) to hint }

        scenarios.forEach { (view, expectedHint) ->
            val rows = renderer.rows(view, null)
            (rows.size in 10..11) shouldBe true
            PlainTextComponentSerializer.plainText().serialize(rows[6]) shouldBe "| $expectedHint"
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
        channels[6] shouldBe "| Идите вдоль синего потока"
        channels[7] shouldBe "| Нажимайте ПКМ по завалам"
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
