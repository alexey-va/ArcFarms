package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.joml.Vector3f
import ru.ruscrafting.farms.paper.worksite.WorksiteDisplayGeometryValidator

/** Focused geometry checks for floor-mounted factory controls and the furnace bridge. */
class MineFactoryControlMountTest : FunSpec({
    test("control assemblies reach the floor without moving their organs upward") {
        val mounted = MineFactoryModels.model("mounted_console")
        val furnace = MineFactoryModels.model("furnace_air_console")

        listOf(mounted, furnace).forEach { parts ->
            parts.minOf { it.center.y - it.size.y / 2f } shouldBe 0f
            val base = parts.first {
                it.material == Material.POLISHED_DEEPSLATE &&
                    it.center.y == .08f && it.size.y == .16f
            }
            (base.center.y - base.size.y / 2f) shouldBe 0f

            val supports = parts.filter {
                it.material == Material.POLISHED_BASALT &&
                    kotlin.math.abs(it.center.x) > .1f && it.center.y < 1f
            }
            supports.size shouldBe 2
            val bodyBottom = parts.filter { it.material == Material.WEATHERED_CUT_COPPER }
                .minOf { it.center.y - it.size.y / 2f }
            supports.forEach { support ->
                // A small inset into both neighbours makes a rigid visual
                // connection while avoiding coplanar display faces.
                (support.center.y - support.size.y / 2f < base.center.y + base.size.y / 2f) shouldBe true
                (support.center.y + support.size.y / 2f > bodyBottom) shouldBe true
            }
        }

        mounted.filter { it.motion == "lever" && it.material == Material.IRON_BLOCK }.single().center.y shouldBe 1.34f
        furnace.filter { it.motion == "lever" && it.material == Material.IRON_BLOCK }.single().center.y shouldBe 1.34f
        furnace.filter { it.motion == "thermometer" }.maxOf { it.center.y } shouldBe 1.72f
    }

    test("floor-mounted controls remain free of coplanar display contacts") {
        listOf("mounted_console", "furnace_air_console").forEach { kind ->
            val conflicts = WorksiteDisplayGeometryValidator.conflicts(
                MineFactoryModels.model(kind).mapIndexed { index, part ->
                    WorksiteDisplayGeometryValidator.Box(
                        "$kind:$index",
                        Vector3f(part.center),
                        Vector3f(part.size),
                        MineDisplayBlueprints.rotation(part, 0f),
                    )
                },
            )
            conflicts shouldBe emptyList<WorksiteDisplayGeometryValidator.Conflict>()
        }
    }

    test("molten bridge starts at the shifted casting-bed inlet") {
        MineFactoryModels.moltenPath.take(2).map { it.x } shouldBe listOf(-4f, -4f)
        MineFactoryModels.moltenPath[2].x shouldBe -3.3f
        MineFactoryModels.moltenPath[2].z shouldBe 0f
    }
})
