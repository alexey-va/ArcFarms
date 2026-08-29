package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path

class ArcFarmsPluginMockBukkitIntegrationTest : FunSpec({
    test("complete plugin boot registers commands, remains entity-stable, and shuts down cleanly") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("sp11")
            paper.server.addSimpleWorld("world")
            val plugin = paper.server.pluginManager.loadPlugin(ArcFarmsPlugin::class.java) as ArcFarmsPlugin
            preparePluginData(plugin.dataFolder.toPath())
            paper.server.pluginManager.enablePlugin(plugin)
            plugin.isEnabled shouldBe true
            paper.server.getPluginCommand("arcfarms")?.plugin shouldBe plugin

            val operator = paper.addPlayer("FarmOperator")
            operator.isOp = true
            operator.performCommand("arcfarms status") shouldBe true
            PlainTextComponentSerializer.plainText().serialize(requireNotNull(operator.nextComponentMessage())) shouldContain
                "Current work shifts"

            val inside = world.getBlockAt(200, 65, 450)
            inside.type = Material.SHULKER_BOX
            val place = BlockPlaceEvent(
                inside,
                inside.state,
                world.getBlockAt(200, 64, 450),
                ItemStack(Material.SHULKER_BOX),
                operator,
                true,
                EquipmentSlot.HAND,
            )
            paper.server.pluginManager.callEvent(place)
            place.isCancelled shouldBe true

            paper.performTicks(25)
            val settledEntityCount = world.entities.size
            paper.performTicks(240)
            world.entities.size shouldBe settledEntityCount

            paper.server.pluginManager.disablePlugin(plugin)
            plugin.isEnabled shouldBe false
            world.entities.count { it !is Player } shouldBe 0
        } finally {
            paper.close()
        }
    }
})

private fun preparePluginData(dataRoot: Path) {
    Files.createDirectories(dataRoot.resolve("lang"))
    listOf("lang/ru.yml", "lang/en.yml").forEach { name ->
        val input = requireNotNull(ArcFarmsPluginMockBukkitIntegrationTest::class.java.classLoader.getResourceAsStream(name))
        input.use { Files.copy(it, dataRoot.resolve(name)) }
    }

    val input = requireNotNull(ArcFarmsPluginMockBukkitIntegrationTest::class.java.classLoader.getResourceAsStream("config.yml"))
    val config = input.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
    config.set("network.enabled", false)
    config.set("ui.bossbars", false)
    config.set("ui.particles", false)
    config.set("ui.sounds", false)
    config.set("ui.farm-scoreboard.enabled", false)
    config.set("farm-zones.communal_farm.region", null)
    config.set("farm-zones.communal_farm.bounds.min", listOf(185, 35, 420))
    config.set("farm-zones.communal_farm.bounds.max", listOf(230, 80, 490))
    config.set("lumber-zones.communal_lumbermill.enabled", false)
    config.getConfigurationSection("mine-zones")?.getKeys(false).orEmpty().forEach { id ->
        config.set("mine-zones.$id.enabled", false)
    }
    config.save(dataRoot.resolve("config.yml").toFile())
}
