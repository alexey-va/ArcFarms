package ru.ruscrafting.farms.paper.mine.incident.crystal

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.joml.Vector3d
import ru.ruscrafting.farms.domain.mine.crystal.MineCrystalPhase

class MineCrystalGeometryTest : FunSpec({
    val nodes = listOf(Vector3d(34.5, 87.08, 40.5), Vector3d(36.5, 88.08, 31.5),
        Vector3d(53.5, 88.08, 38.5), Vector3d(52.5, 89.08, 30.5))
    val contacts = listOf(Vector3d(57.5, 90.5, 19.015), Vector3d(58.5, 90.5, 19.015))
    test("native four-crystal geometry retains links and a thick finite wall beam within its fixed budget") {
        MineCrystalPhase.entries.forEach { phase ->
            for (age in 0L..3_600L step 150) {
                val wall = MineCrystalGeometry.impact(contacts, phase, age)
                val points = MineCrystalGeometry.sample(nodes, setOf(0, 1, 2, 3), wall, phase, age, age)
                (points.size <= MineCrystalGeometry.MAX_SAMPLES) shouldBe true
                points.all { it.position.isFinite && it.size > 0 } shouldBe true
                if (phase == MineCrystalPhase.DRILLING && age >= 500) {
                    points.any { it.color == 0xFFF6FF && it.size >= 2.0f } shouldBe true
                    points.any { it.position.distance(wall) < .8 } shouldBe true
                }
            }
        }
    }
    test("drill head reaches each cell at its mutation beat and continues into extraction without a jump") {
        MineCrystalGeometry.impact(contacts, MineCrystalPhase.DRILLING, 500) shouldBe contacts.first()
        MineCrystalGeometry.impact(contacts, MineCrystalPhase.DRILLING, 1_150) shouldBe contacts.last()
        MineCrystalGeometry.impact(contacts, MineCrystalPhase.DRILLING, 3_600) shouldBe
            MineCrystalGeometry.impact(contacts, MineCrystalPhase.EXTRACTING, 0)
        for (age in 0L..1_500L step 50) {
            (MineCrystalGeometry.impact(contacts, MineCrystalPhase.DRILLING, age).distance(
                MineCrystalGeometry.impact(contacts, MineCrystalPhase.DRILLING, age + 50)) <= .12) shouldBe true
        }
    }
})
