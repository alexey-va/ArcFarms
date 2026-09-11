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
})

private fun writeConfig(additionalX: Double, additionalZ: Double = 10.0): Path {
    val root = createTempDirectory("mine-lift-settings")
    root.resolve("modules").createDirectories()
    root.resolve("modules/mine-lift.yml").writeText(
        """
        enabled: true
        world: mine
        speed: 6.0
        cabin: {x: 0.0, z: 0.0, width: 2.8, depth: 2.8}
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
