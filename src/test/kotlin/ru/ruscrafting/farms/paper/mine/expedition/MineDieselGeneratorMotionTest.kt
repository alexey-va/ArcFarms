package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs

class MineDieselGeneratorMotionTest : FunSpec({
    test("all six rods stay joined to their crankpins and piston pins through a full revolution") {
        val parts = MineDieselGeneratorModel.model()
        for (cylinder in 0..5) {
            val pivot = Vector3f(0f, 1.8f, -2.75f + cylinder * 1.1f)
            val piston = parts.single { it.motion == "diesel_piston_$cylinder" && it.center == Vector3f() }
            val rod = parts.single { it.motion == "diesel_rod_$cylinder" }
            val crankPin = parts.single { it.motion == "rotate" && it.material == Material.CUT_COPPER &&
                it.pivot.distance(pivot) < .00001f && it.size == Vector3f(.28f, .28f, .3f) }
            val halfRod = rod.size.y / 2
            for (step in 0..120) {
                val phase = (step * PI / 60).toFloat()
                val center = MineDisplayBlueprints.center(rod, phase)
                val rotation = MineDisplayBlueprints.rotation(rod, phase)
                val top = rotation.transform(Vector3f(0f, halfRod, 0f)).add(center)
                val bottom = rotation.transform(Vector3f(0f, -halfRod, 0f)).add(center)
                val pin = MineDisplayBlueprints.center(crankPin, phase)
                (top.distance(MineDisplayBlueprints.center(piston, phase)) < .00001f) shouldBe true
                (bottom.distance(pin) < .00001f) shouldBe true
                (abs(top.distance(bottom) - 2.5f) < .00001f) shouldBe true
                (abs(top.x) < .00001f) shouldBe true
                // The complete crown stays in its 3.32..4.99 liner/head space.
                (top.y + piston.size.y / 2 < 4.99f) shouldBe true
                (top.y - piston.size.y / 2 > 3.32f) shouldBe true
            }
            (MineDisplayBlueprints.center(piston, 0f).distance(
                MineDisplayBlueprints.center(piston, (2 * PI).toFloat())) < .00001f) shouldBe true
        }
    }
})
