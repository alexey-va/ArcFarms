package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs

private fun verticalBounds(part: MineDisplayBlueprints.Part, phase: Float): Pair<Float, Float> {
    val center = MineDisplayBlueprints.center(part, phase)
    val rotation = MineDisplayBlueprints.rotation(part, phase)
    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (x in listOf(-.5f, .5f)) for (y in listOf(-.5f, .5f)) for (z in listOf(-.5f, .5f)) {
        val cornerY = rotation.transform(Vector3f(part.size).mul(x, y, z)).add(center).y
        minY = minOf(minY, cornerY)
        maxY = maxOf(maxY, cornerY)
    }
    return minY to maxY
}

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
            for (step in 0..240) {
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
    test("twelve cam noses complete one turn per two crank revolutions and drive their valve followers") {
        val parts = MineDieselGeneratorModel.model()
        val noses = parts.filter { it.motion == "diesel_cam" && it.size == Vector3f(.18f, .42f, .15f) }
        noses.size shouldBe 12
        for (nose in noses) {
            val initial = MineDisplayBlueprints.center(nose, 0f)
            (initial.distance(MineDisplayBlueprints.center(nose, (2 * PI).toFloat())) > .43f) shouldBe true
            (initial.distance(MineDisplayBlueprints.center(nose, (4 * PI).toFloat())) < .00001f) shouldBe true
            val cylinder = ((nose.pivot.z + 2.75f) / 1.1f).let { kotlin.math.round(it).toInt() }
            val inlet = nose.pivot.z > -2.75f + cylinder * 1.1f
            val tag = "diesel_valve_${if (inlet) "inlet" else "exhaust"}_$cylinder"
            val follower = parts.single { it.motion == tag && it.size == Vector3f(.9f, .12f, .19f) }
            for (step in 0..240) {
                val phase = (step * PI / 60).toFloat()
                val lift = MineDieselGeneratorMotion.valveLift(cylinder, inlet, phase)
                val followerTop = MineDisplayBlueprints.center(follower, phase).y + follower.size.y / 2
                val rotation = MineDisplayBlueprints.rotation(nose, phase)
                val center = MineDisplayBlueprints.center(nose, phase)
                val noseBottom = listOf(-.5f, .5f).flatMap { x -> listOf(-.5f, .5f).map { y ->
                    rotation.transform(Vector3f(nose.size.x * x, nose.size.y * y, 0f)).add(center).y
                } }.min()
                (followerTop <= noseBottom + .00001f) shouldBe true
                if (lift > .00001f) (abs(followerTop - noseBottom) < .00001f) shouldBe true
            }
            val peak = -2 * MineDieselGeneratorMotion.camAngle(cylinder, inlet)
            (MineDieselGeneratorMotion.valveLift(cylinder, inlet, peak) > .11f) shouldBe true
            MineDieselGeneratorMotion.valveLift(cylinder, inlet, peak + (2 * PI).toFloat()) shouldBe 0f
        }
    }

    test("twelve valve plates stay inside their cylinder bores") {
        val parts = MineDieselGeneratorModel.model()
        val tips = parts.filter { it.motion.startsWith("diesel_injector_tip_") }
        tips.size shouldBe 6
        val plateSize = Vector3f(.24f, .07f, .24f)
        val plates = parts.filter { it.motion.startsWith("diesel_valve_") && it.size == plateSize && it.center.y < 5.5f }
        plates.size shouldBe 12

        for (cylinder in 0..5) {
            val axisZ = tips.single { it.motion == "diesel_injector_tip_$cylinder" }.center.z
            val axisX = parts.single { it.motion == "diesel_piston_$cylinder" && it.center == Vector3f() }.pivot.x
            val cylinderPlates = plates.filter { it.motion.endsWith("_$cylinder") && it.center.y < 5.5f }
            cylinderPlates.size shouldBe 2
            val offsets = cylinderPlates.map { it.center.z - axisZ }.sorted()
            (abs(offsets[0] + .23f) < .00001f) shouldBe true
            (abs(offsets[1] - .23f) < .00001f) shouldBe true

            for (plate in cylinderPlates) {
                // Bore fit is a physical radial constraint: include each square plate corner.
                val farthestCorner = listOf(-.5f, .5f).maxOf { x ->
                    listOf(-.5f, .5f).maxOf { z ->
                        val dx = plate.center.x + x * plate.size.x - axisX
                        val dz = plate.center.z + z * plate.size.z - axisZ
                        kotlin.math.sqrt(dx * dx + dz * dz)
                    }
                }
                (farthestCorner < .46f) shouldBe true
            }
        }
    }

    test("injector tips and valve plates clear the piston crowns through 720 crank degrees") {
        val parts = MineDieselGeneratorModel.model()
        for (cylinder in 0..5) {
            val piston = parts.single { it.motion == "diesel_piston_$cylinder" && it.center == Vector3f() }
            val tip = parts.single { it.motion == "diesel_injector_tip_$cylinder" }
            tip.moving shouldBe false
            val tipBottom = tip.center.y - tip.size.y / 2
            var maxCrownY = Float.NEGATIVE_INFINITY
            val cylinderPlates = parts.filter {
                it.motion.startsWith("diesel_valve_") && it.motion.endsWith("_$cylinder") &&
                    it.size == Vector3f(.24f, .07f, .24f) && it.center.y < 5.5f
            }
            cylinderPlates.size shouldBe 2

            for (step in 0..1440) {
                val phase = (step * PI / 360).toFloat()
                val crownTop = verticalBounds(piston, phase).second
                maxCrownY = maxOf(maxCrownY, crownTop)
                for (plate in cylinderPlates) {
                    val plateBottom = verticalBounds(plate, phase).first
                    // X/Z footprints overlap, so vertical clearance is the separating dimension.
                    val xOverlap = abs(plate.center.x - MineDisplayBlueprints.center(piston, phase).x) <
                        (plate.size.x + piston.size.x) / 2
                    val zOverlap = abs(plate.center.z - MineDisplayBlueprints.center(piston, phase).z) <
                        (plate.size.z + piston.size.z) / 2
                    (xOverlap && zOverlap) shouldBe true
                    (plateBottom - crownTop >= .02f) shouldBe true
                }
            }
            // Preserve the authored 2 cm physical clearance under the fixed nozzle tip.
            (tipBottom - maxCrownY >= .02f) shouldBe true
        }
    }

    test("the service cutaway exposes the valve faces and nozzles above unobstructed pistons") {
        val parts = MineDieselGeneratorModel.model()
        val targets = parts.filter { it.motion.startsWith("diesel_injector_tip_") ||
            (it.motion.startsWith("diesel_valve_") && it.center.y < 5.1f) }
        targets.size shouldBe 18
        val fixed = parts.filter { !it.moving && it.angle == 0f }
        for (target in targets) {
            // A horizontal sightline from the +X service aisle must reach the actual part.
            fixed.none { obstacle -> obstacle !== target &&
                obstacle.center.x + obstacle.size.x / 2 > target.center.x + target.size.x / 2 &&
                abs(obstacle.center.y - target.center.y) < obstacle.size.y / 2 &&
                abs(obstacle.center.z - target.center.z) < obstacle.size.z / 2
            } shouldBe true
        }
        val liners = fixed.filter { it.material == Material.POLISHED_BASALT && it.size.y > 1.5f &&
            it.size.x < .1f && it.size.z < .1f }
        liners.size shouldBe 36
        parts.filter { it.motion.startsWith("diesel_piston_") }.forEach { piston ->
            // Pistons translate only vertically; their entire XZ footprint must clear the barrel.
            val center = MineDisplayBlueprints.center(piston, 0f)
            liners.none { liner -> abs(liner.center.x - center.x) < (liner.size.x + piston.size.x) / 2 &&
                abs(liner.center.z - center.z) < (liner.size.z + piston.size.z) / 2
            } shouldBe true
        }
    }

    test("valve springs keep their lower seat and compress beneath the moving retainer") {
        val parts = MineDieselGeneratorModel.model()
        val plateSize = Vector3f(.24f, .07f, .24f)
        for (cylinder in 0..5) for (inlet in listOf(false, true)) {
            val type = if (inlet) "inlet" else "exhaust"
            val valveMotion = "diesel_valve_${type}_$cylinder"
            val springMotion = "diesel_spring_${type}_$cylinder"
            val retainer = parts.single {
                it.motion == valveMotion && it.size == plateSize && it.center.y > 5.8f
            }
            val seat = parts.single {
                it.motion == "fixed" && it.material == Material.IRON_BLOCK &&
                    it.center.z == retainer.center.z && it.center.y < 5.8f &&
                    it.size == Vector3f(.24f, .06f, .24f)
            }
            val spring = parts.filter { it.motion == springMotion }
            spring.size shouldBe 16
            val lowerTurn = spring.minBy { it.center.y }
            val upperTurn = spring.maxBy { it.center.y }
            val peak = -2 * MineDieselGeneratorMotion.camAngle(cylinder, inlet)
            val closed = peak + (2 * PI).toFloat()

            val seatTop = seat.center.y + seat.size.y / 2
            val closedLowerGap = verticalBounds(lowerTurn, closed).first - seatTop
            val peakLowerGap = verticalBounds(lowerTurn, peak).first - seatTop
            (closedLowerGap >= 0f && peakLowerGap >= 0f) shouldBe true
            (abs(peakLowerGap - closedLowerGap) < .01f) shouldBe true

            val closedUpperGap = verticalBounds(retainer, closed).first - verticalBounds(upperTurn, closed).second
            val peakUpperGap = verticalBounds(retainer, peak).first - verticalBounds(upperTurn, peak).second
            (peakUpperGap >= 0f) shouldBe true
            (peakUpperGap < closedUpperGap) shouldBe true
        }
    }
})
