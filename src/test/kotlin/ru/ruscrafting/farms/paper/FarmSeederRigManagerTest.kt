package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.util.Vector
import kotlin.math.hypot

class FarmSeederRigManagerTest : FunSpec({
    test("pig steering follows the moving formation smoothly without exceeding its speed cap") {
        val velocity = smoothSeederVelocity(
            current = Vector(0.1, 0.0, 0.0),
            offset = Vector(4.0, 0.0, 3.0),
            maxSpeed = 0.46,
        )

        (velocity.x > 0.0) shouldBe true
        (velocity.z > 0.0) shouldBe true
        (hypot(velocity.x, velocity.z) <= 0.46) shouldBe true
    }

    test("pig steering damps horizontal drift after reaching its formation slot") {
        val velocity = smoothSeederVelocity(
            current = Vector(0.4, 0.1, -0.2),
            offset = Vector(0.0, 0.0, 0.0),
            maxSpeed = 0.46,
        )

        velocity.x shouldBe (0.14 plusOrMinus 1.0e-9)
        velocity.y shouldBe (0.1 plusOrMinus 1.0e-9)
        velocity.z shouldBe (-0.07 plusOrMinus 1.0e-9)
    }

    test("pig steering caps inherited leash velocity") {
        val velocity = smoothSeederVelocity(
            current = Vector(4.0, 0.0, 4.0),
            offset = Vector(2.0, 0.0, 0.0),
            maxSpeed = 0.46,
        )

        (hypot(velocity.x, velocity.z) <= 0.46 + 1.0e-9) shouldBe true
    }
})
