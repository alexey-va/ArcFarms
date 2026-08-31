package ru.ruscrafting.farms.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class MineCartVisualConfigTest : FunSpec({
    test("bundled mine cart visual is portable without a resource pack") {
        val settings = inspect(sourceConfig())

        settings.mines.forEach { mine ->
            mine.cartVisual shouldBe MineCartVisualSettings()
        }
    }

    test("mine cart visual parses every non-default runtime field") {
        val configured = DEFAULT_CART.replace("material: MINECART", "material: DIAMOND")
            .replace("custom-model-data: 0", "custom-model-data: 777")
            .replace("item-model: ''", "item-model: 'minecraft:diamond'")
            .replace("display-transform: GROUND", "display-transform: FIXED")
            .replace("scale: 1.0", "scale: 2.25")
            .replace("y-offset: 0.15", "y-offset: 0.75")
            .replace("view-range: 2.0", "view-range: 10.0")
            .replace("interaction-width: 1.5", "interaction-width: 3.25")
            .replace("interaction-height: 1.0", "interaction-height: 2.25")
        val mine = inspect(sourceConfig().replaceFirst(DEFAULT_CART, configured)).mines.single { it.id == "old_shafts" }

        mine.cartVisual shouldBe MineCartVisualSettings(
            material = "DIAMOND",
            customModelData = 777,
            itemModel = "minecraft:diamond",
            displayTransform = FarmItemDisplayTransform.FIXED,
            scale = 2.25f,
            yOffset = 0.75,
            viewRange = 10.0f,
            interactionWidth = 3.25f,
            interactionHeight = 2.25f,
        )
    }

    test("mine cart visual rejects values outside its live-safe bounds") {
        val invalid = DEFAULT_CART.replace("view-range: 2.0", "view-range: 64.01")

        shouldThrow<IllegalArgumentException> {
            inspect(sourceConfig().replaceFirst(DEFAULT_CART, invalid))
        }
    }
}) {
    companion object {
        private val DEFAULT_CART = """
            |      cart:
            |        material: MINECART
            |        custom-model-data: 0
            |        item-model: ''
            |        display-transform: GROUND
            |        scale: 1.0
            |        y-offset: 0.15
            |        view-range: 2.0
            |        interaction-width: 1.5
            |        interaction-height: 1.0
        """.trimMargin()

        private fun sourceConfig(): String {
            val project = Path.of(requireNotNull(System.getProperty("arcfarms.projectDir")))
            return Files.readString(project.resolve("src/main/resources/config.yml"))
        }

        private fun inspect(source: String): ArcFarmsConfig {
            val root = Files.createTempDirectory("arcfarms-mine-cart-config")
            Files.writeString(root.resolve("config.yml"), source)
            return ArcFarmsConfig.inspect(root)
        }
    }
}
