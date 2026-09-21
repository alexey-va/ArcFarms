package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.util.Vector
import kotlin.math.abs

class MineFactoryExperimentMotionTest : FunSpec({
    test("bounded aim intersects the deck and clamps the reachable area") {
        val bounds = MineFactoryExperimentMotion.Bounds(-1.0, 2.0, -2.0, 3.0)
        val hit = MineFactoryExperimentMotion.boundedAim(
            Vector(0.0, 4.0, 0.0),
            Vector(1.0, -1.0, 1.0),
            MineFactoryExperimentMotion.AimPlane(1.0, bounds),
        )

        hit shouldBe Vector(2.0, 1.0, 3.0)
    }

    test("bounded aim rejects parallel, backward and non-finite rays") {
        val plane = MineFactoryExperimentMotion.AimPlane(
            1.0,
            MineFactoryExperimentMotion.Bounds(-4.0, 4.0, -4.0, 4.0),
        )

        MineFactoryExperimentMotion.boundedAim(Vector(0.0, 2.0, 0.0), Vector(1.0, 0.0, 0.0), plane) shouldBe null
        MineFactoryExperimentMotion.boundedAim(Vector(0.0, 0.0, 0.0), Vector(0.0, -1.0, 0.0), plane) shouldBe null
        MineFactoryExperimentMotion.boundedAim(Vector(0.0, 2.0, 0.0), Vector(Double.NaN, -1.0, 0.0), plane) shouldBe null
    }

    test("smooth moves at the requested bound and snaps inside it") {
        val current = Vector(0.0, 0.0, 0.0)
        val target = Vector(3.0, 4.0, 0.0)

        val limited = MineFactoryExperimentMotion.smooth(current, target, 2.0)
        (abs(limited.length() - 2.0) < 0.000001) shouldBe true
        MineFactoryExperimentMotion.smooth(current, target, 5.0) shouldBe target
        MineFactoryExperimentMotion.smooth(current, target, 0.0) shouldBe current
    }

    test("pry and timed phases are bounded at both ends") {
        MineFactoryExperimentMotion.pryProgress(0) shouldBe 0.0
        MineFactoryExperimentMotion.pryProgress(2) shouldBe (2.0 / 3.0)
        MineFactoryExperimentMotion.pryProgress(99) shouldBe 1.0
        MineFactoryExperimentMotion.pryWobble(1) shouldNotBe MineFactoryExperimentMotion.pryWobble(2)

        MineFactoryExperimentMotion.phase(1_000L, 500L, 1_000L) shouldBe 0.0
        MineFactoryExperimentMotion.phase(1_000L, 1_500L, 1_000L) shouldBe .5
        MineFactoryExperimentMotion.phase(1_000L, 4_000L, 1_000L) shouldBe 1.0
    }
})
