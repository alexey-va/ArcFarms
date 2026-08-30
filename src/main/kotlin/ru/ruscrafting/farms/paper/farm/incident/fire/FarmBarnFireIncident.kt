package ru.ruscrafting.farms.paper.farm.incident.fire

import io.papermc.paper.event.entity.EntityLoadCrossbowEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.Vector
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.platform.FarmBlockPassability
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private data class FireKey(val zoneId: String, val index: Int)

/** Real fire hotspots whose spread and block damage are suppressed by [FarmEventRouter]. */
internal class FarmBarnFireIncident(
    private val settings: () -> ArcFarmsConfig,
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val points: FarmPointProvider,
    private val transitions: FarmTransitionSink,
    private val blockPassability: FarmBlockPassability,
) {
    private val blocks = mutableMapOf<FireKey, FarmPointPosition>()
    private val nextSpreadAt = mutableMapOf<String, Long>()
    private val unavailableSequences = mutableMapOf<String, Long>()

    fun initialize(runtime: FarmRuntime): Boolean {
        if (!active(runtime)) return false
        if (runtime.state.specialIncident != null) return true
        val anchor = points.resolve(runtime, FarmPointKind.PEN)
        val hotspots = planHotspots(runtime, anchor)
        if (hotspots.isEmpty()) {
            logUnavailable(runtime, anchor, "no_supported_surface")
            return false
        }
        val result = FarmShiftEngine.initializeBarnFire(
            runtime.state,
            hotspots,
            runtime.settings.barnFire.initialHotspotCount.coerceAtMost(hotspots.size),
        )
        if (!result.accepted) return false
        runtime.state = result.state
        state.persistAsync()
        debug.event(
            "farm_barn_fire_initialized",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "hotspots" to hotspots.size,
            "initial_hotspots" to runtime.state.specialIncident?.active?.size,
            "anchor" to "${anchor.x},${anchor.y},${anchor.z}",
        )
        return true
    }

    fun ensure(runtime: FarmRuntime) {
        if (!active(runtime)) {
            clear(runtime.settings.id, "inactive")
            return
        }
        if (!initialize(runtime)) return
        val incident = runtime.state.specialIncident ?: return
        blocks.keys.filter { it.zoneId == runtime.settings.id && it.index !in incident.active }
            .toList().forEach { remove(it, "extinguished") }
        var spawnBudget = runtime.settings.barnFire.spawnPerTick
        incident.active.sorted().forEach { index ->
            val point = incident.points.getOrNull(index) ?: return@forEach
            val key = FireKey(runtime.settings.id, index)
            val location = point.location() ?: return@forEach
            if (!location.world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return@forEach
            val block = location.block
            if (block.type == Material.FIRE) {
                blocks[key] = point
                return@forEach
            }
            blocks.remove(key)
            if (block.type == Material.AIR && spawnBudget > 0) {
                block.setType(Material.FIRE, false)
                spawnBudget--
            }
            if (block.type == Material.FIRE) blocks[key] = point
            else debug.event(
                "farm_barn_fire_hotspot_blocked",
                "zone" to runtime.settings.id,
                "hotspot" to index,
                "block" to block.type.name,
            )
        }
    }

    fun update(runtimes: Collection<FarmRuntime>, tick: Long) {
        runtimes.forEach { runtime ->
            if (!active(runtime)) {
                nextSpreadAt.remove(runtime.settings.id)
                return@forEach
            }
            spread(runtime, tick)
            val activeCount = runtime.state.specialIncident?.active?.size ?: 0
            val materializedCount = blocks.keys.count { it.zoneId == runtime.settings.id }
            if (materializedCount < activeCount || tick % 10L == 0L) ensure(runtime)
            if (!settings().particles || tick % runtime.settings.barnFire.flameParticleIntervalTicks != 0L) return@forEach
            particleHotspots(runtime, tick).forEach { index ->
                val location = runtime.state.specialIncident?.points?.getOrNull(index)?.location() ?: return@forEach
                if (!location.world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return@forEach
                location.world.spawnParticle(Particle.FLAME, location.clone().add(0.0, 0.65, 0.0), 2, 0.32, 0.45, 0.32, 0.012)
                location.world.spawnParticle(Particle.LARGE_SMOKE, location.clone().add(0.0, 1.05, 0.0), 1, 0.2, 0.25, 0.2, 0.015)
            }
        }
    }

    fun spray(event: PlayerInteractEvent, runtime: FarmRuntime): Boolean {
        if (!active(runtime) || event.hand != EquipmentSlot.HAND ||
            event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)
        ) return false
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        event.isCancelled = true
        return spray(event.player, runtime)
    }

    /** Late fallback if a modeled CROSSBOW reaches Paper's load event. */
    fun spray(event: EntityLoadCrossbowEvent, runtime: FarmRuntime): Boolean {
        val player = event.entity as? Player ?: return false
        if (!active(runtime)) return false
        event.isCancelled = true
        event.setConsumeItem(false)
        return spray(player, runtime)
    }

    /** A previously charged service item still cannot release a vanilla projectile. */
    fun spray(event: EntityShootBowEvent, runtime: FarmRuntime): Boolean {
        val player = event.entity as? Player ?: return false
        if (!active(runtime)) return false
        event.isCancelled = true
        return spray(player, runtime)
    }

    private fun spray(player: Player, runtime: FarmRuntime): Boolean {
        if (!access.hasAccess(player, runtime.settings.permission)) {
            audience.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        val config = runtime.settings.barnFire
        if (!access.allowInteraction(
                "farm-barn-fire:${runtime.settings.id}:${player.uniqueId}",
                config.sprayCooldownTicks * 50L,
            )
        ) return true
        val start = player.eyeLocation.clone().add(player.eyeLocation.direction.normalize().multiply(0.45))
        val direction = player.eyeLocation.direction.normalize()
        renderJet(start, direction, config.sprayRange, config.particleStep)
        val hits = hitsInSpray(runtime, start, direction, config.sprayRange, config.sprayHitRadius)
        if (hits.isEmpty()) {
            audience.sendActionBar(player, MessageKey.FARM_BARN_FIRE_AIM_HINT)
            if (settings().sounds) player.playSound(player.location, Sound.ITEM_BUCKET_EMPTY, 0.35f, 1.35f)
            return true
        }
        hits.forEach { hit ->
            val location = runtime.state.specialIncident?.points?.getOrNull(hit)?.location() ?: return@forEach
            remove(FireKey(runtime.settings.id, hit), "sprayed")
            if (settings().particles) {
                location.world.spawnParticle(Particle.SPLASH, location.clone().add(0.0, 0.65, 0.0), 26, 0.45, 0.55, 0.45, 0.12)
                location.world.spawnParticle(Particle.CLOUD, location.clone().add(0.0, 0.45, 0.0), 10, 0.35, 0.25, 0.35, 0.035)
            }
            if (settings().sounds) location.world.playSound(location, Sound.BLOCK_FIRE_EXTINGUISH, 1.1f, 0.9f)
            transitions.apply(runtime, FarmShiftEngine.extinguishBarnFire(runtime.state, hit, player.uniqueId), player)
        }
        if (settings().sounds) player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.55f, 1.35f)
        debug.event(
            "farm_barn_fire_extinguished",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "hotspots" to hits.joinToString(","),
            "count" to hits.size,
            "player" to player.name,
        )
        return true
    }

    fun anchor(runtime: FarmRuntime): Location? = points.resolve(runtime, FarmPointKind.PEN).location()

    fun clear(zoneId: String, reason: String) {
        blocks.keys.filter { it.zoneId == zoneId }.toList().forEach { remove(it, reason) }
        nextSpreadAt.remove(zoneId)
    }

    fun cleanup(reason: String) {
        val removed = blocks.keys.toList().count { key -> remove(key, reason) }
        nextSpreadAt.clear()
        unavailableSequences.clear()
        if (removed > 0) debug.event("farm_barn_fire_cleanup", "reason" to reason, "removed" to removed)
    }

    fun protects(location: Location): Boolean = blocks.values.any { point ->
        point.world == location.world.name && point.x.toIntFloor() == location.blockX &&
            point.y.toIntFloor() == location.blockY && point.z.toIntFloor() == location.blockZ
    }

    private fun planHotspots(runtime: FarmRuntime, anchor: FarmPointPosition): List<FarmPointPosition> {
        val config = runtime.settings.barnFire
        val world = runtime.region.world
        if (anchor.world != world.name) return emptyList()
        val candidates = buildList {
            for (x in -config.placementRadius..config.placementRadius) {
                for (z in -config.placementRadius..config.placementRadius) {
                    if (x * x + z * z <= config.placementRadius * config.placementRadius) add(x to z)
                }
            }
        }.sortedBy { (x, z) -> mix(runtime.state.placementSequence, x, z) }
        val chosen = mutableListOf<FarmPointPosition>()
        candidates.forEach { (offsetX, offsetZ) ->
            if (chosen.size >= config.hotspotCount) return@forEach
            val x = anchor.x.toIntFloor() + offsetX
            val z = anchor.z.toIntFloor() + offsetZ
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return@forEach
            val y = surfaceY(runtime, x, anchor.y.toIntFloor(), z, config.verticalSearch) ?: return@forEach
            val point = FarmPointPosition(world.name, x + 0.5, y + 1.02, z + 0.5)
            if (chosen.any { horizontalDistanceSquared(it, point) < config.minSpacing * config.minSpacing }) return@forEach
            chosen += point
        }
        return chosen
    }

    private fun spread(runtime: FarmRuntime, tick: Long) {
        val zoneId = runtime.settings.id
        val config = runtime.settings.barnFire
        val next = nextSpreadAt.getOrPut(zoneId) { tick + config.spreadIntervalTicks }
        if (tick < next) return
        nextSpreadAt[zoneId] = tick + config.spreadIntervalTicks
        val result = FarmShiftEngine.spreadBarnFire(runtime.state, config.spreadHotspotsPerPulse)
        if (!result.accepted) return
        runtime.state = result.state
        state.persistAsync()
        debug.event(
            "farm_barn_fire_spread",
            "zone" to zoneId,
            "sequence" to runtime.state.sequence,
            "active_hotspots" to runtime.state.specialIncident?.active?.size,
            "hotspot_cap" to runtime.state.incidentRequired,
        )
    }

    private fun surfaceY(runtime: FarmRuntime, x: Int, centerY: Int, z: Int, verticalSearch: Int): Int? {
        val world = runtime.region.world
        val offsets = buildList {
            add(0)
            for (step in 1..verticalSearch) {
                add(step)
                add(-step)
            }
        }
        return offsets.firstNotNullOfOrNull { offset ->
            val floor = world.getBlockAt(x, centerY + offset - 1, z)
            val feet = floor.getRelative(BlockFace.UP)
            val head = feet.getRelative(BlockFace.UP)
            if (floor.type.isSolid && feet.type.isAir && blockPassability.isPassable(head) && runtime.region.contains(feet.location)) floor.y else null
        }
    }

    private fun hitsInSpray(
        runtime: FarmRuntime,
        start: Location,
        direction: Vector,
        range: Double,
        radius: Double,
    ): List<Int> = runtime.state.specialIncident?.let { incident ->
        incident.active.mapNotNull { index ->
            if (FireKey(runtime.settings.id, index) !in blocks) return@mapNotNull null
            val point = incident.points.getOrNull(index)?.location() ?: return@mapNotNull null
            if (point.world !== start.world) return@mapNotNull null
            val relative = point.toVector().subtract(start.toVector())
            val along = relative.dot(direction)
            if (along !in 0.0..range) return@mapNotNull null
            val closest = start.toVector().add(direction.clone().multiply(along))
            val distanceSquared = point.toVector().distanceSquared(closest)
            if (distanceSquared > radius * radius) null else Triple(index, along, distanceSquared)
        }.sortedWith(compareBy<Triple<Int, Double, Double>> { it.second }.thenBy { it.third })
            .map { it.first }
    }.orEmpty()

    private fun particleHotspots(runtime: FarmRuntime, tick: Long): List<Int> {
        val limit = runtime.settings.barnFire.particleHotspotLimit
        if (limit <= 0) return emptyList()
        val active = runtime.state.specialIncident?.active.orEmpty().asSequence()
            .filter { FireKey(runtime.settings.id, it) in blocks }
            .sorted()
            .toList()
        if (active.size <= limit) return active
        val start = Math.floorMod(
            tick / runtime.settings.barnFire.flameParticleIntervalTicks,
            active.size.toLong(),
        ).toInt()
        return List(limit) { offset -> active[(start + offset) % active.size] }
    }

    private fun renderJet(start: Location, direction: Vector, range: Double, step: Double) {
        if (!settings().particles) return
        val forward = direction.clone().normalize()
        val reference = if (abs(forward.y) < 0.92) Vector(0.0, 1.0, 0.0) else Vector(1.0, 0.0, 0.0)
        val right = forward.clone().crossProduct(reference).normalize()
        val up = right.clone().crossProduct(forward).normalize()
        var distance = 0.0
        var sample = 0
        while (distance <= range) {
            val center = start.clone().add(forward.clone().multiply(distance))
            center.world.spawnParticle(Particle.SPLASH, center, 1, 0.06, 0.06, 0.06, 0.02)
            if (sample % 2 == 0) {
                val coneRadius = 0.12 + (distance / range).coerceIn(0.0, 1.0) * 0.82
                repeat(WATER_SIDE_STREAMS) { stream ->
                    val angle = (stream.toDouble() / WATER_SIDE_STREAMS * PI * 2.0) + sample * 0.47
                    val spray = center.clone()
                        .add(right.clone().multiply(cos(angle) * coneRadius))
                        .add(up.clone().multiply(sin(angle) * coneRadius))
                    spray.world.spawnParticle(Particle.SPLASH, spray, 2, 0.1, 0.1, 0.1, 0.045)
                }
            }
            distance += step
            sample++
        }
    }

    private fun remove(key: FireKey, reason: String): Boolean {
        val point = blocks.remove(key) ?: return false
        val location = point.location()
        if (location?.block?.type == Material.FIRE) location.block.setType(Material.AIR, false)
        debug.event("farm_barn_fire_removed", "zone" to key.zoneId, "hotspot" to key.index, "reason" to reason)
        return true
    }

    private fun logUnavailable(runtime: FarmRuntime, anchor: FarmPointPosition, reason: String) {
        if (unavailableSequences.put(runtime.settings.id, runtime.state.placementSequence) == runtime.state.placementSequence) return
        state.log(
            java.util.logging.Level.WARNING,
            "ArcFarms barn fire could not start: zone=${runtime.settings.id} sequence=${runtime.state.placementSequence} " +
                "reason=$reason anchor=${anchor.world}:${anchor.x},${anchor.y},${anchor.z}",
        )
        debug.event("farm_barn_fire_unavailable", "zone" to runtime.settings.id, "sequence" to runtime.state.placementSequence, "reason" to reason)
    }

    private fun FarmPointPosition.location(): Location? = Bukkit.getWorld(world)?.let { Location(it, x, y, z, yaw, pitch) }

    private fun Double.toIntFloor(): Int = kotlin.math.floor(this).toInt()

    private fun horizontalDistanceSquared(first: FarmPointPosition, second: FarmPointPosition): Double {
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz
    }

    private fun mix(sequence: Long, x: Int, z: Int): Long {
        var value = sequence xor (x.toLong() shl 32) xor z.toLong()
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    private fun active(runtime: FarmRuntime): Boolean = runtime.state.phase == FarmPhase.INCIDENT &&
        runtime.state.incidentType == FarmIncidentType.BARN_FIRE

    private companion object {
        const val WATER_SIDE_STREAMS = 4
    }
}
