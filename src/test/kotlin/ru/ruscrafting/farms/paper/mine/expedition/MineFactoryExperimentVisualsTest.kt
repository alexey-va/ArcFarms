package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.joml.Vector3f
import kotlin.math.abs

class MineFactoryExperimentVisualsTest : FunSpec({
    test("movable experiment pieces keep only their carried geometry") {
        val ore = MineFactoryExperimentVisualGeometry.parts("ore_piece")
        ore.size shouldBe 1
        ore.single().material shouldBe Material.RAW_IRON_BLOCK
        ore.single().size shouldBe Vector3f(.3f)

        listOf("gear", "plate", "rod").forEach { shape ->
            val full = MineFactoryExperimentModels.model("factory_mould_$shape")
            val piece = MineFactoryExperimentVisualGeometry.parts("mould_${shape}_piece")
            piece.size shouldBe full.size - 4
            val minY = piece.minOf { it.center.y - it.size.y / 2f }
            val maxY = piece.maxOf { it.center.y + it.size.y / 2f }
            (abs((minY + maxY) / 2f) < .000001f) shouldBe true
        }

        val nozzle = MineFactoryExperimentVisualGeometry.parts("hose_nozzle_held")
        nozzle.size shouldBe MineFactoryExperimentModels.model("factory_hose_nozzle").size - 2
        nozzle.first().center.y shouldBe 0f
    }

    test("orientation follows the packet display yaw and downward pitch convention") {
        val yawed = MineFactoryExperimentVisualGeometry.orientation(90f, 0f)
            .transform(Vector3f(0f, 0f, 1f))
        (abs(yawed.x + 1f) < .0001f && abs(yawed.z) < .0001f) shouldBe true

        val pitched = MineFactoryExperimentVisualGeometry.orientation(0f, 90f)
            .transform(Vector3f(0f, 0f, 1f))
        (abs(pitched.y + 1f) < .0001f && abs(pitched.z) < .0001f) shouldBe true
    }

    test("scaled pose geometry remains finite and scales both offset and bounds") {
        val part = MineDisplayBlueprints.Part(
            Material.IRON_BLOCK,
            Vector3f(0f, 0f, 1f),
            Vector3f(1f, .5f, .25f),
        )
        val pose = MineFactoryExperimentVisualGeometry.transformation(
            part,
            2f,
            MineFactoryExperimentVisualGeometry.orientation(0f, 0f),
        )
        pose.scale shouldBe Vector3f(2f, 1f, .5f)
        pose.translation shouldBe Vector3f(-1f, -.5f, 1.75f)
        listOf(pose.translation.x, pose.translation.y, pose.translation.z,
            pose.scale.x, pose.scale.y, pose.scale.z).all(Float::isFinite) shouldBe true
    }
})
