package ru.ruscrafting.farms.paper.farm.presentation

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayStyle
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.UUID

internal data class FarmPortalStyle(
    val width: Float,
    val height: Float,
    val labelHeight: Double,
    val labelScale: Float,
    val activationSeconds: Int,
    val viewRange: Float,
)

/** Activity action. False from enter means no transfer occurred and the target is still loading. */
internal interface FarmPortalDestination {
    fun canEnter(player: Player): Boolean = true
    fun enter(player: Player): Boolean
}

/** Shared visual and countdown lifecycle for farm activity entry portals. */
internal class FarmActivityPortal(
    plugin: Plugin,
    private val locale: ArcFarmsLocale,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val tasks: WorksiteTaskPort,
    private val textDisplays: FarmTextDisplayRenderer,
    namespace: String,
    private val rolePrefix: String = "",
) {
    private val zoneKey = NamespacedKey(plugin, "${namespace}_zone")
    private val sequenceKey = NamespacedKey(plugin, "${namespace}_sequence")
    private val roleKey = NamespacedKey(plugin, "${namespace}_role")
    private val entries = mutableMapOf<String, PortalEntry>()
    private val pending = mutableMapOf<UUID, PendingEntry>()
    private var nextRenderTick = 0L

    fun ensure(
        runtime: FarmRuntime,
        point: FarmPointPosition,
        style: FarmPortalStyle,
        label: MessageKey,
        destination: FarmPortalDestination,
    ) {
        val current = entries[runtime.settings.id]
        if (!active(runtime) || point.world != runtime.region.world.name) {
            if (current != null) clear(runtime.settings.id)
            return
        }
        if (current != null && (current.sequence != runtime.state.sequence || current.incidentType != runtime.state.incidentType)) {
            clear(runtime.settings.id)
        }
        val world = Bukkit.getWorld(point.world) ?: return
        val at = Location(world, point.x, point.y, point.z, point.yaw, point.pitch)
        if (!world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4)) return
        val entry = entries.getOrPut(runtime.settings.id) {
            removeOwned(runtime.settings.id)
            PortalEntry(runtime, runtime.state.sequence, runtime.state.incidentType!!, style, destination)
        }
        entry.runtime = runtime
        entry.style = style
        entry.destination = destination
        val portal = (entry.portalId?.let(Bukkit::getEntity) as? Interaction)?.takeIf(Entity::isValid)
            ?: world.spawn(at, Interaction::class.java).also { entry.portalId = it.uniqueId }
        portal.teleport(at)
        portal.interactionWidth = style.width
        portal.interactionHeight = style.height
        portal.isResponsive = true
        portal.isPersistent = false
        mark(portal, runtime, "portal")

        val labelAt = at.clone().add(0.0, style.labelHeight, 0.0).apply { yaw = 0f; pitch = 0f }
        val display = (entry.labelId?.let(Bukkit::getEntity) as? TextDisplay)?.takeIf(Entity::isValid)
            ?: world.spawn(labelAt, TextDisplay::class.java).also { entry.labelId = it.uniqueId }
        display.teleport(labelAt)
        textDisplays.render(
            display,
            locale.render(label, Bukkit.getConsoleSender(), mapOf("seconds" to locale.text(style.activationSeconds))),
            FarmTextDisplayStyle(viewRange = style.viewRange),
        )
        val transform = display.transformation
        display.transformation = org.bukkit.util.Transformation(
            transform.translation,
            transform.leftRotation,
            org.joml.Vector3f(style.labelScale, style.labelScale, style.labelScale),
            transform.rightRotation,
        )
        mark(display, runtime, "portal_label")
    }

    fun update(particles: Boolean) {
        val tick = Bukkit.getCurrentTick().toLong()
        val render = particles && tick >= nextRenderTick
        if (render) nextRenderTick = tick + 5L
        entries.toMap().forEach { (zoneId, entry) ->
            val runtime = entry.runtime
            if (!active(entry) || runtime.settings.id != zoneId) {
                clear(zoneId)
                return@forEach
            }
            if (!render) return@forEach
            val portal = entry.portalId?.let(Bukkit::getEntity) as? Interaction
            if (portal?.isValid != true) return@forEach
            val viewers = Bukkit.getOnlinePlayers().filter { player ->
                player.world === portal.world && !access.isAdminEditing(player) &&
                    player.location.distanceSquared(portal.location) <= VIEW_RADIUS * VIEW_RADIUS
            }
            FarmPortalRenderer.render(portal, viewers)
        }
    }

    fun enter(player: Player, location: Location): Boolean {
        val entry = entries.values.firstOrNull { candidate ->
            val portal = candidate.portalId?.let(Bukkit::getEntity) as? Interaction
            active(candidate) && portal?.isValid == true && contains(portal, location)
        } ?: run { cancel(player); return false }
        if (!access.hasAccess(player, entry.runtime.settings.permission)) {
            cancel(player)
            audience.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (!entry.destination.canEnter(player)) {
            cancel(player)
            return true
        }
        val old = pending[player.uniqueId]
        if (old?.entry === entry) return true
        cancel(player)
        val pendingEntry = PendingEntry(entry, entry.style.activationSeconds)
        pending[player.uniqueId] = pendingEntry
        showCountdown(player, pendingEntry)
        if (!tasks.runLater(20L) { continueEntry(player.uniqueId, pendingEntry) }) cancel(player)
        return true
    }

    fun owns(entity: Entity): Boolean =
        entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING) &&
            entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING) in
                setOf("${rolePrefix}portal", "${rolePrefix}portal_label")

    fun clear(zoneId: String) {
        pending.entries.removeIf { (playerId, pendingEntry) ->
            if (pendingEntry.entry.runtime.settings.id != zoneId) return@removeIf false
            Bukkit.getPlayer(playerId)?.let(audience::clearScreenTitle)
            true
        }
        entries.remove(zoneId)?.let { entry ->
            entry.portalId?.let(Bukkit::getEntity)?.remove()
            entry.labelId?.let(Bukkit::getEntity)?.remove()
        }
    }

    fun cleanup() {
        entries.keys.toList().forEach(::clear)
        Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }.filter(::owns).forEach(Entity::remove)
        pending.keys.mapNotNull(Bukkit::getPlayer).forEach(audience::clearScreenTitle)
        pending.clear()
    }

    fun onQuit(player: Player) = cancel(player)

    private fun continueEntry(playerId: UUID, pendingEntry: PendingEntry) {
        if (pending[playerId] !== pendingEntry) return
        val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline) ?: run {
            pending.remove(playerId, pendingEntry)
            return
        }
        val entry = pendingEntry.entry
        val portal = entry.portalId?.let(Bukkit::getEntity) as? Interaction
        if (!active(entry) || portal?.isValid != true || !contains(portal, player.location) ||
            !access.hasAccess(player, entry.runtime.settings.permission) || !entry.destination.canEnter(player)
        ) {
            cancel(player)
            return
        }
        if (pendingEntry.remainingSeconds > 1) {
            pendingEntry.remainingSeconds--
            showCountdown(player, pendingEntry)
            if (!tasks.runLater(20L) { continueEntry(playerId, pendingEntry) }) cancel(player)
            return
        }
        audience.clearScreenTitle(player)
        if (entry.destination.enter(player)) {
            pending.remove(playerId, pendingEntry)
            return
        }
        if (pending[playerId] !== pendingEntry) return
        pendingEntry.remainingSeconds = 1
        showCountdown(player, pendingEntry)
        if (!tasks.runLater(20L) { continueEntry(playerId, pendingEntry) }) cancel(player)
    }

    private fun showCountdown(player: Player, pendingEntry: PendingEntry) {
        audience.showScreenTitle(
            player,
            MessageKey.FARM_ACTIVITY_PORTAL_COUNTDOWN,
            mapOf("seconds" to locale.text(pendingEntry.remainingSeconds)),
            "activity_portal",
        )
    }

    private fun cancel(player: Player) {
        if (pending.remove(player.uniqueId) != null) audience.clearScreenTitle(player)
    }

    private fun active(runtime: FarmRuntime): Boolean =
        runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType != null

    private fun active(entry: PortalEntry): Boolean =
        active(entry.runtime) && entry.runtime.state.sequence == entry.sequence &&
            entry.runtime.state.incidentType == entry.incidentType

    private fun mark(entity: Entity, runtime: FarmRuntime, role: String) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, rolePrefix + role)
    }

    private fun removeOwned(zoneId: String) {
        Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }
            .filter { entity ->
                owns(entity) && entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) == zoneId
            }
            .forEach(Entity::remove)
    }

    private fun contains(portal: Interaction, location: Location): Boolean = location.world === portal.world &&
        kotlin.math.abs(location.x - portal.location.x) <= portal.interactionWidth / 2.0 &&
        location.y >= portal.location.y - 0.5 && location.y <= portal.location.y + portal.interactionHeight &&
        kotlin.math.abs(location.z - portal.location.z) <= portal.interactionWidth / 2.0

    private data class PortalEntry(
        var runtime: FarmRuntime,
        val sequence: Long,
        val incidentType: FarmIncidentType,
        var style: FarmPortalStyle,
        var destination: FarmPortalDestination,
        var portalId: UUID? = null,
        var labelId: UUID? = null,
    )

    private data class PendingEntry(val entry: PortalEntry, var remainingSeconds: Int)

    private companion object { const val VIEW_RADIUS = 96.0 }
}
