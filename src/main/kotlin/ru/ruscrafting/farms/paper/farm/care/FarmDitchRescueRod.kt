package ru.ruscrafting.farms.paper.farm.care

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.FishHook
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import java.util.UUID

/** Identity, issuance and retirement of the temporary ditch-rescue tool. */
internal class FarmDitchRescueRod(
    plugin: Plugin,
    private val serviceItems: WorksiteServiceItems,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
) {
    private val hookKey = NamespacedKey(plugin, "farm_rescue_hook")

    fun identity(runtime: FarmRuntime) = ServiceItemIdentity(
        ActivityKind.FARM, runtime.settings.id, runtime.state.sequence, runtime.state.placementSequence,
        ObjectiveTargetRole("farm_care"), ITEM_ID,
    )

    fun ensure(runtime: FarmRuntime) {
        val identity = identity(runtime)
        audience.players(runtime.region).filterNot(access::isAdminEditing).forEach { player ->
            if (!serviceItems.has(player, identity) && serviceItems.issueTool(
                    player, identity, Material.FISHING_ROD,
                    locale.render(MessageKey.FARM_CARE_ANIMAL_FISHING_ROD, player),
                ) == null && access.allowInteraction("farm-equipment-full:${player.uniqueId}", 3_000L)
            ) audience.sendChat(player, MessageKey.FARM_ACTION_INVENTORY_FULL)
        }
    }

    fun remove(player: Player, runtime: FarmRuntime) {
        val identity = identity(runtime)
        while (serviceItems.consume(player, identity)) Unit
    }

    fun markCast(event: PlayerFishEvent, runtimes: Collection<FarmRuntime>) {
        val hand = event.hand ?: return
        val identity = serviceItems.identity(event.player.inventory.getItem(hand)) ?: return
        if (isActive(identity, runtimes)) {
            event.hook.persistentDataContainer.set(hookKey, PersistentDataType.STRING, hookStamp(identity))
        }
    }

    fun ownsHook(hook: FishHook, runtime: FarmRuntime): Boolean =
        hook.persistentDataContainer.get(hookKey, PersistentDataType.STRING) == hookStamp(identity(runtime))

    private fun hookStamp(identity: ServiceItemIdentity): String =
        "${identity.zoneId}:${identity.sequence}:${identity.objectiveNonce}"

    fun isActive(identity: ServiceItemIdentity, runtimes: Collection<FarmRuntime>): Boolean {
        if (!owns(identity)) return false
        val runtime = runtimes.firstOrNull { it.settings.id == identity.zoneId } ?: return false
        return runtime.state.phase == FarmPhase.CARE && runtime.state.careType == FarmCareType.DITCH_RESCUE &&
            identity == identity(runtime)
    }

    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        if (owns(identity)) debug.event(
            "farm_rescue_rod_released", "zone" to identity.zoneId, "player" to playerId, "reason" to reason,
        )
    }

    private fun owns(identity: ServiceItemIdentity): Boolean = identity.activity == ActivityKind.FARM && identity.itemId == ITEM_ID

    private companion object {
        const val ITEM_ID = "ditch_rescue_rod"
    }
}
