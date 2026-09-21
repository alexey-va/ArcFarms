package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.util.Vector

class WorksiteWaterJetTest : FunSpec({
    test("spray hit detection is forward, radius bounded and nearest first") {
        val start = Location(null, 0.0, 0.0, 0.0)
        val targets = listOf(
            "far" to Location(null, 0.0, 0.0, 3.0),
            "near" to Location(null, 0.0, 0.0, 1.0),
            "side" to Location(null, .6, 0.0, 1.5),
            "behind" to Location(null, 0.0, 0.0, -1.0),
            "outside" to Location(null, 1.0, 0.0, 1.0),
        )

        WorksiteWaterJet.hitTargets(targets, start, Vector(0.0, 0.0, 1.0), 3.1, .65) shouldContainExactly
            listOf("near", "side", "far")
    }

    test("invalid rays and ranges are inert") {
        val start = Location(null, 0.0, 0.0, 0.0)
        val targets = listOf("target" to Location(null, 0.0, 0.0, 1.0))

        WorksiteWaterJet.hitTargets(targets, start, Vector(), 2.0, .5) shouldBe emptyList()
        WorksiteWaterJet.hitTargets(targets, start, Vector(0.0, 0.0, 1.0), -1.0, .5) shouldBe emptyList()
        WorksiteWaterJet.hitTargets(targets, start, Vector(0.0, 0.0, 1.0), 2.0, -1.0) shouldBe emptyList()
        WorksiteWaterJet.hitTargets(targets, start, Vector(0.0, 0.0, 1.0), Double.NaN, .5) shouldBe emptyList()
    }

    test("particle rendering handles zero, tiny and capped sample ranges") {
        val world = mockk<World>(relaxed = true)
        val start = Location(world, 0.0, 64.0, 0.0)
        WorksiteWaterJet.renderJet(start, Vector(0.0, 0.0, 1.0), 0.0, .2, 2)
        WorksiteWaterJet.renderJet(start, Vector(0.0, 0.0, 1.0), .000001, .000000001, 2)
        WorksiteWaterJet.renderJet(start, Vector(0.0, 0.0, 1.0), 100.0, .000000001, 64)

        // One zero-length center plus two side streams, a bounded 256-sample
        // pass with two side streams, and a second pass capped at 32 side
        // streams. The exact count guards both loop bounds without depending
        // on a real world.
        verify(exactly = 4_867) {
            world.spawnParticle(
                Particle.SPLASH,
                any<Location>(),
                any<Int>(),
                any<Double>(),
                any<Double>(),
                any<Double>(),
                any<Double>(),
            )
        }
    }
})
