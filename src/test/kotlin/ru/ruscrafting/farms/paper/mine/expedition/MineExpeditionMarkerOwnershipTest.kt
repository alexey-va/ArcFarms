package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Interaction
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry

class MineExpeditionMarkerOwnershipTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("expedition click routing ignores a same-key marker owned by another renderer") {
        val world = paper.server.addSimpleWorld("world")
        val plugin = paper.createSimplePlugin("MineExpeditionMarkerOwnership")
        val markerLocation = Location(world, 0.5, 64.0, 0.5)
        val hitbox = world.spawn(markerLocation, Interaction::class.java)
        hitbox.persistentDataContainer.set(
            NamespacedKey(plugin, "mine_expedition_control"),
            PersistentDataType.STRING,
            "working-return:zone-a/return-lift",
        )

        try {
            val player = paper.server.addPlayer("MarkerClicker")
            player.teleport(markerLocation)
            val event = PlayerInteractEntityEvent(player, hitbox, EquipmentSlot.HAND)

            controller(plugin).onInteractEntity(event) shouldBe false
            event.isCancelled shouldBe false
        } finally {
            hitbox.remove()
        }
    }

    test("expedition chunk cleanup leaves the separate working-return namespace alive") {
        val world = paper.server.addSimpleWorld("world")
        val plugin = paper.createSimplePlugin("MineWorkingReturnNamespace")
        val markerLocation = Location(world, 0.5, 64.0, 0.5)
        val hitbox = world.spawn(markerLocation, Interaction::class.java)
        hitbox.persistentDataContainer.set(
            NamespacedKey(plugin, "mine_working_return"),
            PersistentDataType.STRING,
            "working-return:zone-a/return-lift",
        )

        try {
            controller(plugin).onChunkLoad(world.getChunkAt(0, 0))
            hitbox.isValid shouldBe true
        } finally {
            hitbox.remove()
        }
    }
})

private fun controller(plugin: Plugin) = MineExpeditionController(
    plugin = plugin,
    registry = MineRuntimeRegistry(),
    world = mockk(relaxed = true),
    incidents = mockk(relaxed = true),
    travel = mockk(relaxed = true),
    surfacePoint = { null },
    access = mockk(relaxed = true),
    statePort = mockk(relaxed = true),
    locale = null,
    clock = { 0L },
)
