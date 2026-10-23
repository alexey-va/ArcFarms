package ru.ruscrafting.farms.paper.mine.incident.gas

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.sqrt

class MineGasCloudGeometryTest : FunSpec({
    test("rolling gas has distinct height layers remains in its hazard footprint and moves continuously") {
        for (now in 0L..10_000L step 250) {
            val cloud = MineGasCloudGeometry.sample(now)
            cloud.size shouldBe 12
            cloud.map { it.color }.distinct().size shouldBe 3
            cloud.count { it.wisps } shouldBe 4
            cloud.all { sqrt(it.x * it.x + it.z * it.z) + it.spread <= 4.5 && it.y in .15..2.15 } shouldBe true
            cloud.zip(MineGasCloudGeometry.sample(now + 250)).all { (a, b) ->
                sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)) < .3
            } shouldBe true
        }
    }
})
