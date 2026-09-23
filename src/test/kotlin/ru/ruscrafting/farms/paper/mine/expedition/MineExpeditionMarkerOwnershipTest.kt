package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Interaction
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry

class MineExpeditionMarkerOwnershipTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var scheduler: TestTaskScheduler

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        scheduler = TestTaskScheduler()
        Tasks.install(scheduler)
    }
    afterEach {
        try { paper.close() } finally { Tasks.reset() }
    }

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

    test("noninteractive block objectives leave the block attack path open") {
        val world = paper.server.addSimpleWorld("world")
        val plugin = paper.createSimplePlugin("MineExpeditionBreakMarker")
        // Packet-only visuals need no protocol transport in this physical-hitbox test.
        val visuals = mockk<PaperPacketDisplays>(relaxed = true)
        val markers = MineExpeditionMarkers(plugin)
        MineExpeditionMarkers::class.java.getDeclaredField("renderer").apply {
            isAccessible = true
            set(markers, visuals)
        }
        try {
            failOnUnsupportedMockBukkitOperation {
                markers.reconcile("scene:1", listOf(
                    MineExpeditionMarkers.Target(
                        id = "ore_break",
                        location = Location(world, 0.5, 64.0, 0.5),
                        material = Material.STONE,
                        label = Component.text("ore"),
                        block = true,
                        interactive = false,
                    ),
                ))

                val hitbox = world.entities.filterIsInstance<Interaction>().single()
                hitbox.interactionWidth shouldBe 0f
                hitbox.interactionHeight shouldBe 0f
            }
        } finally {
            markers.cleanup()
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
