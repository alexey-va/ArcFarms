package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.ruscrafting.farms.domain.mine.expedition.ExpeditionPoint
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionGenerator
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind
import kotlin.math.PI
import kotlin.math.floor

class MineDieselGeneratorPlacementTest : FunSpec({
    test("generator fits the existing factory floor and leaves all authored routes accessible") {
        for (seed in listOf(73L, 3900778703475868044L)) {
            val plan = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, seed, 3)
            val fixture = MineExpeditionFurnishings.fixtures(plan).single { it.id == "decor_diesel_generator" }
            val yaw = Quaternionf().rotateY(Math.toRadians(fixture.yaw.toDouble()).toFloat())
            // The visible service face points into the hall, away from the east wall.
            (yaw.transform(Vector3f(1f, 0f, 0f)).x < -.99f) shouldBe true
            val min = Vector3f(Float.POSITIVE_INFINITY)
            val max = Vector3f(Float.NEGATIVE_INFINITY)
            for (part in MineDisplayBlueprints.model(fixture.model)) for (step in 0..32) {
                val phase = (step * PI / 8).toFloat()
                val center = MineDisplayBlueprints.center(part, phase)
                val rotation = MineDisplayBlueprints.rotation(part, phase)
                for (x in listOf(-.5f, .5f)) for (y in listOf(-.5f, .5f)) for (z in listOf(-.5f, .5f)) {
                    val corner = rotation.transform(Vector3f(part.size).mul(x, y, z)).add(center)
                    yaw.transform(corner.mul(fixture.scale)).add(fixture.at.x + .5f, fixture.at.y.toFloat(), fixture.at.z + .5f)
                    min.min(corner); max.max(corner)
                }
            }
            (min.y >= 5f - .0001f) shouldBe true
            (max.y <= 12.5f + .0001f) shouldBe true
            // Every cell in the assembly's swept envelope must be air or invisible light.
            for (x in floor(min.x).toInt()..floor(max.x).toInt()) for (z in floor(min.z).toInt()..floor(max.z).toInt()) {
                (plan.blocks[ExpeditionPoint(x, 4, z)]?.let { it != "minecraft:air" } == true) shouldBe true
                for (y in floor(min.y + .0001f).toInt()..floor(max.y).toInt()) {
                    val block = plan.blocks[ExpeditionPoint(x, y, z)]
                    (block == "minecraft:air" || block?.startsWith("minecraft:light[") == true) shouldBe true
                }
            }
            plan.walkingRoutes.flatten().forEach { point ->
                // Include player width around the route's block-centred position.
                val outside = point.x + .8f < min.x || point.x + .2f > max.x ||
                    point.z + .8f < min.z || point.z + .2f > max.z
                outside shouldBe true
            }
        }
    }

    test("legacy factory rooms retain their original furnishings") {
        for (version in 1..2) {
            val plan = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73L, version)
            MineExpeditionFurnishings.fixtures(plan).none { it.id == "decor_diesel_generator" } shouldBe true
        }
    }
})
