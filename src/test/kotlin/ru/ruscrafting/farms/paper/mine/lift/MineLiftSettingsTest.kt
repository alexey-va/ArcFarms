package ru.ruscrafting.farms.paper.mine.lift

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class MineLiftSettingsTest : FreeSpec({
    "compact lift excludes its entire shaft including the pit below the last stop" {
        val world=io.mockk.mockk<org.bukkit.World>()
        io.mockk.every { world.name } returns "rc_atelier_compact_mine"
        val settings=MineLiftSettings("main",world.name,21.5,43.5,4.8,6.4,6.0,
            listOf(111.0,97.0,83.0).mapIndexed { i,y -> MineLiftFloor("floor_$i",y,
                LiftPoint(28.5,y,43.5),LiftPoint(24.5,y,39.5)) })
        for(y in listOf(-64.0,0.0,64.0,79.0,83.0,97.0,111.0,140.0,319.0)) {
            settings.excludesEvent(org.bukkit.Location(world,21.5,y,43.5)) shouldBe true
            settings.excludesEvent(org.bukkit.Location(world,24.5,y,46.5)) shouldBe true
            settings.excludesEvent(org.bukkit.Location(world,28.5,y,43.5)) shouldBe false
        }
        val other=io.mockk.mockk<org.bukkit.World>()
        io.mockk.every { other.name } returns "another_mine"
        settings.excludesEvent(org.bukkit.Location(other,21.5,64.0,43.5)) shouldBe false
    }

    "loads the legacy main profile and an enabled additional profile" {
        val root = writeConfig(additionalX = 10.0)
        try {
            MineLiftSettings.loadAll(root).map { it.id } shouldBe listOf("main", "service")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "accepts a rectangular cabin fitted to its shaft" {
        val root = writeConfig(additionalX = 10.0, mainWidth = 4.8, mainDepth = 6.4)
        try {
            val settings = MineLiftSettings.loadAll(root).first()
            settings.width shouldBe 4.8
            settings.depth shouldBe 6.4
            settings.cabinHitboxBottomOffset() shouldBe -0.24
            settings.cabinHitboxWidth() shouldBe (6.8 plusOrMinus 0.0001)
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

    "walk-in entry crosses only the active landing doorway and can be retried after exit" {
        val world = io.mockk.mockk<org.bukkit.World>()
        io.mockk.every { world.name } returns "mine"
        val settings = MineLiftSettings(
            id = "main", world = world.name, x = 0.0, z = 0.0, width = 2.8, depth = 2.8, speed = 6.0,
            floors = listOf(
                MineLiftFloor("top", 100.0, LiftPoint(-8.0, 100.0, 0.0), LiftPoint(-8.0, 100.0, 2.0)),
                MineLiftFloor("bottom", 90.0, LiftPoint(0.0, 90.0, -8.0), LiftPoint(2.0, 90.0, -8.0)),
            ),
        )
        val outsideWest = org.bukkit.Location(world, -1.71, 100.0, 0.0)
        val insideWest = org.bukkit.Location(world, -1.69, 100.0, 0.0)
        val outsideNorth = org.bukkit.Location(world, 0.0, 90.0, -1.71)
        val insideNorth = org.bukkit.Location(world, 0.0, 90.0, -1.69)

        settings.entersCabin(outsideWest, insideWest, 0) shouldBe true
        settings.entersCabin(insideWest, org.bukkit.Location(world, -1.5, 100.0, 0.0), 0) shouldBe false
        settings.entersCabin(insideWest, outsideWest, 0) shouldBe false
        settings.entersCabin(outsideWest, insideWest, 0) shouldBe true
        settings.entersCabin(outsideNorth, insideNorth, 0) shouldBe false
        settings.entersCabin(outsideWest, insideWest, 1) shouldBe false
        settings.entersCabin(
            org.bukkit.Location(world, -1.71, 90.0, 0.0),
            org.bukkit.Location(world, -1.69, 90.0, 0.0),
            0,
        ) shouldBe false
        settings.entersCabin(outsideNorth, insideNorth, 1) shouldBe true
    }

    "look-only movement does not reset a walk-in menu latch, but stepping away does" {
        val world = io.mockk.mockk<org.bukkit.World>()
        io.mockk.every { world.name } returns "mine"
        val settings = MineLiftSettings(
            id = "main", world = world.name, x = 0.0, z = 0.0, width = 2.8, depth = 2.8, speed = 6.0,
            floors = listOf(
                MineLiftFloor("top", 100.0, LiftPoint(-8.0, 100.0, 0.0), LiftPoint(-8.0, 100.0, 2.0)),
                MineLiftFloor("bottom", 90.0, LiftPoint(0.0, 90.0, -8.0), LiftPoint(2.0, 90.0, -8.0)),
            ),
        )
        val atDoor = org.bukkit.Location(world, -1.71, 100.0, 0.0)
        val lookOnly = atDoor.clone().apply { yaw = 90f; pitch = 20f }

        settings.movedAwayFromCabin(atDoor, lookOnly) shouldBe false
        settings.movedAwayFromCabin(atDoor, org.bukkit.Location(world, -1.69, 100.0, 0.0)) shouldBe false
        settings.movedAwayFromCabin(atDoor, org.bukkit.Location(world, -1.91, 100.0, 0.0)) shouldBe true
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

private fun writeConfig(
    additionalX: Double,
    additionalZ: Double = 10.0,
    mainWidth: Double = 2.8,
    mainDepth: Double = mainWidth,
): Path {
    val root = createTempDirectory("mine-lift-settings")
    root.resolve("modules").createDirectories()
    root.resolve("modules/mine-lift.yml").writeText(
        """
        enabled: true
        world: mine
        speed: 6.0
        cabin: {x: 0.0, z: 0.0, width: $mainWidth, depth: $mainDepth}
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
