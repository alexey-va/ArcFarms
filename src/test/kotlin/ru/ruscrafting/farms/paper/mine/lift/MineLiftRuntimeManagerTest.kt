@file:Suppress("DEPRECATION")

package ru.ruscrafting.farms.paper.mine.lift

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.ArcFarmsPlugin
import java.io.InputStreamReader
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import java.nio.file.Files

class MineLiftRuntimeManagerTest : FunSpec({
    test("a corrupt additional journal does not discard the main runtime") {
        val paper = MockBukkitTestRuntime.open()
        try {
            paper.server.addSimpleWorld("sp11")
            paper.server.addSimpleWorld("world")
            paper.server.addSimpleWorld("mine")
            val plugin = paper.server.pluginManager.loadPlugin(ArcFarmsPlugin::class.java) as ArcFarmsPlugin
            val root = plugin.dataFolder.toPath()
            val config = requireNotNull(javaClass.classLoader.getResourceAsStream("config.yml"))
                .use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
            config.set("network.enabled", false)
            config.set("ui.farm-scoreboard.enabled", false)
            config.getConfigurationSection("farm-zones")?.getKeys(false).orEmpty().forEach { id ->
                config.set("farm-zones.$id.enabled", false)
                config.set("farm-zones.$id.region", null)
                config.set("farm-zones.$id.bounds.min", listOf(0, 0, 0))
                config.set("farm-zones.$id.bounds.max", listOf(10, 10, 10))
            }
            config.getConfigurationSection("lumber-zones")?.getKeys(false).orEmpty().forEach { id ->
                config.set("lumber-zones.$id.enabled", false)
                config.set("lumber-zones.$id.region", null)
                config.set("lumber-zones.$id.station-region", null)
                config.set("lumber-zones.$id.bounds.min", listOf(0, 0, 0))
                config.set("lumber-zones.$id.bounds.max", listOf(10, 10, 10))
                config.set("lumber-zones.$id.station-bounds.min", listOf(0, 0, 0))
                config.set("lumber-zones.$id.station-bounds.max", listOf(10, 10, 10))
            }
            config.getConfigurationSection("mine-zones")?.getKeys(false).orEmpty().forEach { id ->
                config.set("mine-zones.$id.enabled", false)
                config.set("mine-zones.$id.region", null)
                config.set("mine-zones.$id.bounds.min", listOf(0, 0, 0))
                config.set("mine-zones.$id.bounds.max", listOf(10, 10, 10))
            }
            config.save(root.resolve("config.yml").toFile())
            root.resolve("modules").createDirectories()
            root.resolve("modules/mine-lift.yml").writeText(managerConfig())
            root.resolve("data").createDirectories()
            val corrupt = root.resolve("data/mine-lift-passengers-west.json")
            corrupt.writeText("corrupt-west-journal")

            paper.server.pluginManager.enablePlugin(plugin)
            plugin.isEnabled shouldBe true
            val operator = paper.addPlayer("LiftOperator")
            operator.isOp = true
            operator.performCommand("minelift status") shouldBe true
            PlainTextComponentSerializer.plainText().serialize(requireNotNull(operator.nextComponentMessage())) shouldContain "id=main"
            Files.readString(corrupt) shouldBe "corrupt-west-journal"
            paper.server.pluginManager.disablePlugin(plugin)
        } finally {
            paper.close()
        }
    }
})

private fun managerConfig() = """
    enabled: true
    world: mine
    speed: 6.0
    cabin: {x: 0.0, z: 0.0, width: 2.8, depth: 2.8}
    floor-order: [top, bottom]
    floors:
      top: {y: 100.0, exit: {x: 4.0, y: 100.0, z: 0.0}, panel: {x: 4.0, y: 100.0, z: 1.0}}
      bottom: {y: 90.0, exit: {x: 4.0, y: 90.0, z: 0.0}, panel: {x: 4.0, y: 90.0, z: 1.0}}
    additional-lifts:
      west:
        enabled: true
        world: mine
        speed: 6.0
        cabin: {x: 10.0, z: 10.0, width: 2.8, depth: 2.8}
        floor-order: [top, bottom]
        floors:
          top: {y: 100.0, exit: {x: 14.0, y: 100.0, z: 10.0}, panel: {x: 14.0, y: 100.0, z: 11.0}}
          bottom: {y: 90.0, exit: {x: 14.0, y: 90.0, z: 10.0}, panel: {x: 14.0, y: 90.0, z: 11.0}}
""".trimIndent()
