package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.joml.Vector3f
import ru.ruscrafting.farms.paper.worksite.WorksiteDisplayGeometryValidator
import kotlin.math.abs

class MineFactoryExperimentModelsTest : FunSpec({
    test("publishes the complete factory experiment fixture set") {
        MineFactoryExperimentModels.kinds shouldBe setOf(
            "factory_jam_stone",
            "factory_mould_gear",
            "factory_mould_plate",
            "factory_mould_rod",
            "factory_mould_socket",
            "factory_mould_stand",
            "factory_route_gate",
            "factory_route_bin",
            "factory_hose_reel",
            "factory_hose_nozzle",
            "factory_hose_stand",
            "factory_hot_bearing",
            "factory_crane_landing",
            "factory_product_plate",
            "factory_product_rod",
        )
        MineFactoryExperimentModels.kinds.forEach { kind ->
            MineFactoryExperimentModels.model(kind).isNotEmpty() shouldBe true
        }
    }

    test("every fixture passes the shared coplanar face validator") {
        MineFactoryExperimentModels.kinds.forEach { kind ->
            val boxes = MineFactoryExperimentModels.model(kind).mapIndexed { index, part ->
                WorksiteDisplayGeometryValidator.Box(
                    id = "$kind:$index",
                    center = Vector3f(part.center),
                    size = Vector3f(part.size),
                    rotation = MineDisplayBlueprints.rotation(part, 0f),
                )
            }
            val conflicts = WorksiteDisplayGeometryValidator.conflicts(boxes)
            conflicts shouldBe emptyList<WorksiteDisplayGeometryValidator.Conflict>()
        }
    }

    test("moulds share a supported stand but have three different silhouettes") {
        val moulds = listOf("factory_mould_gear", "factory_mould_plate", "factory_mould_rod")
            .map(MineFactoryExperimentModels::model)
        moulds.map { it.take(4) }.distinct().size shouldBe 1
        moulds.map { parts ->
            parts.drop(4).joinToString("|") { part ->
                "${part.material}:${part.center}:${part.size}"
            }
        }.toSet().size shouldBe 3
    }

    test("moving and directional fixtures expose usable geometry") {
        val gate = MineFactoryExperimentModels.model("factory_route_gate")
            .single { it.motion == "lever" }
        gate.moving shouldBe true
        gate.center shouldBe Vector3f(-.08f, .82f, 0f)
        gate.pivot shouldBe Vector3f(-.72f, .82f, 0f)

        MineFactoryExperimentModels.model("factory_hose_reel")
            .count { it.moving && it.motion == "rotate" } shouldBe 8

        val nozzle = MineFactoryExperimentModels.model("factory_hose_nozzle")
        (nozzle.maxOf { it.center.z + it.size.z / 2f } -
            nozzle.minOf { it.center.z - it.size.z / 2f } > .70f) shouldBe true

        MineFactoryExperimentModels.model("factory_crane_landing")
            // Part.angle rotates in the X/Y plane; landing guides are kept
            // horizontal so their corners remain on the pad rather than
            // becoming tilted ramps.
            .count { abs(it.angle) > .01f } shouldBe 0
    }

    test("jam stone remains a small irregular mineral obstruction") {
        val stone = MineFactoryExperimentModels.model("factory_jam_stone")
        stone.all { it.material == Material.TUFF || it.material == Material.ANDESITE } shouldBe true
        val width = stone.maxOf { it.center.x + it.size.x / 2f } - stone.minOf { it.center.x - it.size.x / 2f }
        val height = stone.maxOf { it.center.y + it.size.y / 2f } - stone.minOf { it.center.y - it.size.y / 2f }
        val depth = stone.maxOf { it.center.z + it.size.z / 2f } - stone.minOf { it.center.z - it.size.z / 2f }
        (width <= .80f && height <= .80f && depth <= .80f) shouldBe true
        (stone.map { it.size }.distinct().size > 1) shouldBe true
    }

    test("finished products are iron bench pieces with no hidden legs") {
        listOf("factory_product_plate", "factory_product_rod").forEach { kind ->
            val parts = MineFactoryExperimentModels.model(kind)
            parts.all { it.material == Material.IRON_BLOCK } shouldBe true
            parts.all { it.center.y - it.size.y / 2f >= 0f } shouldBe true
        }
        MineFactoryExperimentModels.model("factory_product_plate").size shouldBe 5
        MineFactoryExperimentModels.model("factory_product_rod").size shouldBe 6
        val plateShape = MineFactoryExperimentModels.model("factory_product_plate")
            .joinToString("|") { "${it.center}:${it.size}" }
        val rodShape = MineFactoryExperimentModels.model("factory_product_rod")
            .joinToString("|") { "${it.center}:${it.size}" }
        (plateShape != rodShape) shouldBe true
    }
})
