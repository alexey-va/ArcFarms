package ru.ruscrafting.farms.paper.mine.working

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.expedition.MineDisplayBlueprints
import ru.ruscrafting.farms.paper.mine.expedition.MineDisplayLighting

/** Native carrier owns rider motion. Client-only body parts follow its observed pose. */
internal class MineDriveRig(private val plugin: Plugin) {
    data class Rig(val cart: Minecart, val body: List<PacketBlockDisplay>, var heading: Float,
        var phase: Float = 0f, var effectAt: Long = 0, var hintAt: Long = 0, val hitbox: Interaction? = null, var speed: Double = 0.0, var soundAt: Long = 0, var railSoundAt: Long = 0, val rail: Boolean = false, var maintenance: Boolean = false)
    private val tag = NamespacedKey(plugin, "mine_drive_carrier")
    private val rigs = mutableMapOf<String, Rig>()
    private var renderer: PaperPacketDisplays? = null
    private fun renderer() = renderer ?: PaperPacketDisplays(plugin).also { renderer = it }

    fun zone(entity: Entity): String? = entity.persistentDataContainer.get(tag, PersistentDataType.STRING)
    fun get(zone: String) = rigs[zone]
    fun ensure(runtime: MineRuntime): Rig {
        rigs[runtime.settings.id]?.takeIf { it.cart.isValid }?.let { return it }
        cleanup(runtime.settings.id)
        val working = requireNotNull(runtime.state.incident?.working)
        val progress = working.drive
        val rail=MineDriveLayout.rail(requireNotNull(runtime.state.incident).type,working.placement)
        val at = if (progress != null) MineDriveLayout.position(working.placement, progress.checkpoint).location(runtime.region.world)
            else working.placement.position(0,1,2).location(runtime.region.world)
        val heading = progress?.heading ?: working.placement.direction * 90f
        val cart = at.world.spawn(at, Minecart::class.java) {
            it.isPersistent = false; it.isInvulnerable = true; it.setGravity(false)
            it.maxSpeed = .13; it.isSlowWhenEmpty = false
            it.derailedVelocityMod = Vector(1.0,1.0,1.0)
            it.flyingVelocityMod = Vector(1.0,1.0,1.0)
            it.persistentDataContainer.set(tag, PersistentDataType.STRING, runtime.settings.id)
        }
        val hitbox = at.world.spawn(at, Interaction::class.java) {
            // Rail service points sit one block off either side. Keep the
            // boarding target inside the chassis envelope so repair clicks
            // reach their own markers instead of the carrier interaction.
            it.isPersistent = false
            it.interactionWidth = if (rail) 1.45f else 2.5f
            it.interactionHeight = if (rail) 1.35f else 2.1f
            it.isResponsive = true
            it.persistentDataContainer.set(tag, PersistentDataType.STRING, runtime.settings.id)
        }
        val body = (if(rail) MineRailDriveModel.parts else MineDriveModel.parts).map { part -> renderer().spawnBlock(at, part.material.createBlockData()).apply {
            brightness = MineDisplayLighting.brightness(part.material)
            viewRange = 2f; teleportDuration = 1; interpolationDuration = 1
            glowColorOverride = Color.fromRGB(255,190,85)
        } }
        return Rig(cart, body, heading, hitbox = hitbox, rail = rail).also { rigs[runtime.settings.id] = it; render(it, false) }
    }

    fun mount(runtime: MineRuntime, player: Player): Boolean {
        val incident = runtime.state.incident ?: return false
        val working = incident.working ?: return false
        if (MineDriveLayout.rail(incident.type, working.placement) && working.drive?.rail?.service != null) return false
        val rig = ensure(runtime)
        if (rig.cart.passengers.isNotEmpty() || player.isInsideVehicle ||
            player.world !== rig.cart.world || player.location.distanceSquared(rig.cart.location) > 25) return false
        return rig.cart.addPassenger(player)
    }

    fun render(rig: Rig, running: Boolean) {
        if (running) rig.phase += .32f
        val rotation = Quaternionf().rotateY(-Math.toRadians(rig.heading.toDouble()).toFloat())
        val at = rig.cart.location.clone().also { it.yaw = 0f; it.pitch = 0f }
        rig.hitbox?.takeIf { it.location != at }?.teleport(at)
        rig.body.zip(if(rig.rail) MineRailDriveModel.parts else MineDriveModel.parts).forEach { (display, part) ->
            val local = MineDisplayBlueprints.rotation(part, rig.phase)
            val rotated = Quaternionf(rotation).mul(local)
            val center = rotation.transform(MineDisplayBlueprints.center(part, rig.phase))
            val corner = rotated.transform(Vector3f(part.size).mul(-.5f)).add(center)
            val pose = Transformation(corner, rotated, Vector3f(part.size), Quaternionf())
            if (display.transformation != pose) { display.interpolationDelay = 0; display.transformation = pose }
            if (display.location != at) display.teleport(at)
            display.isGlowing = !rig.maintenance && rig.cart.passengers.isEmpty()
        }
    }

    fun release(player: Player) {
        rigs.values.filter { player in it.cart.passengers }.forEach { it.cart.removePassenger(player); it.cart.velocity = Vector(); it.speed = 0.0 }
    }
    fun cleanup(zone: String) { rigs.remove(zone)?.let { it.cart.eject(); it.cart.remove(); it.hitbox?.remove(); it.body.forEach(PacketBlockDisplay::remove) } }
    fun cleanup() { rigs.keys.toList().forEach(::cleanup); renderer?.close(); renderer = null }
    fun reconcileLoaded() {
        cleanup()
        Bukkit.getWorlds().forEach { world -> world.loadedChunks.forEach { chunk ->
            chunk.entities.filter { zone(it) != null }.forEach { it.eject(); it.remove() }
        } }
    }
}
