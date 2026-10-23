package ru.ruscrafting.farms.paper.farm.incident.tornado

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.joml.Matrix4f

class FarmTornadoTransformTest : FunSpec({
    test("packet debris preserves the original rotating centered block matrix") {
        listOf(0.0, 0.15, 1.0, 17.35).forEach { time ->
            repeat(10) { index ->
                val size = (0.35 + (index % 5) * 0.14).toFloat()
                val original = Matrix4f()
                    .rotateXYZ((time * 1.8 + index).toFloat(), (time * 2.3).toFloat(), (time + index).toFloat())
                    .scale(size).translate(-0.5f, -0.5f, -0.5f)
                val pose = FarmTornadoScene.debrisTransformation(time, index)
                val migrated = Matrix4f().translation(pose.translation).rotate(pose.leftRotation)
                    .scale(pose.scale).rotate(pose.rightRotation)
                // Float half-angle quaternion and full-angle matrix trigonometry differ by at most 5.1e-6 here.
                original.equals(migrated, 0.00001f) shouldBe true
            }
        }
    }
})
