package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs

class MineExpeditionMarkerGeometryTest : FunSpec({
    test("mounted console hitbox follows the lever after yaw and scale") {
        val parts = MineDisplayBlueprints.model("mounted_console")
        val lever = parts.filter(MineExpeditionMarkerGeometry::actionable)
        val bounds = requireNotNull(MineExpeditionMarkerGeometry.hitbox(parts, yaw = 90, scale = 2f))

        (lever.isNotEmpty()) shouldBe true
        val expected = Quaternionf().rotateY(Math.toRadians(90.0).toFloat())
            .transform(Vector3f(-.3f, 0f, .4f).mul(2f))
        (abs(bounds.center.x - expected.x) < .001f) shouldBe true
        (abs(bounds.center.z - expected.z) < .001f) shouldBe true
        // The frame is around y=1.15; a frame-inclusive box would pull this
        // center down. The control box remains centered on the elevated lever.
        (bounds.center.y > 2f) shouldBe true
        (bounds.width >= .8f) shouldBe true
        (bounds.height >= .8f) shouldBe true
    }

    test("pipe valve glow and hitbox use the wheel instead of its frame") {
        val parts = MineDisplayBlueprints.model("pipe_valve")
        val wheelIndex = parts.indexOfFirst(MineExpeditionMarkerGeometry::actionable)
        val frameIndex = parts.indexOfFirst { !MineExpeditionMarkerGeometry.actionable(it) }
        val wheelBounds = requireNotNull(MineExpeditionMarkerGeometry.hitbox(parts, yaw = 0, scale = 1f))
        val allBounds = requireNotNull(MineExpeditionMarkerGeometry.hitbox(parts.map { it.copy(moving = false) }, 0, 1f))

        (wheelIndex >= 0) shouldBe true
        (frameIndex >= 0) shouldBe true
        MineExpeditionMarkerGeometry.glows(parts, wheelIndex) shouldBe true
        MineExpeditionMarkerGeometry.glows(parts, frameIndex) shouldBe false
        (wheelBounds.width < allBounds.width) shouldBe true
        (wheelBounds.height < allBounds.height) shouldBe true
    }

    test("control hitbox keeps a minimum forgiving size") {
        val parts = MineDisplayBlueprints.model("mounted_console")
        val bounds = requireNotNull(MineExpeditionMarkerGeometry.hitbox(parts, yaw = 37, scale = .5f))

        (bounds.width >= .8f) shouldBe true
        (bounds.height >= .8f) shouldBe true
    }

    test("rotation at a full turn keeps the previous quaternion sign") {
        val previous = Quaternionf().rotateZ((2 * PI - .001).toFloat())
        val next = MineExpeditionMarkerGeometry.continuousRotation(Quaternionf(), previous)

        (next.dot(previous) > 0f) shouldBe true
        (next.transform(Vector3f(1f, 0f, 0f)).distance(Vector3f(1f, 0f, 0f)) < .001f) shouldBe true
    }
})
