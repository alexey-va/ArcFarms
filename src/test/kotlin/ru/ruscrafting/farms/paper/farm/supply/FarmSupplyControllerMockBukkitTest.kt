package ru.ruscrafting.farms.paper.farm.supply

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmSupplySettings
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.CountingFarmEntityLookup
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.arc.paper.testing.MockBukkitTestRuntime

class FarmSupplyControllerMockBukkitTest : FunSpec({
    lateinit var server: ServerMock
    lateinit var world: WorldMock
    lateinit var player: PlayerMock
    lateinit var paper: MockBukkitTestRuntime
    lateinit var plugin: Plugin

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        server = paper.server
        plugin = paper.createSimplePlugin("FarmSupplyTest")
        world = server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
        player = server.addPlayer("Worker")
        player.teleport(world.spawnLocation)
    }

    afterEach {
        paper.close()
    }

    test("service item is replaced instead of duplicated and is removed at the farm boundary") {
        val controller = controller(plugin)
        val runtime = runtime(world)

        controller.give(runtime, FarmSupplyKind.TOOL, player) shouldBe true
        controller.give(runtime, FarmSupplyKind.TOOL, player) shouldBe true

        player.inventory.storageContents.filterNotNull().filter(controller::isServiceItem) shouldHaveSize 1
        controller.removeServiceItems(player, runtime.settings.id, "left_zone")
        player.inventory.storageContents.filterNotNull().filter(controller::isServiceItem) shouldHaveSize 0
    }

    test("loaded supply scene converges after controller restart without duplicate entities") {
        val runtime = runtime(world)
        val points = supplyPoints(world)
        val first = controller(plugin)

        first.ensure(runtime, points::getValue)
        val firstOwned = world.entities.filter(first::owns)
        firstOwned shouldHaveSize 9
        firstOwned.map { it.uniqueId }.distinct() shouldHaveSize 9

        val afterRestart = controller(plugin)
        afterRestart.ensure(runtime, points::getValue)
        val reconciled = world.entities.filter(afterRestart::owns)
        reconciled shouldHaveSize 9
        reconciled.map { it.uniqueId }.distinct() shouldHaveSize 9
        reconciled.count { afterRestart.interaction(it)?.zoneId == runtime.settings.id } shouldBe 9
    }

    test("cleanup removes every loaded supply entity and every online tagged item") {
        val controller = controller(plugin)
        val runtime = runtime(world)
        controller.ensure(runtime, supplyPoints(world)::getValue)
        controller.give(runtime, FarmSupplyKind.WATER, player)
        world.entities.count(controller::owns) shouldBeGreaterThan 0

        controller.cleanup("reload")

        world.entities.count(controller::owns) shouldBe 0
        player.inventory.storageContents.filterNotNull().count(controller::isServiceItem) shouldBe 0
    }

    test("supply scene scans existing entities once and then uses tracked UUIDs") {
        val lookup = CountingFarmEntityLookup()
        val controller = controller(plugin, lookup)
        val runtime = runtime(world)

        repeat(20) { controller.ensure(runtime, supplyPoints(world)::getValue) }

        lookup.worldScans shouldBe 1
        lookup.globalScans shouldBe 0
    }
})

private fun controller(plugin: Plugin, entityLookup: CountingFarmEntityLookup = CountingFarmEntityLookup()): FarmSupplyController {
    val config = mockk<ArcFarmsConfig> { every { sounds } returns false }
    val locale = mockk<ArcFarmsLocale>(relaxed = true) {
        every { render(any(), any(), any()) } answers { Component.text(firstArg<MessageKey>().path) }
    }
    return FarmSupplyController(
        plugin = plugin,
        locale = locale,
        debug = ArcFarmsDebug({ false }) {},
        settings = { config },
        entityLookup = entityLookup,
    )
}

private fun runtime(world: WorldMock): FarmRuntime {
    val supplies = mockk<FarmSupplySettings> {
        every { toolMaterial } returns "IRON_HOE"
        every { seedAmount } returns 16
    }
    val settings = mockk<FarmZoneSettings> {
        every { id } returns "communal_farm"
        every { this@mockk.supplies } returns supplies
        every { displayViewRange } returns 1.0f
    }
    return FarmRuntime(
        settings = settings,
        region = CuboidActivityRegion(world, "farm", CuboidBounds(-32, 0, -32, 32, 128, 32)),
        orders = emptyMap(),
        orderList = emptyList(),
        rules = mockk(relaxed = true),
        state = FarmShiftState(preparationCrop = Material.WHEAT.name),
    )
}

private fun supplyPoints(world: WorldMock): Map<FarmSupplyKind, FarmPointPosition> = mapOf(
    FarmSupplyKind.TOOL to FarmPointPosition(world.name, 1.5, 65.0, 1.5),
    FarmSupplyKind.SEEDS to FarmPointPosition(world.name, 4.5, 65.0, 1.5),
    FarmSupplyKind.WATER to FarmPointPosition(world.name, 7.5, 65.0, 1.5),
)
