package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.abs

class MineDisplayBlueprintsTest : FunSpec({
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
