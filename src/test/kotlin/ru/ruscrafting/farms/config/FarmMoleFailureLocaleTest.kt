package ru.ruscrafting.farms.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.Config
import java.nio.file.Files

class FarmMoleFailureLocaleTest : FunSpec({
    for (language in listOf("ru", "en")) test("$language mole failure messages render with the production locale parser") {
        val root = Files.createTempDirectory("mole-failure-locale")
        try {
            Files.createDirectories(root.resolve("lang"))
            listOf("config.yml", "lang/ru.yml", "lang/en.yml").forEach { name ->
                requireNotNull(javaClass.classLoader.getResourceAsStream(name)).use { Files.copy(it, root.resolve(name)) }
            }
            Config(root, "config.yml").also { it.setString("locale.default", language); it.saveStrict() }
            val settings = ArcFarmsConfig.inspect(root)
            val locale = ArcFarmsLocale(root) { settings }
            val rendered = listOf(MessageKey.ADMIN_MOLES_UNAVAILABLE, MessageKey.ADMIN_MOLES_PREPARE_FAILED,
                MessageKey.ADMIN_MOLES_STATE_CHANGED).map { PlainTextComponentSerializer.plainText().serialize(locale.render(it)) }
            rendered.toSet().size shouldBe 3
            rendered.forEach { text ->
                text shouldNotContain "<"
                text shouldNotContain "admin."
            }
            rendered[0] shouldContain if (language == "ru") "Не удалось выбрать место" else "No suitable burrow location"
            rendered[1] shouldContain if (language == "ru") "подготовка не завершилась" else "preparation failed"
            rendered[2] shouldContain if (language == "ru") "состояние фермы изменилось" else "farm state changed"
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
