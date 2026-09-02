package ru.ruscrafting.farms.paper.platform

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Horse
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.TextDisplay
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmBlockDataDecoder
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmBlockPassability
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmTextDisplays
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmVehiclePassengers
import ru.ruscrafting.farms.paper.fixtures.MockBukkitMoleBurrowChunkRetention

class FarmPlatformPortsMockBukkitTest : FunSpec({
    test("test entity adapter preserves passenger and display semantics") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("farm")
            val player = paper.server.addPlayer()
            val horse = world.spawn(Location(world, 2.5, 65.0, 2.5), Horse::class.java)
            horse.addPassenger(player) shouldBe true

            MockBukkitFarmVehiclePassengers.ejectAll(horse) shouldBe true
            horse.passengers shouldBe emptyList()
            player.vehicle shouldBe null

            val display = world.spawn(Location(world, 3.5, 66.0, 3.5), TextDisplay::class.java)
            MockBukkitFarmTextDisplays.render(
                display,
                Component.text("test"),
                FarmTextDisplayStyle(viewRange = 1.25f),
            )
            display.text() shouldBe Component.text("test")
            display.viewRange shouldBe 1.25f
            display.isPersistent shouldBe false
        }
    }

    test("test block and chunk adapters retain the gameplay-observable contract") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("farm")
            val air = world.getBlockAt(0, 65, 0)
            val stone = world.getBlockAt(0, 64, 0).apply { type = Material.STONE }
            val water = world.getBlockAt(1, 65, 0).apply { type = Material.WATER }
            MockBukkitFarmBlockPassability.isPassable(air) shouldBe true
            MockBukkitFarmBlockPassability.isPassable(stone) shouldBe false
            MockBukkitFarmBlockPassability.isPassable(water) shouldBe true
            MockBukkitFarmBlockDataDecoder.decode("minecraft:wheat[age=3]").asString shouldBe
                "minecraft:wheat[age=3]"
            val pointedDripstone = Material.POINTED_DRIPSTONE.createBlockData().asString
            MockBukkitFarmBlockDataDecoder.decode(pointedDripstone).material shouldBe Material.POINTED_DRIPSTONE
            shouldThrow<IllegalArgumentException> {
                MockBukkitFarmBlockDataDecoder.decode("minecraft:wheat[not_a_property=3]")
            }
            shouldThrow<IllegalArgumentException> {
                MockBukkitFarmBlockDataDecoder.decode("minecraft:pointed_dripstone[thickness=impossible]")
            }
            shouldThrow<IllegalArgumentException> {
                MockBukkitFarmBlockDataDecoder.decode(
                    "minecraft:pointed_dripstone[thickness=impossible,thickness=tip]",
                )
            }

            val leases = MockBukkitMoleBurrowChunkRetention()
            val chunk = world.getChunkAt(0, 0)
            val lease = leases.retain(chunk)
            leases.retainedCount() shouldBe 1
            lease.close()
            lease.close()
            leases.retainedCount() shouldBe 0
        }
    }

    test("production raid seat motion keeps the rider mounted and uses velocity instead of per-tick teleport") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("farm")
            val player = paper.server.addPlayer()
            val seat = world.spawn(Location(world, 2.5, 70.0, 2.5), ArmorStand::class.java)
            seat.addPassenger(player) shouldBe true
            val before = seat.location.clone()
            val target = before.clone().add(0.25, 0.05, -0.15)

            val leaderVelocity = org.bukkit.util.Vector(0.20, 0.01, -0.05)
            PaperFarmRaidSeatMotion.move(seat, target, leaderVelocity)

            player.vehicle shouldBe seat
            seat.location shouldBe before
            seat.velocity.x shouldBeGreaterThan leaderVelocity.x
            seat.velocity.length() shouldBeLessThan 0.28
        }
    }

    test("production raid seat never snaps a mounted player during same-world catch-up") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("farm")
            val player = paper.server.addPlayer()
            val seat = world.spawn(Location(world, 2.5, 70.0, 2.5), ArmorStand::class.java)
            seat.addPassenger(player) shouldBe true
            val before = seat.location.clone()
            val distantTarget = before.clone().add(20.0, 0.0, 0.0)

            PaperFarmRaidSeatMotion.move(seat, distantTarget, org.bukkit.util.Vector(0.30, 0.0, 0.0))

            player.vehicle shouldBe seat
            seat.location shouldBe before
            seat.velocity.length() shouldBeLessThan 0.55
        }
    }
})
