package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.worksite.WorksiteCoherentNoise
import kotlin.math.abs

class WorksiteCoherentNoiseTest : FunSpec({
    test("cave field is bounded, replayable and changes between seeds") {
        val points = (-12..12).map { it / 3.0 }
        val first = points.map { WorksiteCoherentNoise.sample(17L, it, it * 0.7, -it * 0.3) }
        first shouldBe points.map { WorksiteCoherentNoise.sample(17L, it, it * 0.7, -it * 0.3) }
        first.all { it in -1.0..1.0 } shouldBe true
        (first != points.map { WorksiteCoherentNoise.sample(42L, it, it * 0.7, -it * 0.3) }) shouldBe true
    }

    test("adjacent samples stay continuous across positive and negative cell boundaries") {
        for (boundary in -4..4) {
            val before = WorksiteCoherentNoise.sample(91L, boundary - 0.0001, 0.3, -1.7)
            val after = WorksiteCoherentNoise.sample(91L, boundary + 0.0001, 0.3, -1.7)
            (abs(before - after) < 0.00001) shouldBe true
        }
    }
})
