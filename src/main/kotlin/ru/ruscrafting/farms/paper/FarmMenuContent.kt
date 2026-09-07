package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.menu.MenuRegionId
import ru.arc.paper.menu.PaperMenuSession

/** One set of business actions, shared by the native dialog and inventory presentation. */
data class FarmMenuContent(
    val title: Component,
    val background: ItemStack? = null,
    val elements: Map<MenuElementId, FarmMenuEntry> = emptyMap(),
    val regions: Map<MenuRegionId, List<FarmMenuEntry>> = emptyMap(),
)

fun interface FarmMenuClickHandler { fun handle(context: FarmMenuClickContext) }
data class FarmMenuEntry(
    val item: ItemStack,
    val enabled: Boolean = true,
    val acceptedClicks: Set<ClickType> = setOf(ClickType.LEFT),
    val onClick: FarmMenuClickHandler = FarmMenuClickHandler {},
    val selected: Boolean = false,
)
data class FarmMenuClickContext(val player: Player, val session: FarmMenuSession, val slot: Int)

class FarmMenuSession internal constructor(
    val player: Player,
    val menuId: MenuId,
    internal val platform: ArcFarmsMenuPlatform,
    internal val content: () -> FarmMenuContent,
    internal val reopen: () -> Unit,
) {
    internal var delegate: PaperMenuSession? = null
    internal var revision = 0L
    internal var pending = false
    internal var detailSlot: Int? = null
    internal var showInformation = false
    internal val feedback = mutableMapOf<Int, ItemStack>()
    val inventory: Inventory? get() = delegate?.inventory
    fun requestRefresh() { feedback.clear(); platform.refresh(this) }
    fun feedback(slot: Int, item: ItemStack) {
        feedback[slot] = item
        delegate?.inventory?.setItem(slot, item)
        if (delegate == null) platform.refresh(this)
    }
    fun close() { if (platform.session(player) === this) platform.close(player) }
}

/** Paper's native dialog display is the only boundary replaced by MockBukkit tests. */
interface FarmDialogDisplay : AutoCloseable {
    fun show(player: Player, screen: ru.arc.paper.menu.PaperDialogScreen)
    fun show(
        player: Player,
        screen: ru.arc.paper.menu.PaperDialogScreen,
        reopen: (() -> Unit)?,
        onDismiss: () -> Unit,
        closeOnEscape: Boolean,
    ) = show(player, screen)
    fun beginFlow(player: Player) = Unit
    fun close(player: Player)
    override fun close()
}
