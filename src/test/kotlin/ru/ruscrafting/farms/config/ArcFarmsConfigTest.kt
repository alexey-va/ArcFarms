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
        settings.farms.single().orders.maxOf { order -> order.required.values.sum() } shouldBe 5_200
        settings.farms.single().orders.associate { it.id to it.required } shouldBe mapOf(
            "miners_rations" to mapOf("WHEAT" to 1_600, "CARROTS" to 800, "POTATOES" to 800),
            "bakery_supply" to mapOf("WHEAT" to 2_400, "BEETROOTS" to 800),
            "market_crates" to mapOf("CARROTS" to 800, "POTATOES" to 800, "BEETROOTS" to 800),
            "harvest_festival" to mapOf("WHEAT" to 2_000, "CARROTS" to 1_200, "POTATOES" to 1_200, "BEETROOTS" to 800),
            "deep_mine_relief" to mapOf("WHEAT" to 2_000, "CARROTS" to 1_200, "POTATOES" to 1_600),
            "master_baker_request" to mapOf("WHEAT" to 3_600, "BEETROOTS" to 1_200),
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
        settings.farms.single().droughtPatches shouldBe 3
        settings.farms.single().droughtCoveragePercent shouldBe 35
        settings.farms.single().droughtMinBeds shouldBe 30
        settings.farms.single().droughtMaxBeds shouldBe 40
        settings.farms.single().droughtTargetBeds(100) shouldBe 35
        settings.farms.single().droughtTargetBeds(60) shouldBe 30
        settings.farms.single().droughtTargetBeds(20) shouldBe 20
        settings.farms.single().preparationPatchSize shouldBe 100
        settings.farms.single().preparationPatchMaxSize shouldBe 256
        settings.farms.single().preparationSearchRadius shouldBe 64
        settings.farms.single().careTypes shouldContainExactly FarmCareType.entries.filterNot { it == FarmCareType.SEEDER }
        settings.farms.single().careTargetCount shouldBe 4
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
        settings.farms.single().placementMinObjectiveDistance shouldBe 10
        settings.farms.single().placementMaxPlayerDistance shouldBe 28
        settings.farms.single().placementSearchRadius shouldBe 32
        settings.farms.single().careVisuals.getValue(FarmCareRole.HIVE).material shouldBe "BEE_NEST"
        settings.farms.single().incidentTypes shouldContainExactly listOf(FarmIncidentType.PESTS, FarmIncidentType.DROUGHT)
        settings.farms.single().pestNestCount shouldBe 3
        settings.farms.single().pestNestHealth shouldBe 3
        settings.farms.single().pestSpawnsPerNest shouldBe 3
        settings.farms.single().pestMaxAlive shouldBe 6
        settings.farms.single().pestEatRadius shouldBe 3
        settings.farms.single().delivery.world shouldBe "sp11"
        settings.farms.single().delivery.x shouldBe 201.65
        settings.farms.single().delivery.crates shouldBe 3
        settings.farms.single().delivery.spawnRadius shouldBe 8
        settings.farms.single().delivery.pickup.z shouldBe 463.5
        settings.farms.single().delivery.itemMaterial shouldBe "BARREL"
        settings.farms.single().delivery.itemCustomModelData shouldBe 0
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
        classicSettings.farms.single().careVisuals.getValue(FarmCareRole.VALVE).customModelData shouldBe 11_859
        classicSettings.farms.single().careVisuals.getValue(FarmCareRole.SCARECROW).customModelData shouldBe 12_160
        classicSettings.farms.single().careVisuals.getValue(FarmCareRole.PEN).customModelData shouldBe 11_864
        classicSettings.farms.single().music.enabled shouldBe true
        classicSettings.farms.single().music.sound shouldBe "arc:farm_valley_comes_alive"
        classicSettings.farms.single().music.durationSeconds shouldBe 262
        classicSettings.farms.single().music.volume shouldBe 0.65f
        classicSettings.farms.single().contractCartVisual.material shouldBe "PAPER"
        classicSettings.farms.single().contractCartVisual.customModelData shouldBe 10_747
        classicSettings.farms.single().contractCartVisual.displayTransform shouldBe FarmItemDisplayTransform.GROUND
        classicSettings.farms.single().contractCartVisual.scale shouldBe 4.0f
        classicSettings.farms.single().contractCartVisual.yOffset shouldBe 0.75
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
        Files.readString(repositoryRoot.resolve("classic/plugins/ItemsAdder/storage/items_ids_cache.yml")) shouldContain
            "elitecreatures:medieval_market_decoration_v1_cart_2: 10747"
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
                "delivery: {x: -3.5, y: 100.0, z: 0.5, radius: 1.5, crates: 3, spawn-radius: 4}",
                "delivery: {x: 50.0, y: 100.0, z: 0.5, radius: 1.5, crates: 3, spawn-radius: 4}",
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
            "Заказ • Пшеница 0/2, Морковь 0/2 • 0/4"
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
            "Заказ • Посев Пшеница 37/100 • ПКМ семенами"
    }

    test("every active farm bossbar leads with the current order name") {
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
            PlainTextComponentSerializer.plainText().serialize(locale.render(key, values = values)) shouldStartWith
                "Заказ •"
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
        settings.farms.single().orders.single().id shouldBe "lab_order"
        settings.farms.single().rareOrderChancePercent shouldBe 0
        settings.farms.single().orders.single().customerType shouldBe FarmCustomerType.MARKET_TRADER
        settings.farms.single().pestEntity shouldBe "SILVERFISH"
        settings.farms.single().preparationPatchSize shouldBe 12
        settings.farms.single().preparationPatchMaxSize shouldBe 160
        settings.farms.single().preparationSearchRadius shouldBe 4
        settings.farms.single().careTargetCount shouldBe 3
        settings.missingBedHighlightThreshold shouldBe 4
        settings.farms.single().delivery.x shouldBe -3.5
        settings.farms.single().delivery.spawnRadius shouldBe 4
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
                cartPercent = 39,
            ),
            null,
        )
        val plain = rows.map(PlainTextComponentSerializer.plainText()::serialize)

        rows.size shouldBe 15
        plain[0] shouldBe "Заказ"
        plain[1].startsWith("| ") shouldBe true
        plain[3] shouldBe "Задача"
        plain[4] shouldBe "| Сбор урожая"
        plain[5] shouldBe "| 1250 / 3200"
        plain[6] shouldBe "| Собирайте культуры из списка"
        plain[8] shouldBe "Урожай"
        plain.last() shouldBe "| Телега 39%"
        plain.any { "pumpkin" in it.lowercase() || "тыкв" in it.lowercase() } shouldBe true
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
            cartPercent = 0,
        )
        val scenarios = listOf(
            base.copy(phase = FarmPhase.IDLE) to "Заказ появится автоматически",
            base to "Найдите метку и вспашите землю",
            base.copy(phase = FarmPhase.PLANTING) to "Найдите метку и засейте грядки",
            base.copy(phase = FarmPhase.CARE, careType = null) to "Следуйте к ближайшей метке",
            base.copy(phase = FarmPhase.HARVESTING) to "Собирайте культуры из списка",
            base.copy(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.PESTS) to
                "У красных меток ломайте гнёзда",
            base.copy(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.DROUGHT) to
                "У меток поливайте сухую землю",
            base.copy(phase = FarmPhase.DELIVERY) to "Берите ящики у телеги",
            base.copy(phase = FarmPhase.DELIVERY, carrying = true) to "Несите ящик к фиолетовой метке",
            base.copy(phase = FarmPhase.COOLDOWN) to "Новый заказ появится позже",
        ) + mapOf(
            FarmCareType.SEEDER to "Ведите лошадь по меткам",
            FarmCareType.WEEDS to "Ищите подсвеченные корни",
            FarmCareType.IRRIGATION to "Открывайте вентили по порядку",
            FarmCareType.POLLINATION to "Пыльцу из улья несите к цветам",
            FarmCareType.STORM_COVERS to "Закрепите отмеченные углы",
            FarmCareType.SCARECROWS to "Дважды почините каждое пугало",
            FarmCareType.ANIMAL_RESCUE to "Ведите животных к зелёной метке",
            FarmCareType.DISEASE to "Обработайте каждый очаг дважды",
            FarmCareType.MOLES to "Бейте свежие холмики мотыгой",
        ).map { (type, hint) -> base.copy(phase = FarmPhase.CARE, careType = type) to hint }

        scenarios.forEach { (view, expectedHint) ->
            val rows = renderer.rows(view, null)
            rows.size shouldBe 11
            PlainTextComponentSerializer.plainText().serialize(rows[6]) shouldBe "| $expectedHint"
        }
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
