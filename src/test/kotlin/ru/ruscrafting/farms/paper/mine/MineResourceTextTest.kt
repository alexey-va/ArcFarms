package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.paper.mine.presentation.MineResourceText
import java.nio.file.Files

class MineResourceTextTest : FunSpec({
    test("resource completion text uses the active Russian resource catalog") {
        val root = Files.createTempDirectory("arcfarms-mine-resource-locale")
        listOf("config.yml", "lang/ru.yml", "lang/en.yml").forEach { name ->
            requireNotNull(MineResourceTextTest::class.java.classLoader.getResourceAsStream(name)).use { input ->
                val target = root.resolve(name)
                Files.createDirectories(target.parent)
                Files.copy(input, target)
            }
        }
        val configFile = root.resolve("config.yml").toFile()
        YamlConfiguration.loadConfiguration(configFile).also { config ->
            config.set("locale.default", "ru")
            config.set("locale.use-client-locale", false)
            config.save(configFile)
        }
        val settings = ArcFarmsConfig.inspect(root)
        ArcFarmsLocale.validateFiles(root, settings)
        val locale = ArcFarmsLocale(root) { settings }
        val player = mockk<Player>(relaxed = true)
        val resource = MineResourceText.name(locale, "IRON", player)
        val plain = PlainTextComponentSerializer.plainText()

        plain.serialize(resource) shouldBe "Железо"
        plain.serialize(locale.render(MessageKey.MINE_RESOURCE_COMPLETED, player, mapOf("resource" to resource))) shouldBe
            "Железо — собрано"
        plain.serialize(locale.render(MessageKey.MINE_RESOURCE_COMPLETED_SUBTITLE, player)) shouldBe
            "Отправляйтесь на другой ярус за оставшимися ресурсами"
    }
})
