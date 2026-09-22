package ru.ruscrafting.farms.paper.mine.working

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.farms.domain.MineRailProgress
import ru.ruscrafting.farms.domain.MineRailProgression
import ru.ruscrafting.farms.domain.MineRailService
import ru.ruscrafting.farms.domain.MineRailServiceKind
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.expedition.MineDisplayBlueprints
import ru.ruscrafting.farms.paper.mine.expedition.MineDisplayLighting
import ru.ruscrafting.farms.paper.mine.expedition.MineExpeditionMarkers
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteCarryable
import java.lang.Math.floorMod
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Owns the two short hand-service branches of the rail drive.
 *
 * The durable operation remains MineDriveController.service().  This class only owns
 * packet markers and the temporary cassette presentation; the cassette itself stays in
 * MineWorkingEquipment so the normal worksite item guard can reclaim it on exit.
 */
internal class MineRailDriveService(
    private val plugin: Plugin,
    private val equipment: MineWorkingEquipment,
    private val presentation: MineWorkingPresentation,
    private val drive: MineDriveController,
    private val access: WorksiteAccessPort,
) {
    private data class Carried(val zone: String, val displays: List<PacketBlockDisplay>)

    private val markers = MineExpeditionMarkers(plugin, MARKER_KEY)
    private val carried = mutableMapOf<UUID, Carried>()
    private var renderer: PaperPacketDisplays? = null

    /** Reconciles only the active service marker and packet cassette visuals. */
    fun tick(runtime: MineRuntime, @Suppress("UNUSED_PARAMETER") now: Long) {
        val zone = runtime.settings.id
        val active = active(runtime)
        if (active == null) {
            clearZone(zone)
            return
        }
        val cart = drive.location(zone)
        if (cart == null) {
            markers.clear(scope(zone))
            clearCarried(zone)
            return
        }

        // A reconnect or zone exit may remove the temporary item while the durable
        // journal is still at the feeder. Return that branch to its rack safely.
        if (active.service.kind == MineRailServiceKind.CASSETTE && active.service.step == 1 &&
            !hasCassette(runtime) && !drive.busy(zone)) {
            drive.service(runtime, active.progress.copy(service = active.service.copy(step = 0)))
        }

        val current = active(runtime) ?: run {
            clearZone(zone)
            return
        }
        val marker = marker(runtime, cart, current.service)
        markers.reconcile(scope(zone), listOf(marker))
        if (current.service.kind == MineRailServiceKind.JAM) {
            // The bent sleeper is deliberately a moving target: each accepted pry
            // withdraws it farther from the feeder and changes its yaw.  The packet
            // marker is therefore visibly different for all three hand strokes even
            // though the underlying block journal remains untouched.
            markers.translate(scope(zone), JAM_TARGET, MineRailDriveServiceGeometry.jamOffset(current.service.step))
        }
        syncCarried(runtime, current.service)
    }

    /** Returns true for every entity still owned by this service, including stale markers. */
    fun interact(runtime: MineRuntime, player: Player, entity: Entity): Boolean {
        val identity = markers.ownedIdentity(entity) ?: return false
        val prefix = "${scope(runtime.settings.id)}/"
        if (!identity.startsWith(prefix)) return false
        val target = identity.removePrefix(prefix)
        val active = active(runtime) ?: return true
        val cart = drive.location(runtime.settings.id) ?: return true
        if (!eligible(runtime, player, cart) || drive.busy(runtime.settings.id)) return true
        if (!access.allowInteraction("mine-rail-service:${runtime.settings.id}:${player.uniqueId}", 250L)) return true

        when (active.service.kind) {
            MineRailServiceKind.JAM -> if (target == JAM_TARGET) serviceJam(runtime, player, active)
            MineRailServiceKind.CASSETTE -> when {
                target == RACK_TARGET && active.service.step == 0 -> pickupCassette(runtime, player, active)
                target == FEEDER_TARGET && active.service.step == 1 -> insertCassette(runtime, player, active)
            }
        }
        return true
    }

    /** The controller releases the drive before calling this on quit/departure. */
    fun release(player: Player) {
        removeCarried(player.uniqueId)
    }

    fun cleanup(zone: String) {
        markers.clear(scope(zone))
        clearCarried(zone)
    }

    fun cleanup() {
        carried.keys.toList().forEach(::removeCarried)
        markers.cleanup()
        renderer?.close()
        renderer = null
    }

    private data class Active(val progress: MineRailProgress, val service: MineRailService)

    private fun active(runtime: MineRuntime): Active? {
        val incident = runtime.state.incident ?: return null
        val working = incident.working ?: return null
        if (!MineDriveLayout.rail(incident.type, working.placement)) return null
        val progress = working.drive?.rail ?: return null
        return progress.service?.let { Active(progress, it) }
    }

    private fun eligible(runtime: MineRuntime, player: Player, cart: Location): Boolean =
        player.isOnline && !player.isDead && !player.isInsideVehicle &&
            player.world === cart.world && player.location.distanceSquared(cart) <= MAX_DISTANCE_SQUARED &&
            !access.isAdminEditing(player) && access.hasAccess(player, runtime.settings.permission)

    private fun marker(runtime: MineRuntime, cart: Location, service: MineRailService): MineExpeditionMarkers.Target {
        val isJam = service.kind == MineRailServiceKind.JAM
        val id = when {
            isJam -> JAM_TARGET
            service.step == 0 -> RACK_TARGET
            else -> FEEDER_TARGET
        }
        val local = when {
            isJam -> JAM_POINT
            service.step == 0 -> RACK_POINT
            else -> FEEDER_POINT
        }
        val rack = !isJam && service.step == 0
        val model = when {
            isJam -> JAM_MODEL
            rack -> CASSETTE_MODEL
            else -> EMPTY_FEEDER_MODEL
        }
        return MineExpeditionMarkers.Target(
            id = id,
            location = localPoint(cart, drive.heading(runtime.settings.id), local.first, local.second, local.third),
            material = when {
                isJam -> Material.COBBLESTONE
                rack -> Material.RAIL
                else -> Material.IRON_BLOCK
            },
            label = presentation.text(if (isJam) JAM_LABEL else if(rack) CASSETTE_LABEL else "rail-service-feeder-target"),
            model = model,
            modelScale = MODEL_SCALE,
            glowing = true,
            yaw = Math.floorMod(
                facing(drive.heading(runtime.settings.id)) +
                    if (isJam) MineRailDriveServiceGeometry.jamYawOffset(service.step) else 0,
                360,
            ),
        )
    }

    private fun serviceJam(runtime: MineRuntime, player: Player, active: Active) {
        val next = if (active.service.step >= 2) {
            MineRailProgression.finishService(active.progress)
        } else {
            active.progress.copy(service = active.service.copy(step = active.service.step + 1))
        }
        if (!drive.service(runtime, next)) return
        presentation.feedback(player, if (active.service.step >= 2) DONE_FEEDBACK else JAM_LABEL)
        servicePulse(player)
    }

    private fun pickupCassette(runtime: MineRuntime, player: Player, active: Active) {
        val alreadyOwned = equipment.has(runtime, player, CASSETTE_ROLE)
        if (!alreadyOwned && !equipment.issue(runtime, player, CASSETTE_ROLE, Material.RAIL, cargo = true)) {
            presentation.feedback(player, INVENTORY_FULL_FEEDBACK)
            return
        }
        val next = active.progress.copy(service = active.service.copy(step = 1))
        if (!drive.service(runtime, next)) {
            if (!alreadyOwned) equipment.consume(runtime, player, CASSETTE_ROLE)
            return
        }
        presentation.feedback(player, CARRIED_FEEDBACK)
        servicePulse(player)
    }

    private fun insertCassette(runtime: MineRuntime, player: Player, active: Active) {
        if (!equipment.has(runtime, player, CASSETTE_ROLE)) {
            presentation.feedback(player, MISSING_FEEDBACK)
            return
        }
        drive.service(runtime, MineRailProgression.finishService(active.progress)) {
            equipment.consume(runtime, player, CASSETTE_ROLE)
            removeCarried(player.uniqueId)
            if (player.isOnline && !player.isDead) {
                presentation.feedback(player, DONE_FEEDBACK)
                servicePulse(player)
            }
        }
    }

    private fun hasCassette(runtime: MineRuntime): Boolean = runtime.region.world.players.any {
        equipment.has(runtime, it, CASSETTE_ROLE)
    }

    private fun syncCarried(runtime: MineRuntime, service: MineRailService) {
        val zone = runtime.settings.id
        val allowed = if (service.kind == MineRailServiceKind.CASSETTE) {
            runtime.region.world.players.filter { equipment.has(runtime, it, CASSETTE_ROLE) }
        } else emptyList()
        val keep = allowed.mapTo(hashSetOf()) { it.uniqueId }
        carried.filterValues { it.zone == zone }.keys.filter { it !in keep }.toList().forEach(::removeCarried)
        allowed.forEach { player ->
            if (carried[player.uniqueId]?.zone != zone) {
                removeCarried(player.uniqueId)
                carried[player.uniqueId] = spawnCarried(zone, player)
            }
            carried[player.uniqueId]?.let { renderCarried(it, player) }
        }
    }

    private fun spawnCarried(zone: String, player: Player): Carried {
        val parts = MineDisplayBlueprints.model(CASSETTE_MODEL)
        val owner = renderer ?: PaperPacketDisplays(plugin).also { renderer = it }
        val displays = mutableListOf<PacketBlockDisplay>()
        return try {
            parts.forEach { part ->
                displays += owner.spawnBlock(WorksiteCarryable.carriedLocation(player, CARRY_FORWARD, CARRY_Y), part.material.createBlockData()).apply {
                    brightness = MineDisplayLighting.brightness(part.material)
                    viewRange = 2f
                    interpolationDuration = 1
                    teleportDuration = 1
                    isGlowing = true
                    glowColorOverride = Color.fromRGB(255, 187, 77)
                }
            }
            Carried(zone, displays)
        } catch (failure: Throwable) {
            displays.forEach(PacketBlockDisplay::remove)
            throw failure
        }
    }

    private fun renderCarried(carried: Carried, player: Player) {
        val at = WorksiteCarryable.carriedLocation(player, CARRY_FORWARD, CARRY_Y)
        val rotation = Quaternionf().rotateY(-Math.toRadians(player.location.yaw.toDouble()).toFloat())
        MineDisplayBlueprints.model(CASSETTE_MODEL).zip(carried.displays).forEach { (part, display) ->
            val partRotation = Quaternionf(rotation).mul(MineDisplayBlueprints.rotation(part, 0f))
            val center = rotation.transform(Vector3f(MineDisplayBlueprints.center(part, 0f)).mul(MODEL_SCALE))
            val corner = partRotation.transform(Vector3f(part.size).mul(-MODEL_SCALE * .5f)).add(center)
            val pose = Transformation(corner, partRotation, Vector3f(part.size).mul(MODEL_SCALE), Quaternionf())
            if (display.transformation != pose) {
                display.interpolationDelay = 0
                display.transformation = pose
            }
            if (display.location != at) display.teleport(at)
        }
    }

    private fun clearZone(zone: String) {
        markers.clear(scope(zone))
        clearCarried(zone)
    }

    private fun clearCarried(zone: String) {
        carried.filterValues { it.zone == zone }.keys.toList().forEach(::removeCarried)
    }

    private fun removeCarried(playerId: UUID) {
        carried.remove(playerId)?.displays?.forEach(PacketBlockDisplay::remove)
    }

    private fun servicePulse(player: Player) {
        if (plugin.config.getBoolean("ui.sounds", true)) {
            player.playSound(player.location, Sound.BLOCK_ANVIL_USE, .55f, 1.15f)
        }
        if (plugin.config.getBoolean("ui.particles", true)) {
            player.world.spawnParticle(Particle.CRIT, player.location.clone().add(0.0, 1.0, 0.0), 5, .25, .2, .25, .02)
        }
    }

    private fun localPoint(cart: Location, heading: Float, x: Double, y: Double, z: Double): Location {
        val offset = MineRailDriveServiceGeometry.localOffset(heading, x, y, z)
        return cart.clone().add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
    }

    private fun facing(heading: Float): Int = floorMod((-heading).roundToInt(), 360)
    private fun scope(zone: String) = "$MARKER_SCOPE_PREFIX$zone"

    private companion object {
        const val MARKER_KEY = "mine_rail_service"
        const val MARKER_SCOPE_PREFIX = "rail-service:"
        const val JAM_TARGET = "jam"
        const val RACK_TARGET = "cassette-rack"
        const val FEEDER_TARGET = "cassette-feeder"
        const val JAM_MODEL = "rail_drive_jam"
        const val CASSETTE_MODEL = "rail_drive_cassette"
        const val EMPTY_FEEDER_MODEL = "rail_drive_feeder"
        const val CASSETTE_ROLE = "rail_cassette"
        const val MODEL_SCALE = .7f
        const val CARRY_FORWARD = .95
        const val CARRY_Y = .82
        const val MAX_DISTANCE_SQUARED = 25.0
        const val JAM_LABEL = "rail-service-jam-target"
        const val CASSETTE_LABEL = "rail-service-cassette-target"
        const val CARRIED_FEEDBACK = "rail-cassette-carried"
        const val DONE_FEEDBACK = "rail-service-done"
        const val INVENTORY_FULL_FEEDBACK = "inventory-full"
        const val MISSING_FEEDBACK = "rail-empty"
        val RACK_POINT = Triple(-1.02, .7, -.75)
        val FEEDER_POINT = Triple(1.02, .7, -.35)
        val JAM_POINT = FEEDER_POINT
    }
}

/** Pure pose rules shared by the packet marker and its focused tests. */
internal object MineRailDriveServiceGeometry {
    fun localOffset(heading: Float, x: Double, y: Double, z: Double): Vector3f {
        val rotation = Quaternionf().rotateY(Math.toRadians((-heading).toDouble()).toFloat())
        return rotation.transform(Vector3f(x.toFloat(), y.toFloat(), z.toFloat()))
    }

    fun jamOffset(step: Int): Vector = when (step.coerceIn(0, 2)) {
        0 -> Vector()
        1 -> Vector(-.04, .02, -.18)
        else -> Vector(.08, .06, -.34)
    }

    fun jamYawOffset(step: Int): Int = when (step.coerceIn(0, 2)) {
        0 -> 0
        1 -> 12
        else -> -14
    }
}
