package ru.ruscrafting.farms.paper.platform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Horse
import org.bukkit.entity.TextDisplay
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmBlockPlatform
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmChunkLeaseManager
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmEntityPlatform

class FarmPlatformPortsMockBukkitTest : FunSpec({
    test("test entity adapter preserves passenger and display semantics") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("farm")
            val player = paper.server.addPlayer()
            val horse = world.spawn(Location(world, 2.5, 65.0, 2.5), Horse::class.java)
            horse.addPassenger(player) shouldBe true

            MockBukkitFarmEntityPlatform.ejectPassengers(horse) shouldBe true
            horse.passengers shouldBe emptyList()
            player.vehicle shouldBe null

            val display = world.spawn(Location(world, 3.5, 66.0, 3.5), TextDisplay::class.java)
            MockBukkitFarmEntityPlatform.configureTextDisplay(
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
            MockBukkitFarmBlockPlatform.isPassable(air) shouldBe true
            MockBukkitFarmBlockPlatform.isPassable(stone) shouldBe false
            MockBukkitFarmBlockPlatform.createBlockData("minecraft:wheat[age=3]").material shouldBe Material.WHEAT

            val leases = MockBukkitFarmChunkLeaseManager()
            val chunk = world.getChunkAt(0, 0)
            leases.retain(chunk) shouldBe true
            leases.retain(chunk) shouldBe false
            leases.retainedCount() shouldBe 1
            leases.release(chunk) shouldBe true
            leases.retainedCount() shouldBe 0
        }
    }
})
