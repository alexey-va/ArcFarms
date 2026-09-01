package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import java.util.UUID

class WorksiteServiceItemControllerMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var player: PlayerMock
    lateinit var owner: RecordingServiceItemOwner
    lateinit var controller: WorksiteServiceItemController
    val identity = ServiceItemIdentity(
        activity = ActivityKind.LUMBER,
        zoneId = "sawmill",
        sequence = 4L,
        objectiveNonce = 2L,
        role = ObjectiveTargetRole("belt"),
        itemId = "belt-1",
    )

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        player = paper.server.addPlayer("Worker")
        owner = RecordingServiceItemOwner(identity.sequence, identity.objectiveNonce)
        controller = WorksiteServiceItemController(paper.createSimplePlugin("WorksiteItemTest"), owner)
    }

    afterEach { paper.close() }

    test("service items cannot enter external storage and release once on zone exit") {
        val item = requireNotNull(controller.issue(player, identity, Material.IRON_NUGGET, Component.text("Drive belt")))
        controller.identity(item) shouldBe identity
        player.inventory.removeItem(item)
        player.openInventory(Bukkit.createInventory(null, 9, Component.text("Chest")))
        player.setItemOnCursor(item)
        val externalClick = InventoryClickEvent(
            player.openInventory,
            InventoryType.SlotType.CONTAINER,
            0,
            ClickType.LEFT,
            InventoryAction.PLACE_ALL,
        )

        controller.guardInventory(externalClick) shouldBe true
        externalClick.isCancelled shouldBe true
        controller.guardDrop(item) shouldBe true

        val safety = WorksiteParticipantSafety(controller, emptyList())
        val report = safety.release(player, WorksitePlayerReleaseReason.ZONE_EXIT)

        report.removedItems shouldBe 1
        controller.isServiceItem(player.itemOnCursor) shouldBe false
        owner.released.map { it.identity } shouldContainExactly listOf(identity)
        owner.released.single().reason shouldBe WorksitePlayerReleaseReason.ZONE_EXIT

        safety.release(player, WorksitePlayerReleaseReason.ZONE_EXIT).removedItems shouldBe 0
        owner.released.map { it.identity } shouldContainExactly listOf(identity)
    }

    test("a stale sequence item is removed on join without advancing progress") {
        val item = requireNotNull(controller.issue(player, identity, Material.RAIL, Component.text("Rail kit")))
        player.inventory.contains(item) shouldBe true
        owner.sequence = identity.sequence + 1

        val removed = controller.cleanupPlayer(player, WorksitePlayerReleaseReason.JOIN_STALE)

        removed shouldBe 1
        player.inventory.storageContents.filterNotNull().none(controller::isServiceItem) shouldBe true
        owner.progress shouldBe 0
        owner.released.single().identity shouldBe identity
    }

    test("valid delivery consumes the exact item without treating cleanup as progress") {
        val first = requireNotNull(controller.issue(player, identity, Material.RAIL, Component.text("Rail kit")))
        val secondIdentity = identity.copy(itemId = "belt-2")
        requireNotNull(controller.issue(player, secondIdentity, Material.RAIL, Component.text("Rail kit")))

        controller.consume(player, identity) shouldBe true

        player.inventory.storageContents.filterNotNull().mapNotNull(controller::identity) shouldContainExactly listOf(secondIdentity)
        owner.released shouldBe emptyList()
        owner.progress shouldBe 0
        controller.isServiceItem(first) shouldBe true
    }

    test("service item appearance applies its model and explicitly disables italic text") {
        val model = NamespacedKey("voxelspawns_megaflintlocks", "vs_rifle_double")
        val item = requireNotNull(
            controller.issue(player, identity, Material.CROSSBOW, Component.text("Machine gun"), 2_100_103, model),
        )

        item.itemMeta.displayName()?.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        @Suppress("DEPRECATION")
        item.itemMeta.customModelData shouldBe 2_100_103
        // MockBukkit 4.84 does not retain Paper's item_model component; the real API call is compile-checked above.
    }
})

private class RecordingServiceItemOwner(
    var sequence: Long,
    var objectiveNonce: Long,
) : WorksiteServiceItemOwner {
    data class Release(val playerId: UUID, val identity: ServiceItemIdentity, val reason: WorksitePlayerReleaseReason)

    val released = mutableListOf<Release>()
    var progress: Int = 0

    override fun isActive(identity: ServiceItemIdentity): Boolean =
        identity.sequence == sequence && identity.objectiveNonce == objectiveNonce

    override fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        released += Release(playerId, identity, reason)
    }
}
