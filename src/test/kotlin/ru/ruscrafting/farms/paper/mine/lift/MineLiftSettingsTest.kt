package ru.ruscrafting.farms.paper.mine.lift

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class MineLiftSettingsTest : FreeSpec({
    "loads the legacy main profile and an enabled additional profile" {
        val root = writeConfig(additionalX = 10.0)
        try {
            MineLiftSettings.loadAll(root).map { it.id } shouldBe listOf("main", "service")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "accepts a wide freight cabin" {
        val root = writeConfig(additionalX = 10.0, mainWidth = 5.8)
        try {
            val settings = MineLiftSettings.loadAll(root).first()
            settings.width shouldBe 5.8
            settings.cabinHitboxBottomOffset() shouldBe -0.24
            settings.cabinHitboxWidth() shouldBe 6.2
            settings.cabinHitboxHeight() shouldBe 3.23
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "reserves status as a command keyword" {
        val failures = mutableListOf<String>()
        val root = writeConfig(additionalX = 10.0)
        try {
            root.resolve("modules/mine-lift.yml").toFile().appendText("\n  status:\n    enabled: false\n")
            MineLiftSettings.loadAll(root) { id, _ -> failures += id }.map { it.id } shouldBe listOf("main", "service")
            failures shouldContain "status"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "rejects an additional profile that overlaps an accepted lift" {
        val failures = mutableListOf<String>()
        val root = writeConfig(additionalX = 0.0, additionalZ = 0.0)
        try {
            MineLiftSettings.loadAll(root) { id, _ -> failures += id }.map { it.id } shouldBe listOf("main")
            failures shouldContain "service"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "derives each cabin opening from the corresponding landing side" {
        val root = writeConfig(additionalX = 10.0)
        try {
            val settings = MineLiftSettings.loadAll(root)
            settings.first().openingSide(0) shouldBe MineLiftDoorSide.EAST
            settings.first().openingSide(1) shouldBe MineLiftDoorSide.EAST
            settings[1].openingSide(0) shouldBe MineLiftDoorSide.SOUTH
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "derives all five default floors from their configured landing sides" {
        val settings = MineLiftSettings(
            id = "main", world = "mine", x = 51.5, z = 78.5, width = 2.8, depth = 2.8, speed = 6.0,
            floors = listOf(
                MineLiftFloor("top", 123.0, LiftPoint(47.5, 123.0, 78.5), LiftPoint(47.5, 123.0, 76.5)),
                MineLiftFloor("upper", 88.0, LiftPoint(47.5, 88.0, 78.5), LiftPoint(47.5, 88.0, 76.5)),
                MineLiftFloor("middle", 73.0, LiftPoint(47.5, 73.0, 78.5), LiftPoint(47.5, 73.0, 76.5)),
                MineLiftFloor("lower", 53.0, LiftPoint(47.5, 53.0, 78.5), LiftPoint(47.5, 53.0, 76.5)),
                MineLiftFloor("bottom", 38.0, LiftPoint(51.5, 38.0, 74.5), LiftPoint(49.5, 38.0, 74.5)),
            ),
        )

        settings.floors.indices.map(settings::openingSide) shouldBe listOf(
            MineLiftDoorSide.WEST,
            MineLiftDoorSide.WEST,
            MineLiftDoorSide.WEST,
            MineLiftDoorSide.WEST,
            MineLiftDoorSide.NORTH,
        )
    }
})

private fun writeConfig(additionalX: Double, additionalZ: Double = 10.0, mainWidth: Double = 2.8): Path {
    val root = createTempDirectory("mine-lift-settings")
    root.resolve("modules").createDirectories()
    root.resolve("modules/mine-lift.yml").writeText(
        """
        enabled: true
        world: mine
        speed: 6.0
        cabin: {x: 0.0, z: 0.0, width: $mainWidth, depth: $mainWidth}
        floor-order: [top, bottom]
        floors:
          top: {y: 100.0, exit: {x: 4.0, y: 100.0, z: 0.0}, panel: {x: 4.0, y: 100.0, z: 1.0}}
          bottom: {y: 90.0, exit: {x: 4.0, y: 90.0, z: 0.0}, panel: {x: 4.0, y: 90.0, z: 1.0}}
        additional-lifts:
          service:
            enabled: true
            world: mine
            speed: 6.0
            cabin: {x: $additionalX, z: $additionalZ, width: 2.8, depth: 2.8}
            floor-order: [top, bottom]
            floors:
              top: {y: 100.0, exit: {x: $additionalX, y: 100.0, z: 14.0}, panel: {x: $additionalX, y: 100.0, z: 15.0}}
              bottom: {y: 90.0, exit: {x: $additionalX, y: 90.0, z: 14.0}, panel: {x: $additionalX, y: 90.0, z: 15.0}}
        """.trimIndent(),
    )
    Files.exists(root.resolve("modules/mine-lift.yml")) shouldBe true
    return root
}
