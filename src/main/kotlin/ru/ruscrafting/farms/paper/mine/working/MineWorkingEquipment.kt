package ru.ruscrafting.farms.paper.mine.working

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.UUID

/** Uses the same leases and inventory guard as farm tools; nothing can leave as ordinary loot. */
internal class MineWorkingEquipment(
    private val registry: MineRuntimeRegistry,
    private val items: WorksiteServiceItems?,
    private val state: WorksiteStatePort,
    private val locale: ArcFarmsLocale?,
) {
    fun issue(runtime: MineRuntime, player: Player, role: String, material: Material, cargo: Boolean): Boolean {
        val incident = runtime.state.incident ?: return false
        val working = incident.working ?: return false
        val itemId = if (cargo) "${role}_${working.batch}" else "${role}_${player.uniqueId.toString().replace("-", "")}"
        val holder = incident.serviceLeases[itemId]
        if (holder != null && holder != player.uniqueId) return false
        val identity = identity(runtime, itemId, role)
        val reserved = holder == null
        if (reserved) {
            runtime.state = runtime.state.copy(incident = incident.copy(
                serviceLeases = incident.serviceLeases + (itemId to player.uniqueId),
            ))
        }
        if (items?.has(player, identity) == true) {
            if (reserved) state.persistAsync()
            return true
        }
        val name = locale?.renderPath("mine.working.item.$role", player) ?: Component.text(role)
        if (items?.issueTool(player, identity, material, name) == null) {
            if (reserved) {
                runtime.state = runtime.state.copy(incident = runtime.state.incident?.let { current ->
                    if (current.serviceLeases[itemId] == player.uniqueId) {
                        current.copy(serviceLeases = current.serviceLeases - itemId)
                    } else current
                })
            }
            player.sendActionBar(locale?.renderPath("mine.working.inventory-full", player) ?: Component.empty())
            return false
        }
        if (reserved) state.persistAsync()
        return true
    }

    fun has(runtime: MineRuntime, player: Player, role: String): Boolean =
        identityFor(runtime, player.uniqueId, role)?.let { items?.has(player, it) } == true

    fun consume(runtime: MineRuntime, player: Player, role: String): Boolean {
        val identity = identityFor(runtime, player.uniqueId, role) ?: return false
        if (items?.consume(player, identity) != true) return false
        release(player.uniqueId, identity, WorksitePlayerReleaseReason.OBJECTIVE_REPLACED)
        return true
    }

    fun isActive(identity: ServiceItemIdentity): Boolean {
        if (identity.activity != ActivityKind.MINE || !identity.role.value.startsWith(ROLE_PREFIX)) return false
        val runtime = registry.byId(identity.zoneId) ?: return false
        val incident = runtime.state.incident ?: return false
        return incident.working != null && runtime.state.sequence == identity.sequence &&
            incident.objectiveNonce == identity.objectiveNonce && identity.itemId in incident.serviceLeases
    }

    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        if (!identity.role.value.startsWith(ROLE_PREFIX)) return
        val runtime = registry.byId(identity.zoneId) ?: return
        val incident = runtime.state.incident ?: return
        if (runtime.state.sequence != identity.sequence || incident.objectiveNonce != identity.objectiveNonce ||
            incident.serviceLeases[identity.itemId] != playerId) return
        runtime.state = runtime.state.copy(incident = incident.copy(serviceLeases = incident.serviceLeases - identity.itemId))
        state.persistAsync()
    }

    fun clear(runtime: MineRuntime, playerId: UUID? = null) {
        val incident = runtime.state.incident ?: return
        val owned = incident.serviceLeases.filter { (id, holder) ->
            ROLES.any { id.startsWith("${it}_") } && (playerId == null || holder == playerId)
        }
        owned.forEach { (id, holder) ->
            val role = ROLES.first { id.startsWith("${it}_") }
            Bukkit.getPlayer(holder)?.let { items?.consume(it, identity(runtime, id, role)) }
        }
        if (owned.isNotEmpty()) {
            runtime.state = runtime.state.copy(incident = incident.copy(serviceLeases = incident.serviceLeases - owned.keys))
            state.persistAsync()
        }
    }

    private fun identityFor(runtime: MineRuntime, playerId: UUID, role: String): ServiceItemIdentity? =
        runtime.state.incident?.serviceLeases?.entries?.firstOrNull {
            it.value == playerId && it.key.startsWith("${role}_")
        }?.let { identity(runtime, it.key, role) }

    private fun identity(runtime: MineRuntime, itemId: String, role: String) = ServiceItemIdentity(
        ActivityKind.MINE, runtime.settings.id, runtime.state.sequence,
        requireNotNull(runtime.state.incident).objectiveNonce, ObjectiveTargetRole("$ROLE_PREFIX$role"), itemId,
    )

    private companion object {
        const val ROLE_PREFIX = "working_"
        val ROLES = listOf("supports", "rails", "ore", "billet", "rail_cassette")
    }
}
