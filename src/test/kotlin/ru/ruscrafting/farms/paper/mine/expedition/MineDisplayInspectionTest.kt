package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.joml.Vector3f
import kotlin.math.PI

class MineDisplayInspectionTest : FunSpec({
    test("the actual nearest solid wins and unlabelled guards occlude named parts") {
        val piston = MineDisplayBlueprints.Part(Material.IRON_BLOCK, Vector3f(), Vector3f(1f), inspection = "piston")
        val guard = piston.copy(center = Vector3f(2f, 0f, 0f), inspection = null)
        val eye = Vector3f(4f, 0f, 0f)
        val direction = Vector3f(-1f, 0f, 0f)
        MineDisplayInspection.pick(listOf(piston, guard), 0f, eye, direction, 8f)?.part shouldBe guard
        MineDisplayInspection.pick(listOf(piston), 0f, eye, direction, 3f) shouldBe null
        MineDisplayInspection.pick(listOf(piston), 0f, eye, Vector3f(1f, 0f, 0f), 8f) shouldBe null
        MineDisplayInspection.pick(listOf(piston), 0f, eye, Vector3f(), 8f) shouldBe null
    }

    test("selection follows piston motion rather than its authored local offset") {
        val piston = MineDieselGeneratorModel.model().single {
            it.motion == "diesel_piston_0" && it.center == Vector3f()
        }
        val top = MineDisplayBlueprints.center(piston, 0f)
        val topEye = Vector3f(top).add(3f, 0f, 0f)
        val direction = Vector3f(-1f, 0f, 0f)
        MineDisplayInspection.pick(listOf(piston), 0f, topEye, direction, 8f)?.part shouldBe piston
        MineDisplayInspection.pick(listOf(piston), PI.toFloat(), topEye, direction, 8f) shouldBe null
        val bottomEye = MineDisplayBlueprints.center(piston, PI.toFloat()).add(3f, 0f, 0f)
        MineDisplayInspection.pick(listOf(piston), PI.toFloat(), bottomEye, direction, 8f)?.part shouldBe piston
    }

    test("rotation is applied to thin parts and their empty bounding-box corners remain empty") {
        val rod = MineDisplayBlueprints.Part(Material.IRON_BLOCK, Vector3f(), Vector3f(.1f, 2f, .1f),
            angle = (PI / 4).toFloat(), inspection = "connecting-rod")
        MineDisplayInspection.pick(listOf(rod), 0f, Vector3f(0f, 0f, 2f), Vector3f(0f, 0f, -1f), 3f)?.part shouldBe rod
        MineDisplayInspection.pick(listOf(rod), 0f, Vector3f(.6f, .6f, 2f), Vector3f(0f, 0f, -1f), 3f) shouldBe null
    }
})
