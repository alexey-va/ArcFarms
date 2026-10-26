package ru.ruscrafting.farms.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.Config
import java.nio.file.Files

class LumberScreenLocaleTest : FunSpec({
    test("bundled lumber completion uses separate single-line title and subtitle in both languages") {
        SCREEN_TITLE_SUBTITLES[MessageKey.LUMBER_COMPLETE] shouldBe MessageKey.LUMBER_COMPLETE_SUBTITLE
        for (language in listOf("ru", "en")) {
            val root = lumberScreenLocaleRoot()
            Config(root, "config.yml").also { it.setString("locale.default", language); it.saveStrict() }
            val settings = ArcFarmsConfig.inspect(root)
            val locale = ArcFarmsLocale(root) { settings }
            locale.prepareReload(settings)
            for (key in listOf(MessageKey.LUMBER_COMPLETE, MessageKey.LUMBER_COMPLETE_SUBTITLE,
                MessageKey.LUMBER_SAWING_RUNNING_HINT, MessageKey.LUMBER_LOADING_BOARDS_HINT)) {
                val text = PlainTextComponentSerializer.plainText().serialize(locale.render(key))
                text.any { it in "\n\r\u240a\u240d" } shouldBe false
                text.contains("\\n") shouldBe false
                text.contains("\\r") shouldBe false
            }
        }
    }

    test("the canonical catalog validation rejects actual and visible LF in lumber screen fields") {
        val root = lumberScreenLocaleRoot()
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }
        for (key in listOf(MessageKey.LUMBER_COMPLETE, MessageKey.LUMBER_SAWING_RUNNING_HINT)) {
            val catalog = Config(root, "lang/ru.yml")
            val original = catalog.string(key.path)
            for (lineBreak in listOf("<newline>", "\n", "\\n", "\u240a")) {
                catalog.setString(key.path, "First${lineBreak}second")
                catalog.saveStrict()
                shouldThrow<IllegalArgumentException> { locale.prepareReload(settings) }.message.orEmpty() shouldContain
                    "screen title contains a line break"
            }
            catalog.setString(key.path, original)
            catalog.saveStrict()
        }
    }
})

private fun lumberScreenLocaleRoot() = Files.createTempDirectory("lumber-screen-locale").also { root ->
    Files.createDirectories(root.resolve("lang"))
    listOf("config.yml", "lang/ru.yml", "lang/en.yml").forEach { resource ->
        requireNotNull(LumberScreenLocaleTest::class.java.classLoader.getResourceAsStream(resource)).use { input ->
            Files.copy(input, root.resolve(resource))
        }
    }
}
