package ru.ruscrafting.farms.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import ru.ruscrafting.farms.domain.MineIncidentType
import java.nio.file.Files
import java.nio.file.Path

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

    test("basic runtime profile parses one invasion and landing guidance") {
        val root = System.getProperty("ruscrafting.opsRoot")?.let(Path::of)
        if (root != null) {
            val mines = ArcFarmsConfig.inspect(root.resolve("classic/plugins/ArcFarms")).mines
            mines.size shouldBe 4
            mines.forEach {
                it.miningOnly shouldBe true
                it.guidanceRadius shouldBe 128.0
                it.incidentCountMin shouldBe 1
                it.incidentCountMax shouldBe 1
                it.orders.forEach { order ->
                    order.incidentTypes shouldBe listOf(MineIncidentType.CREATURE_NEST)
                    order.miningRequired shouldBe 100
                    order.miningMaterials.isNotEmpty() shouldBe true
                }
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
