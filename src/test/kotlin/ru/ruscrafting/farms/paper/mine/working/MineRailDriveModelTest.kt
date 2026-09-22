package ru.ruscrafting.farms.paper.mine.working

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Material
import org.joml.Vector3f
import ru.ruscrafting.farms.paper.mine.expedition.MineDisplayBlueprints
import ru.ruscrafting.farms.paper.worksite.WorksiteDisplayGeometryValidator
import kotlin.math.PI

class MineRailDriveModelTest : FunSpec({
    data class Bounds(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
        val minZ: Float,
        val maxZ: Float,
    )

    fun bounds(part: MineDisplayBlueprints.Part, phase: Float): Bounds {
        val center = MineDisplayBlueprints.center(part, phase)
        val rotation = MineDisplayBlueprints.rotation(part, phase)
        val corners = buildList {
            for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) {
                add(
                    rotation.transform(
                        Vector3f(
                            sx * part.size.x / 2f,
                            sy * part.size.y / 2f,
                            sz * part.size.z / 2f,
                        ),
                    ).add(center),
                )
            }
        }
        return Bounds(
            minX = corners.minOf { it.x },
            maxX = corners.maxOf { it.x },
            minY = corners.minOf { it.y },
            maxY = corners.maxOf { it.y },
            minZ = corners.minOf { it.z },
            maxZ = corners.maxOf { it.z },
        )
    }

    fun boxes(parts: List<MineDisplayBlueprints.Part>, phase: Float) = parts.mapIndexed { index, part ->
        WorksiteDisplayGeometryValidator.Box(
            id = "$index:${part.material}:${part.motion}",
            center = MineDisplayBlueprints.center(part, phase),
            size = Vector3f(part.size),
            rotation = MineDisplayBlueprints.rotation(part, phase),
        )
    }

    test("retains the drilling base and adds a rear rail layer") {
        MineRailDriveModel.parts.take(MineDriveModel.parts.size) shouldBe MineDriveModel.parts
        MineRailDriveModel.parts.size shouldNotBe MineDriveModel.parts.size

        val layer = MineRailDriveModel.parts.drop(MineDriveModel.parts.size)
        layer.any { it.material == Material.YELLOW_TERRACOTTA } shouldBe true
        layer.any { it.material == Material.IRON_BLOCK } shouldBe true
        layer.any { it.material == Material.DARK_OAK_PLANKS } shouldBe true
        layer.count { it.motion == "axle" && it.moving } shouldBe 32
        val pressParts = layer.filter { it.motion == "press" && it.moving }
        pressParts.any { it.material == Material.DARK_OAK_PLANKS && it.size.x > 1f } shouldBe true
        val crosshead = pressParts.single { it.material == Material.COPPER_BLOCK }
        val guides = layer.filter { it.material == Material.IRON_BLOCK && it.size.y > 1.5f }
        guides.size shouldBe 2
        for (phase in listOf(0f, PI.toFloat())) {
            val moving = bounds(crosshead, phase)
            guides.forEach { guide ->
                val fixed = bounds(guide, phase)
                (moving.minY >= fixed.minY && moving.maxY <= fixed.maxY) shouldBe true
            }
        }
    }

    test("rail layer stays in the carrier envelope throughout its motion") {
        val layer = MineRailDriveModel.parts.drop(MineDriveModel.parts.size)
        for (step in 0..64) {
            val phase = (step * PI / 32.0).toFloat()
            layer.map { bounds(it, phase) }.forEach { bound ->
                (bound.minX >= -1.18f) shouldBe true
                (bound.maxX <= 1.18f) shouldBe true
                (bound.minY >= 0f) shouldBe true
                (bound.maxY <= 2.5f) shouldBe true
                (bound.minZ >= -1.65f) shouldBe true
                (bound.maxZ <= 2.25f) shouldBe true
            }
        }
    }

    test("full rail body has no coplanar face overlap at sampled poses") {
        for (step in 0..64) {
            val phase = (step * PI / 32.0).toFloat()
            WorksiteDisplayGeometryValidator.conflicts(boxes(MineRailDriveModel.parts, phase)) shouldBe
                emptyList<WorksiteDisplayGeometryValidator.Conflict>()
        }
    }

    test("service anchors and service models describe rail maintenance") {
        MineRailDriveModel.kinds shouldBe
            setOf("rail_drive_cassette", "rail_drive_jam", "rail_drive_feeder")
        MineRailDriveModel.servicePoints[MineRailDriveModel.RESERVE_SLOT_LABEL] shouldBe
            Vector3f(-1.02f, .70f, -.75f)
        MineRailDriveModel.servicePoints[MineRailDriveModel.FEEDER_LABEL] shouldBe
            Vector3f(1.02f, .70f, -.35f)

        val cassette = MineRailDriveModel.model("rail_drive_cassette")
        cassette.count { it.material == Material.IRON_BLOCK } shouldBe 2
        cassette.count { it.material == Material.DARK_OAK_PLANKS } shouldBe 3
        WorksiteDisplayGeometryValidator.conflicts(boxes(cassette, 0f)) shouldBe
            emptyList<WorksiteDisplayGeometryValidator.Conflict>()

        val jam = MineRailDriveModel.model("rail_drive_jam")
        jam.any { it.material == Material.DARK_OAK_PLANKS } shouldBe true
        jam.any { it.material == Material.SPRUCE_PLANKS } shouldBe true
        jam.any { kotlin.math.abs(it.angle) > .01f } shouldBe true
        jam.none { it.material in setOf(Material.STONE, Material.COBBLESTONE, Material.TUFF) } shouldBe true
        WorksiteDisplayGeometryValidator.conflicts(boxes(jam, 0f)) shouldBe
            emptyList<WorksiteDisplayGeometryValidator.Conflict>()

        val feeder = MineRailDriveModel.model("rail_drive_feeder")
        feeder.any { it.material == Material.YELLOW_TERRACOTTA } shouldBe true
        feeder.any { it.material == Material.IRON_BLOCK } shouldBe true
        feeder.none { it.material == Material.DARK_OAK_PLANKS } shouldBe true
        WorksiteDisplayGeometryValidator.conflicts(boxes(feeder, 0f)) shouldBe
            emptyList<WorksiteDisplayGeometryValidator.Conflict>()
    }
})
