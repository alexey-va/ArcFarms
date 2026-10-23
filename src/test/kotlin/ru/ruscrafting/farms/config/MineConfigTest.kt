package ru.ruscrafting.farms.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.FarmRewardPlanner
import ru.ruscrafting.farms.domain.FarmRewardRecipient
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class MineConfigTest : FunSpec({
    test("real Paper mine fixture passes the native configuration contract") {
        val project = Path.of(requireNotNull(System.getProperty("arcfarms.projectDir")))
        val root = Files.createTempDirectory("arcfarms-mine-e2e-config")
        try {
            val source = Files.readString(project.resolve("src/main/resources/config.yml"))
            val fixture = Files.readString(project.resolve("src/test/e2e/fixtures/mine-zone.yml"))
            Files.writeString(root.resolve("config.yml"), source.replace(
                Regex("(?ms)^mine-zones:.*?(?=^[a-z][a-z-]*:|\\z)"), fixture,
            ))
            val mines = ArcFarmsConfig.inspect(root).mines
            mines.map { it.id }.toSet() shouldBe setOf("old_shafts", "lab_mine")
            val mine = mines.single { it.id == "old_shafts" }
            mine.id shouldBe "old_shafts"
            mine.orders.single().incidentTypes.size shouldBe 3
            mine.rewards.experience.amount shouldBe 220
            val basic = mines.single { it.id == "lab_mine" }
            basic.miningOnly shouldBe true
            basic.orders.single().miningRequired shouldBe 100
            basic.orders.single().miningMaterials shouldBe setOf("COAL_ORE")
            basic.orders.single().incidentTypes shouldBe listOf(MineIncidentType.CAVE_IN)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("extraction rail materials default empty and parse a validated material list") {
        val project = Path.of(requireNotNull(System.getProperty("arcfarms.projectDir")))
        val root = Files.createTempDirectory("arcfarms-mine-rail-config")
        try {
            val source = Files.readString(project.resolve("src/main/resources/config.yml"))
            val marker = "    extraction:\n      checkpoint-radius: 1.6"
            val markerStart = source.indexOf(marker).also { require(it >= 0) }
            val replacement = "    extraction:\n      rail-materials: [POLISHED_ANDESITE]\n      checkpoint-radius: 1.6"
            val configured = source.substring(0, markerStart) + replacement +
                source.substring(markerStart + marker.length)
            Files.writeString(root.resolve("config.yml"), configured)

            val settings = ArcFarmsConfig.inspect(root).mines
            settings.first { it.id == "old_shafts" }.extractionRailMaterials shouldBe setOf("POLISHED_ANDESITE")
            settings.filterNot { it.id == "old_shafts" }.all { it.extractionRailMaterials.isEmpty() } shouldBe true

            Files.writeString(root.resolve("config.yml"), configured.replace("POLISHED_ANDESITE", "bad-material!"))
            shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(root) }
                .message shouldContain "Invalid material name"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("resource mining requirements parse and define the order total") {
        val project = Path.of(requireNotNull(System.getProperty("arcfarms.projectDir")))
        val root = Files.createTempDirectory("arcfarms-mine-mixed-order")
        try {
            val source = Files.readString(project.resolve("src/main/resources/config.yml"))
            val configured = source.replaceFirst(
                "          COAL: 32\n          IRON: 24\n",
                "          COAL: 7\n          IRON: 5\n",
            )
            Files.writeString(root.resolve("config.yml"), configured)

            val order = ArcFarmsConfig.inspect(root).mines.first { it.id == "old_shafts" }.orders.first()
            order.requestedResources shouldBe linkedSetOf("COAL", "IRON")
            order.normalizedRequirements shouldBe linkedMapOf("COAL" to 7, "IRON" to 5)
            order.totalMiningRequired shouldBe 12
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("short mine orders preserve resource variety and plan one scaled resource bundle") {
        val project = Path.of(requireNotNull(System.getProperty("arcfarms.projectDir")))
        val profiles = listOfNotNull(
            project.resolve("src/main/resources"),
            System.getProperty("ruscrafting.opsRoot")?.let { Path.of(it).resolve("classic/plugins/ArcFarms") },
        )
        val expectedTotals = mapOf("coal_order" to 56, "foundry_order" to 64, "deep_alloy" to 50, "crystal_order" to 48)
        val expectedMoney = mapOf("coal_order" to 27_600L, "foundry_order" to 28_400L, "deep_alloy" to 27_000L, "crystal_order" to 26_800L)
        val expectedExperience = mapOf("coal_order" to 414, "foundry_order" to 426, "deep_alloy" to 405, "crystal_order" to 402)
        profiles.forEach { root ->
            val mine = ArcFarmsConfig.inspect(root).mines.single { it.id == "old_shafts" }
            mine.miningOnly shouldBe true
            mine.guidanceRadius shouldBe 128.0
            mine.incidentCountMin shouldBe 1
            mine.incidentCountMax shouldBe 1
            mine.orders.associate { it.id to it.totalMiningRequired } shouldBe expectedTotals
            mine.rewards.randomBundles.rolls shouldBe 1
            mine.rewards.randomBundles.chancePercent shouldBe 100
            mine.rewards.randomBundles.entries.associate { it.id to it.weight } shouldBe
                mapOf("miner_cache" to 50, "redstone_cache" to 30, "gem_cache" to 15, "rich_cache" to 5)
            mine.rewards.items shouldBe emptyList()
            mine.rewards.commands shouldBe emptyList()
            mine.orders.forEach { order ->
                order.miningRequired shouldBe order.normalizedRequirements.values.sum()
                order.incidentTypes.size shouldBe 14
                order.incidentTypes.containsAll(listOf(
                    MineIncidentType.TRACK_DAMAGE, MineIncidentType.LAST_DESCENT,
                    MineIncidentType.DRILLING_ARK, MineIncidentType.DEAD_FACTORY,
                    MineIncidentType.CRYSTAL_RESONANCE, MineIncidentType.FREIGHT_WINCH, MineIncidentType.DRAINAGE_PUMP,
                )) shouldBe true
                val reward = FarmRewardPlanner.plan(
                    mine.rewards, "mine_${mine.id}", 1,
                    FarmRewardRecipient(UUID(0, 1), "Miner", contribution = 1, rank = 1),
                    rewardMultiplierPercent = 110 + order.totalMiningRequired / 2,
                )
                reward.moneyCents shouldBe expectedMoney.getValue(order.id)
                reward.experience shouldBe expectedExperience.getValue(order.id)
                reward.bundleIds.size shouldBe 1
                reward.items.size shouldBe 2
                reward.fixedItemUnits shouldBe 0
            }
        }
    }

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

    test("mine order uses the sum of material requirements as its total quota") {
        val mixed = order.copy(
            miningMaterials = linkedSetOf("COAL_ORE", "IRON_ORE"),
            miningRequirements = linkedMapOf("COAL_ORE" to 7, "IRON_ORE" to 5),
        )
        mixed.totalMiningRequired shouldBe 12
    }

    test("legacy exact-material aliases normalize ordinary and deepslate ores into one resource") {
        val legacy = order.copy(
            miningMaterials = linkedSetOf("COAL_ORE", "DEEPSLATE_COAL_ORE"),
            miningRequirements = linkedMapOf("COAL_ORE" to 7, "DEEPSLATE_COAL_ORE" to 5),
        )

        legacy.requestedResources shouldBe setOf("COAL")
        legacy.normalizedRequirements shouldBe mapOf("COAL" to 12)
        legacy.totalMiningRequired shouldBe 12
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
