package ru.ruscrafting.farms.paper.farm.incident.route

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.FarmNightShiftController

class FarmFoodDeliveryNightMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.addSimpleWorld("farm")
        world.fullTime = 54_000L
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true)
    }
    afterEach { paper.close() }

    test("native effective relative player clock remains unverified with MockBukkit4.116.3") {
        throw org.opentest4j.TestAbortedException(
            "PlayerMock.getPlayerTime returns its stored offset, not the effective world-relative time; native client required",
        )
    }

    test("delivery freezes the exact world without players and departure cannot turn it into day") {
        val plugin = paper.createSimplePlugin("DeliveryWorldNight")
        val night = FarmFoodDeliveryNight(plugin)
        val other = paper.addSimpleWorld("other")
        other.fullTime = 9_000L
        val visitor = paper.addPlayer("Visitor")
        visitor.teleport(Location(world, 0.5, 65.0, 0.5))

        night.hold("farm", world, 18_000L)
        world.time shouldBe 18_000L
        world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) shouldBe false
        // MockBukkit4.116.3 getPlayerTime returns only its stored offset. Verify world authority and relative policy.
        visitor.isPlayerTimeRelative shouldBe true

        visitor.teleport(Location(other, 0.5, 65.0, 0.5))
        FarmNightShiftController(plugin).clear(visitor)
        night.hold("farm", world, 18_000L)
        world.time shouldBe 18_000L
        other.fullTime shouldBe 9_000L
        night.release("farm")
        world.fullTime shouldBe 54_000L
        world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) shouldBe true
    }

    test("the last delivery zone restores the original clock once and repeated claims retain the baseline") {
        val night = FarmFoodDeliveryNight(paper.createSimplePlugin("SharedDeliveryNight"))
        night.hold("first", world, 18_000L)
        night.hold("second", world, 18_000L)
        world.time = 1_000L
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true)
        night.hold("second", world, 18_000L)
        world.time shouldBe 18_000L
        world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) shouldBe false
        night.release("first")
        world.time shouldBe 18_000L
        night.release("second")
        world.fullTime shouldBe 54_000L
        world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) shouldBe true
        world.fullTime = 70_000L
        night.release("second")
        world.fullTime shouldBe 70_000L
    }

    test("a restarted delivery retains its first recovery baseline and preserves an originally frozen world") {
        val plugin = paper.createSimplePlugin("RestartedDeliveryNight")
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
        FarmFoodDeliveryNight(plugin).hold("farm", world, 18_000L)

        val replacement = FarmFoodDeliveryNight(plugin)
        replacement.hold("farm", world, 18_000L)
        replacement.release("farm")
        world.fullTime shouldBe 54_000L
        world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) shouldBe false
    }

    test("startup cleanup restores only its persisted clock and removes the recovery record after restore") {
        val plugin = paper.createSimplePlugin("RecoveredDeliveryNight")
        val key = NamespacedKey(plugin, "farm_food_delivery_night_v1")
        val other = paper.addSimpleWorld("unrelated")
        other.fullTime = 83_000L
        other.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
        FarmFoodDeliveryNight(plugin).hold("farm", world, 18_000L)
        world.persistentDataContainer.has(key, PersistentDataType.LONG_ARRAY) shouldBe true

        FarmFoodDeliveryNight(plugin).cleanup()
        world.fullTime shouldBe 54_000L
        world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) shouldBe true
        world.persistentDataContainer.has(key, PersistentDataType.LONG_ARRAY) shouldBe false
        other.fullTime shouldBe 83_000L
        other.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) shouldBe false
    }

    test("malformed recovery remains recorded and does not mutate its world") {
        val plugin = paper.createSimplePlugin("InvalidDeliveryNight")
        val key = NamespacedKey(plugin, "farm_food_delivery_night_v1")
        world.persistentDataContainer.set(key, PersistentDataType.LONG_ARRAY, longArrayOf(6_000L))
        shouldThrow<IllegalStateException> { FarmFoodDeliveryNight(plugin).cleanup() }
        world.fullTime shouldBe 54_000L
        world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) shouldBe true
        world.persistentDataContainer.has(key, PersistentDataType.LONG_ARRAY) shouldBe true
    }

    test("a rejected last release keeps ownership until a later confirmed restore") {
        val plugin = paper.createSimplePlugin("RetriedDeliveryNight")
        val key = NamespacedKey(plugin, "farm_food_delivery_night_v1")
        val night = FarmFoodDeliveryNight(plugin)
        night.hold("farm", world, 18_000L)
        world.persistentDataContainer.set(key, PersistentDataType.LONG_ARRAY, longArrayOf(6_000L))

        shouldThrow<IllegalStateException> { night.release("farm") }
        world.time shouldBe 18_000L
        world.persistentDataContainer.has(key, PersistentDataType.LONG_ARRAY) shouldBe true

        world.persistentDataContainer.set(key, PersistentDataType.LONG_ARRAY, longArrayOf(54_000L, 1L))
        night.release("farm")
        world.fullTime shouldBe 54_000L
        world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) shouldBe true
        world.persistentDataContainer.has(key, PersistentDataType.LONG_ARRAY) shouldBe false
    }

    test("claim retires only the old delivery client clock before player boundary cleanup") {
        val plugin = paper.createSimplePlugin("DeliveryClockMigration")
        val legacy = FarmNightShiftController(plugin)
        val driver = paper.addPlayer("LegacyDriver")
        val observer = paper.addPlayer("OtherEvent")
        driver.teleport(Location(world, 0.5, 65.0, 0.5))
        observer.teleport(Location(world, 2.5, 65.0, 0.5))
        legacy.syncFixedAmbientTime("food:farm", listOf(driver), 1_000L, 18)
        legacy.syncFixedAmbientTime("frost:farm", listOf(observer), 13_000L, 18)

        FarmFoodDeliveryNight(plugin).hold("farm", world, 18_000L)
        legacy.resetAmbientTime("food:farm")
        legacy.clear(driver)
        repeat(40) { legacy.updatePlayerTimes() }
        driver.isPlayerTimeRelative shouldBe true
        observer.playerTime shouldBe 13_000L
        world.time shouldBe 18_000L
    }

    test("failed cleanup retains the owner so release can finish restoration") {
        val plugin = paper.createSimplePlugin("CleanupDeliveryNight")
        val key = NamespacedKey(plugin, "farm_food_delivery_night_v1")
        val night = FarmFoodDeliveryNight(plugin)
        night.hold("farm", world, 18_000L)
        world.persistentDataContainer.set(key, PersistentDataType.LONG_ARRAY, longArrayOf(6_000L))
        shouldThrow<IllegalStateException> { night.cleanup() }
        night.hasOwner("farm") shouldBe true
        world.persistentDataContainer.set(key, PersistentDataType.LONG_ARRAY, longArrayOf(54_000L, 1L))
        night.release("farm")
        night.hasOwner("farm") shouldBe false
        world.fullTime shouldBe 54_000L
    }
})
