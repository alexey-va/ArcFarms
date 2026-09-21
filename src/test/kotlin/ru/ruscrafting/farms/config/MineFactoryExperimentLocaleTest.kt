package ru.ruscrafting.farms.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.opentest4j.TestAbortedException
import ru.arc.config.Config
import java.nio.file.Files
import java.nio.file.Path

class MineFactoryExperimentLocaleTest : FunSpec({
    test("factory experiment prompts are complete and mirrored across bundled profiles") {
        val bundled = Files.createTempDirectory("arcfarms-factory-locale")
        Files.createDirectories(bundled.resolve("lang"))
        listOf("ru", "en").forEach { language ->
            requireNotNull(MineFactoryExperimentLocaleTest::class.java.classLoader
                .getResourceAsStream("lang/$language.yml")).use { input ->
                Files.copy(input, bundled.resolve("lang/$language.yml"))
            }
        }
        val ops = System.getProperty("ruscrafting.opsRoot")?.let(Path::of)
            ?: throw TestAbortedException("RusCrafting ops checkout is not configured")
        val roots = linkedMapOf(
            "bundled" to bundled,
            "classic" to ops.resolve("classic/plugins/ArcFarms"),
            "classic_survival" to ops.resolve("classic_survival/plugins/ArcFarms"),
            "parkour" to ops.resolve("parkour/plugins/ArcFarms"),
        )
        val expected = setOf(
            "rock-pry", "rock-free", "mould-select", "mould-wrong", "mould-fitting",
            "crane-grab", "crane-aim", "crane-drop", "crane-miss",
            "route-switch", "route-return", "route-moving",
            "cooling-pickup", "cooling-aim", "cooling-progress", "busy",
        )
        val values = roots.mapValues { (_, root) ->
            listOf("ru", "en").associateWith { language ->
                val catalog = Config(root, "lang/$language.yml")
                val section = expected.associateWith { key ->
                    catalog.string("mine.expedition.experiment.$key")
                }
                section.keys shouldBe expected
                section.forEach { (key, text) ->
                    text.isNotBlank() shouldBe true
                    val placeholders = Regex("<([a-z][a-z0-9_-]*)>")
                        .findAll(text)
                        .map { it.groupValues[1] }
                        .toSet()
                    withClue("$root/$language/$key") {
                        placeholders shouldBe if (key == "cooling-progress") setOf("percent") else emptySet()
                    }
                }
                section
            }
        }
        roots.keys.filter { it != "bundled" }.forEach { profile ->
            listOf("ru", "en").forEach { language ->
                values[profile]!![language] shouldBe values["bundled"]!![language]
            }
        }
    }
})
