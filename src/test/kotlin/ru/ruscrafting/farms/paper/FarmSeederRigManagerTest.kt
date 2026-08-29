package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.Vector
import ru.arc.paper.testing.MockBukkitTestRuntime
import kotlin.math.hypot

class FarmSeederRigManagerTest : FunSpec({
    test("pig steering follows the moving formation smoothly without exceeding its speed cap") {
        val velocity = smoothSeederVelocity(
            current = Vector(0.1, 0.0, 0.0),
            offset = Vector(4.0, 0.0, 3.0),
            maxSpeed = 0.46,
        )

        (velocity.x > 0.0) shouldBe true
        (velocity.z > 0.0) shouldBe true
        (hypot(velocity.x, velocity.z) <= 0.46) shouldBe true
    }

    test("pig steering damps horizontal drift after reaching its formation slot") {
        val velocity = smoothSeederVelocity(
            current = Vector(0.4, 0.1, -0.2),
            offset = Vector(0.0, 0.0, 0.0),
            maxSpeed = 0.46,
        )

        velocity.x shouldBe (0.14 plusOrMinus 1.0e-9)
        velocity.y shouldBe (0.1 plusOrMinus 1.0e-9)
        velocity.z shouldBe (-0.07 plusOrMinus 1.0e-9)
    }

    test("pig steering caps inherited leash velocity") {
        val velocity = smoothSeederVelocity(
            current = Vector(4.0, 0.0, 4.0),
            offset = Vector(2.0, 0.0, 0.0),
            maxSpeed = 0.46,
        )

        (hypot(velocity.x, velocity.z) <= 0.46 + 1.0e-9) shouldBe true
    }

    test("seeder rig waits until the field release batch is complete") {
        shouldSpawnSeederRig(preparationReleased = false) shouldBe false
        shouldSpawnSeederRig(preparationReleased = true) shouldBe true
    }

    test("clicking a saddled seeder pig mounts that pig instead of redirecting to the horse") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("farm")
            world.getBlockAt(4, 64, 4).type = Material.STONE
            val plugin = paper.createSimplePlugin("SeederRigTest")
            val manager = FarmSeederRigManager(plugin)
            val rig = manager.spawn(
                location = Location(world, 4.5, 65.0, 4.5),
                pigCount = 2,
                leadDistance = 1.6,
                spacing = 1.1,
                horseSpeed = 0.2,
                pigSpeed = 0.3,
                labelText = Component.text("Seeder"),
                labelViewRange = 1f,
                mark = {},
                validPosition = { true },
            )
            val passenger = paper.addPlayer("Passenger")

            manager.mount(rig, rig.pigs.first(), passenger) shouldBe FarmSeederMountResult.MOUNTED
            rig.pigs.first().passengers.single() shouldBe passenger
            rig.horse.passengers shouldBe emptyList()
        }
    }
})
