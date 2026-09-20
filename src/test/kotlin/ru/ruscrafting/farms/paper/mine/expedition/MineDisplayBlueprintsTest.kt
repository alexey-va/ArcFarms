package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.BlockDisplay
import org.bukkit.util.Transformation
import org.joml.Vector3f
import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.abs

class MineDisplayBlueprintsTest : FunSpec({
    test("parked casting does not restart interpolation on identical poses") {
        var pose = Transformation(Vector3f(), Quaternionf(), Vector3f(1f), Quaternionf())
        val display = mockk<BlockDisplay>(relaxed = true)
        every { display.transformation } answers { pose }
        every { display.transformation = any() } answers { pose = firstArg() }
        val desired = Transformation(Vector3f(-.8f, -.5f, -.8f), Quaternionf(), Vector3f(1.6f, 1f, 1.6f), Quaternionf())
        repeat(40) { MineDisplayPose.apply(display, desired) }
        verify(exactly = 1) { display.interpolationDelay = 0 }
        verify(exactly = 1) { display.transformation = any() }
        MineDisplayPose.apply(display, Transformation(Vector3f(), Quaternionf(), Vector3f(), Quaternionf()))
        verify(exactly = 2) { display.interpolationDelay = 0 }
    }
    test("every authored model is free of coplanar overlap throughout sampled motion") {
        MineDisplayModelValidation.validate() shouldBe emptyList()
    }
    test("machine bodies use moderate block light while active lamps remain legible") {
        val body = MineDisplayLighting.brightness(org.bukkit.Material.WEATHERED_CUT_COPPER)
        body.blockLight shouldBe 11
        body.skyLight shouldBe 0
        MineDisplayLighting.brightness(org.bukkit.Material.SEA_LANTERN).blockLight shouldBe 14
        MineDisplayLighting.brightness(org.bukkit.Material.LIME_CONCRETE, true).blockLight shouldBe 14
        MineDisplayLighting.brightness(org.bukkit.Material.GRAY_CONCRETE).blockLight shouldBe 11
    }
    test("press moves the ram one block vertically and returns it without moving its frame") {
        val parts = MineDisplayBlueprints.model("assembly_bench")
        parts.count { it.motion == "press" } shouldBe 2
        parts.forEach { part ->
            val rest = MineDisplayBlueprints.center(part, 0f)
            val bottom = MineDisplayBlueprints.center(part, PI.toFloat())
            val returned = MineDisplayBlueprints.center(part, (2 * PI).toFloat())
            (abs(rest.y - bottom.y - if (part.moving) 1f else 0f) < .0001f) shouldBe true
            rest.x shouldBe bottom.x
            rest.z shouldBe bottom.z
            (returned.distance(rest) < .0001f) shouldBe true
            MineDisplayBlueprints.rotation(part, PI.toFloat()) shouldBe MineDisplayBlueprints.rotation(part, 0f)
        }
    }
    test("crane levers tilt around their base while the console stays fixed") {
        val parts = MineDisplayBlueprints.model("crane_console")
        parts.filter { it.moving }.forEach { part ->
            (abs(MineDisplayBlueprints.center(part, PI.toFloat()).distance(part.pivot) -
                part.center.distance(part.pivot)) < .0001f) shouldBe true
            (MineDisplayBlueprints.center(part, (2 * PI).toFloat()).distance(part.center) < .0001f) shouldBe true
        }
        parts.filterNot { it.moving }.forEach { part ->
            MineDisplayBlueprints.center(part, PI.toFloat()) shouldBe part.center
        }
    }
})
