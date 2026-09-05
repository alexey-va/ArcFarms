package ru.ruscrafting.farms.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import ru.ruscrafting.farms.domain.FarmCareRole
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class ArcFarmsDisplayTransformConfigTest : FunSpec({
    val cases = listOf(
        TransformCase("farm-zones.communal_farm.care-visuals.weed-root.display-transform", "care-visuals.weed-root.display-transform", FarmItemDisplayTransform.FIXED) {
            it.farms.single().careVisuals.getValue(FarmCareRole.WEED_ROOT).displayTransform
        },
        TransformCase("farm-zones.communal_farm.contract-scene.cart.display-transform", "contract-scene.cart.display-transform", FarmItemDisplayTransform.GROUND) {
            it.farms.single().contractCartVisual.displayTransform
        },
        TransformCase("farm-zones.communal_farm.processing.visuals.machine.display-transform", "processing.visuals.machine.display-transform", FarmItemDisplayTransform.FIXED) {
            it.farms.single().processing.visuals.getValue(FarmProcessingVisualRole.MACHINE).displayTransform
        },
        TransformCase("farm-zones.communal_farm.mole-burrow.lair-visual.display-transform", "mole-burrow.lair-visual.display-transform", FarmItemDisplayTransform.FIXED) {
            it.farms.single().moleBurrow.lairVisual.displayTransform
        },
        TransformCase("mine-zones.old_shafts.extraction.cart.display-transform", "mine-zones.old_shafts.extraction.cart.display-transform", FarmItemDisplayTransform.GROUND) {
            it.mines.single { it.id == "old_shafts" }.cartVisual.displayTransform
        },
        TransformCase("farm-zones.communal_farm.delivery.display-transform", "farm-zones.communal_farm.delivery.display-transform", FarmItemDisplayTransform.GROUND) {
            it.farms.single().delivery.displayTransform
        },
    )

    cases.forEach { candidate ->
        test("${candidate.path} uses default when missing and accepts trimmed case") {
            val defaultsRoot = configRoot()
            try {
                Config(defaultsRoot, "config.yml").also {
                    it.removeKey(candidate.path)
                    it.saveStrict()
                }
                candidate.read(ArcFarmsConfig.inspect(defaultsRoot)) shouldBe candidate.default

                val customizedRoot = configRoot()
                try {
                    Config(customizedRoot, "config.yml").also {
                        it.setString(candidate.path, "  head ")
                        it.saveStrict()
                    }
                    candidate.read(ArcFarmsConfig.inspect(customizedRoot)) shouldBe FarmItemDisplayTransform.HEAD
                } finally {
                    customizedRoot.toFile().deleteRecursively()
                }
            } finally {
                defaultsRoot.toFile().deleteRecursively()
            }
        }

        test("${candidate.path} rejects an invalid label with its exact error") {
            val root = configRoot()
            try {
                Config(root, "config.yml").also {
                    it.setString(candidate.path, "SIDEWAYS")
                    it.saveStrict()
                }
                shouldThrow<IllegalStateException> { ArcFarmsConfig.inspect(root) }
                    .message shouldBe "${candidate.errorPath} must be GROUND, FIXED, or HEAD"
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }
}) {
    private data class TransformCase(
        val path: String,
        val errorPath: String,
        val default: FarmItemDisplayTransform,
        val read: (ArcFarmsConfig) -> FarmItemDisplayTransform,
    )

    companion object {
        private fun configRoot(): Path {
            val root = Files.createTempDirectory("arcfarms-transform-test-${UUID.randomUUID()}")
            val input = requireNotNull(ArcFarmsDisplayTransformConfigTest::class.java.classLoader.getResourceAsStream("config.yml"))
            input.use { Files.copy(it, root.resolve("config.yml")) }
            return root
        }
    }
}
