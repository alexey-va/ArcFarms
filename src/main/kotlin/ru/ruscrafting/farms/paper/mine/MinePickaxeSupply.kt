package ru.ruscrafting.farms.paper.mine

import org.bukkit.Material
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems

/** Keeps one temporary work pickaxe on every active miner, mirroring farm supply replenishment. */
internal class MinePickaxeSupply(
    private val registry: MineRuntimeRegistry,
    private val items: WorksiteServiceItems?,
    private val locale: ArcFarmsLocale?,
    private val audience: WorksiteAudiencePort,
) {
    fun ensure(runtime: MineRuntime, player: Player): Boolean {
        val serviceItems = items ?: return true
        val expected = identity(runtime)
        val held = player.inventory.storageContents.toList() + player.inventory.itemInOffHand + player.itemOnCursor
        if (held.any { serviceItems.identity(it) == expected }) return true
        held.mapNotNull(serviceItems::identity)
            .filter { it.activity == ActivityKind.MINE && it.zoneId == runtime.settings.id && it.role.value == ROLE }
            .distinct()
            .forEach { stale -> while (serviceItems.consume(player, stale)) Unit }
        val name = locale?.render(MessageKey.MINE_SERVICE_PICKAXE, player)
            ?: net.kyori.adventure.text.Component.translatable("item.minecraft.iron_pickaxe")
        if (serviceItems.issueTool(player, expected, Material.IRON_PICKAXE, name) != null) return true
        audience.sendActionBar(player, MessageKey.MINE_PICKAXE_INVENTORY_FULL)
        return false
    }

    fun isActive(identity: ServiceItemIdentity): Boolean {
        if (identity.activity != ActivityKind.MINE || identity.role.value != ROLE || identity.itemId != ITEM_ID) return false
        val runtime = registry.byId(identity.zoneId) ?: return false
        return runtime.state.sequence == identity.sequence
    }

    fun identity(runtime: MineRuntime) = ServiceItemIdentity(
        ActivityKind.MINE,
        runtime.settings.id,
        runtime.state.sequence,
        0L,
        ObjectiveTargetRole(ROLE),
        ITEM_ID,
    )

    private companion object {
        const val ROLE = "mine_pickaxe"
        const val ITEM_ID = "work_pickaxe"
    }
}
