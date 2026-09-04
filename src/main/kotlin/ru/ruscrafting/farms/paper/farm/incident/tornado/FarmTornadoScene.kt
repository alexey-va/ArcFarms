package ru.ruscrafting.farms.paper.farm.incident.tornado

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.joml.Matrix4f
import ru.ruscrafting.farms.config.FarmTornadoSettings
import ru.ruscrafting.farms.domain.FarmTornadoShape
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Only visual entities: no falling-block physics, item drops or terrain writes. */
internal class FarmTornadoScene(plugin: Plugin) {
    private val key = NamespacedKey(plugin, "farm_tornado_zone")
    private val debris = mutableMapOf<String, MutableMap<Int, UUID>>()
    private val dark = Particle.DustOptions(Color.fromRGB(77, 87, 96), 2.8f)
    private val silver = Particle.DustOptions(Color.fromRGB(174, 190, 195), 2.1f)
    private val earth = Particle.DustOptions(Color.fromRGB(155, 119, 78), 2.6f)
    private val warning = Particle.DustOptions(Color.fromRGB(245, 188, 91), 1.4f)
    private val materials = listOf(Material.COARSE_DIRT, Material.OAK_PLANKS, Material.HAY_BLOCK, Material.ROOTED_DIRT, Material.OAK_LEAVES)

    fun render(zone: String, center: Location, options: FarmTornadoSettings, tick: Int, strength: Double, players: List<Player>, particles: Boolean) {
        if (tick % FRAME_TICKS != 0) return
        val time = tick / 20.0
        val height = options.height * strength
        val radius = options.radius * strength
        val viewers = players.filter { it.world === center.world && it.location.distanceSquared(center) <= 96.0 * 96.0 }
        if (viewers.isEmpty()) {
            clear(zone)
            return
        }
        // Round-robin updates preserve a hard packet ceiling when a farm is crowded.
        val offset = (tick / FRAME_TICKS * MAX_PARTICLE_VIEWERS) % viewers.size
        val particleViewers = List(minOf(MAX_PARTICLE_VIEWERS, viewers.size)) { viewers[(offset + it) % viewers.size] }
        val point = center.clone()
        if (particles) {
            // Six interleaved ropes give a continuous silhouette under the counter-rotating crown.
            repeat(32) { level ->
                val fraction = level / 31.0
                repeat(6) { strand ->
                    val angle = strand * PI / 3 + fraction * PI * 4.2 - time * (3.8 - fraction * 1.6)
                    val offset = FarmTornadoShape.funnel(fraction, angle, time, height, radius)
                    point.set(center.x + offset.x, center.y + offset.y, center.z + offset.z)
                    val dust = if (level < 6) earth else if (strand % 3 == 0) silver else dark
                    particleViewers.forEach { it.spawnParticle(Particle.DUST, point, 1, 0.10, 0.16, 0.10, 0.0, dust) }
                    if (strand == 0 && level % 3 == 0) {
                        particleViewers.forEach { it.spawnParticle(Particle.CLOUD, point, 1, 0.3, 0.15, 0.3, 0.005) }
                    }
                }
            }
            repeat(32) { index ->
                val angle = index * PI / 16 + time * 2.1
                val ring = radius * (0.72 + (index % 3) * 0.16)
                point.set(center.x + cos(angle) * ring, center.y + 0.15, center.z + sin(angle) * ring)
                particleViewers.forEach { it.spawnParticle(Particle.DUST, point, 1, 0.22, 0.12, 0.22, 0.0, earth) }
                if (index % 2 == 0) {
                    point.set(center.x + cos(-angle) * radius, center.y + height, center.z + sin(-angle) * radius)
                    particleViewers.forEach { it.spawnParticle(Particle.CLOUD, point, 1, 0.55, 0.2, 0.55, 0.005) }
                }
            }
            if (strength < 1.0) repeat(32) { index ->
                val angle = index * PI / 16
                point.set(center.x + cos(angle) * 4.5, center.y + 0.12, center.z + sin(angle) * 4.5)
                particleViewers.forEach { it.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, warning) }
            }
        }
        val owned = debris.getOrPut(zone, ::linkedMapOf)
        repeat(options.debrisCount) { index ->
            val offset = FarmTornadoShape.debris(index, options.debrisCount, time, height, radius)
            point.set(center.x + offset.x, center.y + offset.y + 0.5, center.z + offset.z)
            if (!point.world.isChunkLoaded(point.blockX shr 4, point.blockZ shr 4)) {
                owned.remove(index)?.let { Bukkit.getEntity(it)?.remove() }
                return@repeat
            }
            val display = (owned[index]?.let(Bukkit::getEntity) as? BlockDisplay)?.takeIf(Entity::isValid)
                ?: center.world.spawn(point, BlockDisplay::class.java) { entity ->
                    entity.isPersistent = false
                    entity.setGravity(false)
                    entity.isInvulnerable = true
                    entity.block = materials[index % materials.size].createBlockData()
                    entity.teleportDuration = FRAME_TICKS
                    entity.interpolationDuration = FRAME_TICKS
                    entity.viewRange = 1.5f
                    entity.persistentDataContainer.set(key, PersistentDataType.STRING, zone)
                    owned[index] = entity.uniqueId
                }
            display.teleport(point)
            val size = (0.35 + (index % 5) * 0.14).toFloat()
            display.setTransformationMatrix(Matrix4f()
                .rotateXYZ((time * 1.8 + index).toFloat(), (time * 2.3).toFloat(), (time + index).toFloat())
                .scale(size).translate(-0.5f, -0.5f, -0.5f))
            display.interpolationDelay = 0
        }
    }

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(key, PersistentDataType.STRING)

    fun clear(zone: String) {
        debris.remove(zone)?.values?.forEach { Bukkit.getEntity(it)?.remove() }
    }

    fun cleanup() {
        debris.keys.toList().forEach(::clear)
        Bukkit.getWorlds().forEach { world -> world.entities.filter(::owns).forEach(Entity::remove) }
    }

    companion object {
        const val FRAME_TICKS = 3
        const val MAX_PARTICLE_VIEWERS = 8
    }
}
