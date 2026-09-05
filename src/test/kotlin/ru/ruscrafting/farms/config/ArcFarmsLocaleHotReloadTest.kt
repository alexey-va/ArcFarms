package ru.ruscrafting.farms.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.Config
import java.nio.file.Files

class ArcFarmsLocaleHotReloadTest : FunSpec({
    test("bundled enterprise participation locale satisfies startup placeholder contract") {
        val root = Files.createTempDirectory("arcfarms-locale-contract")
        Files.createDirectories(root.resolve("lang"))
        listOf("config.yml", "lang/ru.yml", "lang/en.yml").forEach { resource ->
            requireNotNull(ArcFarmsLocaleHotReloadTest::class.java.classLoader.getResourceAsStream(resource)).use { input ->
                Files.copy(input, root.resolve(resource))
            }
        }
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }
        locale.prepareReload(settings)
    }

    test("prepared locale catalogs publish and roll back atomically") {
        val root = Files.createTempDirectory("arcfarms-locale-reload")
        Files.createDirectories(root.resolve("lang"))
        listOf("config.yml", "lang/ru.yml", "lang/en.yml").forEach { resource ->
            requireNotNull(ArcFarmsLocaleHotReloadTest::class.java.classLoader.getResourceAsStream(resource)).use { input ->
                Files.copy(input, root.resolve(resource))
            }
        }
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }
        val plain = PlainTextComponentSerializer.plainText()
        val before = plain.serialize(locale.render(MessageKey.RELOAD_OK))
        val previous = locale.snapshot()

        Config(root, "lang/ru.yml").also { russian ->
            russian.setString(MessageKey.RELOAD_OK.path, "<green>Новый каталог</green>")
            russian.saveStrict()
        }
        val candidate = locale.prepareReload()

        plain.serialize(locale.render(MessageKey.RELOAD_OK)) shouldBe before
        locale.publish(candidate)
        plain.serialize(locale.render(MessageKey.RELOAD_OK)) shouldBe "Новый каталог"
        locale.publish(previous)
        plain.serialize(locale.render(MessageKey.RELOAD_OK)) shouldBe before
    }
})
