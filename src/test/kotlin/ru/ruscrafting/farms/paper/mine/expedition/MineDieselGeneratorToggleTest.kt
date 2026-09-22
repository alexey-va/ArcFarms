package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.config.ArcFarmsConfig
import java.nio.file.Files

class MineDieselGeneratorToggleTest : FunSpec({
    test("cutaway toggle defaults on and accepts an explicit off value") {
        val root = Files.createTempDirectory("mine-diesel-toggle")
        try {
            listOf("config.yml", "lang/ru.yml", "lang/en.yml", "modules/redis.yml").forEach { name ->
                val target = root.resolve(name)
                Files.createDirectories(target.parent)
                val input = requireNotNull(MineDieselGeneratorToggleTest::class.java.classLoader.getResourceAsStream(name)) { "Missing $name" }
                input.use { Files.copy(it, target) }
            }

            val configPath = root.resolve("config.yml")
            val bundled = Files.readString(configPath)
            ArcFarmsConfig.inspect(root).dieselGeneratorEnabled shouldBe true

            val toggleLine = "  diesel-generator-enabled: true\n"
            bundled.contains(toggleLine) shouldBe true
            Files.writeString(configPath, bundled.replace(toggleLine, ""))
            ArcFarmsConfig.inspect(root).dieselGeneratorEnabled shouldBe true

            Files.writeString(configPath, bundled.replace(toggleLine, "  diesel-generator-enabled: false\n"))
            ArcFarmsConfig.inspect(root).dieselGeneratorEnabled shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
