package ru.ruscrafting.farms.paper.mine.floorwork

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.joml.Vector3f
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.MineFloorActivity
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentPlacementReport
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteCarryable
import ru.ruscrafting.farms.paper.worksite.WorksiteGuidanceTarget
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.UUID
import java.util.logging.Level
import kotlin.math.abs

/** Fixed-floor hauling and drainage. Durable steps and rewards remain in the existing shift engine. */
internal class MineFloorActivities(
    private val plugin: Plugin,
    private val points: (MineRuntime, String) -> Location?,
    private val incidents: MineIncidentCoordinator,
    private val access: WorksiteAccessPort,
    private val state: WorksiteStatePort,
    private val locale: ArcFarmsLocale? = null,
) {
    private data class Session(
        val sequence: Long,
        val nonce: Long,
        val type: MineIncidentType,
        var operator: UUID? = null,
        var carrier: UUID? = null,
        var pull: MineFloorActivity.Pull? = null,
        var pump: MineFloorActivity.Pump = MineFloorActivity.Pump(),
        var animationAt: Long? = null,
        var visualPull: Float = 0f,
        var visualPress: Float = 0f,
        var lastUpdate: Long = 0L,
        var lastParticle: Long = 0L,
    )

    private val scenes = mutableMapOf<Pair<String, MineIncidentType>, MineFloorActivityScene>()
    private val sessions = mutableMapOf<String, Session>()
    private val completion = mutableMapOf<Pair<String, MineIncidentType>, Long>()
    private val warned = mutableSetOf<Pair<String, MineIncidentType>>()
    private val retryAt = mutableMapOf<Pair<String, MineIncidentType>, Long>()
    private var renderer: PaperPacketDisplays? = null

    fun diagnostics(runtime: MineRuntime, type: MineIncidentType): MineIncidentPlacementReport {
        val reason = placementFailure(runtime, type)
        return MineIncidentPlacementReport(type, required(type), if (reason == null) 1 else 0, 1,
            reason?.let { mapOf(it to 1) }.orEmpty())
    }

    fun start(runtime: MineRuntime, type: MineIncidentType, now: Long): Boolean {
        if (type !in TYPES || placementFailure(runtime, type) != null) return false
        return incidents.start(runtime, type, required(type), now)
    }

    /** Called from the existing visual cadence; simulation is bounded to 10 Hz and never scheduled separately. */
    fun update(runtime: MineRuntime, participants: Collection<Player>, now: Long) {
        val incident = runtime.state.incident?.takeIf { it.type in TYPES }
        val session = incident?.let { current ->
            sessions[runtime.settings.id]?.takeIf {
                it.sequence == runtime.state.sequence && it.nonce == current.objectiveNonce && it.type == current.type
            } ?: Session(runtime.state.sequence, current.objectiveNonce, current.type).also {
                sessions[runtime.settings.id] = it
            }
        }
        if (session == null) sessions.remove(runtime.settings.id)
        if (session != null && now - session.lastUpdate < 100L) return
        if (session != null) {
            session.lastUpdate = now
            val anchor = anchor(runtime, session.type)
            if (anchor == null) {
                cleanup(runtime)
                incidents.abort(runtime)
                return
            }
            session.carrier?.let { id ->
                if (participants.none { it.uniqueId == id && usable(runtime, it, anchor, 10.0) }) session.carrier = null
            }
            val operator = session.operator?.let(Bukkit::getPlayer)?.takeIf { usable(runtime, it, anchor, 10.0) }
            if (operator == null) {
                session.operator = null
                session.pull = null
                session.pump = MineFloorActivity.Pump()
                session.animationAt = null
            } else if (session.type == MineIncidentType.FREIGHT_WINCH) updateWinch(runtime, session, operator, anchor, now)
            else updatePump(runtime, session, operator, anchor, now)
        }
        TYPES.forEach { type ->
            val at = anchor(runtime, type) ?: return@forEach
            val key = runtime.settings.id to type
            val existing = scenes[key]
            if (existing != null && (existing.anchor != at || existing.sequence != runtime.state.sequence)) {
                existing.remove(); scenes.remove(key)
            }
            if (key !in scenes && participants.none { it.world === at.world && it.location.distanceSquared(at) < 1_024.0 }) return@forEach
            val scene = scenes[key] ?: if (now >= (retryAt[key] ?: 0L)) {
                retryAt[key] = now + 5_000L
                createScene(runtime, type, at)
            } else null
            if (scene == null) return@forEach
            val active = runtime.state.incident?.takeIf { it.type == type }
            val completed = (completion[key] ?: 0L) > now
            val progress = active?.progress ?: if (completed) required(type) else 0
            val current = session?.takeIf { active != null && it.type == type }
            val targetPull = if (progress >= 2 && type == MineIncidentType.FREIGHT_WINCH) 1f
                else ((current?.pull?.distance ?: 0.0) / MineFloorActivity.PULL_DISTANCE).toFloat()
            current?.let {
                it.visualPull += (targetPull - it.visualPull).coerceIn(-.08f, .12f)
                val targetPress = if (it.pump.downAt != null) 1f else 0f
                it.visualPress += (targetPress - it.visualPress).coerceIn(-.35f, .35f)
            }
            val elapsed = current?.animationAt?.let { (now - it).coerceAtLeast(0L) }
            val carrier = current?.carrier?.let(Bukkit::getPlayer)
            val pipe = carrier?.let { MineFloorActivityScene.local(at,
                WorksiteCarryable.carriedLocation(it, .85, .95)) }
            scene.render(MineFloorActivityGeometry.Pose(
                progress = progress,
                pull = if (progress >= 2 && type == MineIncidentType.FREIGHT_WINCH) 1f else current?.visualPull ?: 0f,
                swing = if (progress >= 3) 1f else if (type == MineIncidentType.FREIGHT_WINCH && elapsed != null)
                    (elapsed / SWING_MILLIS.toFloat()).coerceIn(0f, 1f) else 0f,
                press = current?.visualPress ?: 0f,
                drain = if (completed && type == MineIncidentType.DRAINAGE_PUMP) 1f else if (type == MineIncidentType.DRAINAGE_PUMP && elapsed != null)
                    (elapsed / DRAIN_MILLIS.toFloat()).coerceIn(0f, 1f) else 0f,
                pipe = pipe,
                pipeYaw = carrier?.let { Math.toRadians((at.yaw - it.location.yaw).toDouble()).toFloat() } ?: 0f,
                returning = if (completed && type == MineIncidentType.FREIGHT_WINCH)
                    (1f - ((completion[key]!! - now) / 2_000f)).coerceIn(0f, 1f) else 0f,
            ), if (active == null) null else action(type, progress, current))
        }
        completion.entries.removeIf { it.value <= now }
    }

    fun onInteract(event: PlayerInteractEntityEvent, runtimes: Collection<MineRuntime>, now: Long): Boolean {
        if (!event.rightClicked.persistentDataContainer.has(NamespacedKey(plugin, MineFloorActivityScene.OWNER_KEY), PersistentDataType.STRING)) return false
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND || !WorksiteCarryable.withinInteractionReach(event.player, event.rightClicked, 3.5)) return true
        val scene = scenes.values.firstOrNull { it.role(event.rightClicked.uniqueId) != null } ?: return true
        val runtime = runtimes.firstOrNull { it.settings.id == scene.zoneId } ?: return true
        val incident = runtime.state.incident?.takeIf { it.type == scene.type } ?: return true
        if (runtime.state.sequence != scene.sequence || !usable(runtime, event.player, scene.anchor, 10.0)) return true
        if (!access.allowInteraction("mine-floor:${runtime.settings.id}:${event.player.uniqueId}", 250L)) return true
        val session = session(runtime) ?: return true
        val role = scene.role(event.rightClicked.uniqueId) ?: return true
        if (role != action(scene.type, incident.progress, session)) return true
        if (scene.type == MineIncidentType.FREIGHT_WINCH) {
            when (role) {
                "hook" -> advance(runtime, event.player, now, Sound.BLOCK_CHAIN_PLACE)
                "rope" -> {
                    val local = MineFloorActivityScene.local(scene.anchor, event.player.location)
                    if (abs(local.x - MineFloorActivity.PULL_X) <= .7 &&
                        abs(local.z - MineFloorActivity.PULL_START_Z) <= .5 && event.player.isOnGround) {
                        session.operator = event.player.uniqueId
                        session.pull = MineFloorActivity.Pull(local.x.toDouble(), local.z.toDouble())
                    }
                }
                "swing" -> { session.operator = event.player.uniqueId; session.animationAt = now }
                "unload" -> advance(runtime, event.player, now, Sound.BLOCK_BARREL_CLOSE)
            }
        } else when (role) {
            "pipe" -> session.carrier = event.player.uniqueId
            "socket" -> if (session.carrier == event.player.uniqueId) {
                session.carrier = null
                advance(runtime, event.player, now, Sound.BLOCK_COPPER_PLACE)
            }
            "handle" -> if (!event.player.isSneaking && event.player.isOnGround) {
                session.operator = event.player.uniqueId
                session.pump = MineFloorActivity.Pump()
            }
            "valve" -> { session.operator = event.player.uniqueId; session.animationAt = now }
        }
        guidanceHint(runtime, event.player)?.let(event.player::sendActionBar)
        return true
    }

    private fun updateWinch(runtime: MineRuntime, session: Session, player: Player, anchor: Location, now: Long) {
        when (runtime.state.incident?.progress) {
            1 -> {
                val previous = session.pull ?: return
                val local = MineFloorActivityScene.local(anchor, player.location)
                session.pull = if (player.isOnGround && abs(local.y) <= .5f)
                    MineFloorActivity.pull(previous, local.x.toDouble(), local.z.toDouble()) else null
                if (session.pull == null) session.operator = null
                else if (session.pull!!.distance >= MineFloorActivity.PULL_DISTANCE - .02) {
                    session.operator = null
                    advance(runtime, player, now, Sound.BLOCK_CHAIN_BREAK)
                }
            }
            2 -> if (session.animationAt?.let { now - it >= SWING_MILLIS } == true) {
                session.animationAt = null; session.operator = null
                advance(runtime, player, now, Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE)
            }
        }
    }

    private fun updatePump(runtime: MineRuntime, session: Session, player: Player, anchor: Location, now: Long) {
        val progress = runtime.state.incident?.progress ?: return
        if (progress in 1..MineFloorActivity.PUMP_STROKES) {
            val local = MineFloorActivityScene.local(anchor, player.location)
            if (!player.isOnGround || abs(local.y) > .5f || abs(local.x - MineFloorActivityGeometry.PUMP_OPERATOR_X) > .85 || local.z !in 1.3f..2.65f) {
                session.operator = null; session.pump = MineFloorActivity.Pump(); return
            }
            val stroke = MineFloorActivity.pump(session.pump, player.isSneaking, now)
            if (session.pump.downAt == null && stroke.state.downAt != null) sound(anchor, Sound.BLOCK_PISTON_EXTEND)
            session.pump = stroke.state
            if (stroke.accepted) advance(runtime, player, now, Sound.BLOCK_PISTON_CONTRACT)
            if (runtime.state.incident?.progress == 5) session.operator = null
        } else if (progress == 5 && session.animationAt != null) {
            if (now - session.lastParticle >= 250L && plugin.config.getBoolean("ui.particles", true)) {
                session.lastParticle = now
                anchor.world.spawnParticle(Particle.SPLASH, MineFloorActivityScene.at(anchor, MineFloorActivityGeometry.DRAIN_OUTLET), 4, .2, .08, .2, 0.0)
            }
            if (now - session.animationAt!! >= DRAIN_MILLIS) {
                session.animationAt = null; session.operator = null
                advance(runtime, player, now, Sound.ITEM_BUCKET_EMPTY)
            }
        }
    }

    private fun advance(runtime: MineRuntime, player: Player, now: Long, sound: Sound) {
        val type = runtime.state.incident?.type ?: return
        val result = incidents.work(runtime, player)
        if (!result.accepted) return
        anchor(runtime, type)?.let { sound(it, sound) }
        if (runtime.state.incident?.type != type) {
            sessions.remove(runtime.settings.id)
            completion[runtime.settings.id to type] = now + if (type == MineIncidentType.FREIGHT_WINCH) 3_500L else 1_500L
        }
    }

    fun guidanceHint(runtime: MineRuntime, player: Player): Component? {
        val incident = runtime.state.incident?.takeIf { it.type in TYPES } ?: return null
        val at = anchor(runtime, incident.type) ?: return null
        val session = session(runtime)
        val id = if (incident.type == MineIncidentType.FREIGHT_WINCH) "winch" else "pump"
        val action = when {
            player.world !== at.world || abs(player.location.y - at.y) > 4 -> "travel"
            session?.operator != null && session.operator != player.uniqueId -> "busy"
            session?.carrier != null && session.carrier != player.uniqueId -> "busy"
            session?.animationAt != null -> if (id == "winch") "swinging" else "draining"
            session?.operator == player.uniqueId -> if (id == "winch") "pull" else "stroke"
            else -> action(incident.type, incident.progress, session) ?: "busy"
        }
        return locale?.renderPath("mine.floorwork.$id.$action", player, mapOf(
            "strokes" to Component.text((incident.progress - 1).coerceIn(0, 4)),
            "percent" to Component.text((((session?.pull?.distance ?: 0.0) / MineFloorActivity.PULL_DISTANCE) * 100).toInt()),
        )) ?: Component.empty()
    }

    fun guidanceTargets(runtime: MineRuntime, player: Player): List<WorksiteGuidanceTarget> {
        val incident = runtime.state.incident?.takeIf { it.type in TYPES } ?: return emptyList()
        val at = anchor(runtime, incident.type) ?: return emptyList()
        val session = session(runtime)
        if (session?.operator != null && session.operator != player.uniqueId) return emptyList()
        if (session?.carrier != null && session.carrier != player.uniqueId) return emptyList()
        val local = if (incident.type == MineIncidentType.FREIGHT_WINCH && session?.operator == player.uniqueId && incident.progress == 1)
            Vector3f(MineFloorActivity.PULL_X.toFloat(), .15f, (MineFloorActivity.PULL_START_Z + MineFloorActivity.PULL_DISTANCE).toFloat())
        else MineFloorActivityGeometry.controls(incident.type)[action(incident.type, incident.progress, session)] ?: return emptyList()
        return listOf(WorksiteGuidanceTarget("floor_${incident.type.name.lowercase()}", ObjectiveTargetRole("mine_floor_activity"),
            MineFloorActivityScene.at(at, local), org.bukkit.Color.fromRGB(255, 191, 78)))
    }

    fun releasePlayer(player: Player) {
        sessions.values.forEach { session ->
            if (session.carrier == player.uniqueId) session.carrier = null
            if (session.operator == player.uniqueId) {
                session.operator = null; session.pull = null; session.pump = MineFloorActivity.Pump(); session.animationAt = null
            }
        }
    }

    fun cleanup(runtime: MineRuntime) {
        scenes.keys.filter { it.first == runtime.settings.id }.forEach { scenes.remove(it)?.remove() }
        sessions.remove(runtime.settings.id)
        completion.keys.removeIf { it.first == runtime.settings.id }
        warned.removeIf { it.first == runtime.settings.id }
        retryAt.keys.removeIf { it.first == runtime.settings.id }
    }

    fun cleanupAll() {
        scenes.values.forEach(MineFloorActivityScene::remove)
        scenes.clear(); sessions.clear(); completion.clear(); warned.clear(); retryAt.clear()
        renderer?.close(); renderer = null
        // Explicit recovery boundary only: never scan world entities in the gameplay cadence.
        val key = NamespacedKey(plugin, MineFloorActivityScene.OWNER_KEY)
        Bukkit.getWorlds().forEach { world -> world.loadedChunks.forEach { chunk ->
            chunk.entities.filter { it.persistentDataContainer.has(key, PersistentDataType.STRING) }.forEach { it.remove() }
        } }
    }

    private fun session(runtime: MineRuntime): Session? = sessions[runtime.settings.id]?.takeIf {
        val current = runtime.state.incident
        current != null && it.type == current.type && it.sequence == runtime.state.sequence && it.nonce == current.objectiveNonce
    }

    private fun anchor(runtime: MineRuntime, type: MineIncidentType): Location? = points(runtime, point(type))?.takeIf {
        it.world === runtime.region.world && runtime.region.contains(it)
    }

    private fun usable(runtime: MineRuntime, player: Player, anchor: Location, radius: Double): Boolean =
        player.isOnline && !player.isDead && player.gameMode != GameMode.SPECTATOR && !access.isAdminEditing(player) &&
            access.hasAccess(player, runtime.settings.permission) && runtime.region.contains(player.location) &&
            player.world === anchor.world && abs(player.location.y - anchor.y) < 2 &&
            player.location.distanceSquared(anchor) <= radius * radius

    private fun placementFailure(runtime: MineRuntime, type: MineIncidentType): String? {
        val at = anchor(runtime, type) ?: return "floor_machine_point_missing"
        val bounds = MineFloorActivityGeometry.bounds(type)
        val corners = listOf(bounds.minX, bounds.maxX).flatMap { x ->
            listOf(bounds.minZ, bounds.maxZ).map { z -> MineFloorActivityScene.at(at, Vector3f(x, 0f, z)) }
        }
        // Floor and a two-block player corridor cover the complete platform footprint.
        for (x in corners.minOf { it.blockX }..corners.maxOf { it.blockX }) {
            for (z in corners.minOf { it.blockZ }..corners.maxOf { it.blockZ }) {
                val cell = Location(at.world, x + .5, at.y, z + .5)
                if (!runtime.region.contains(cell)) return "floor_machine_outside_region"
                if (!at.world.isChunkLoaded(x shr 4, z shr 4)) return "floor_machine_chunk_unloaded"
                if (!cell.clone().subtract(0.0, .1, 0.0).block.type.isSolid) return "floor_machine_floor_missing"
                for (y in 0..1) {
                    val clearance = cell.clone().add(0.0, y.toDouble(), 0.0)
                    if (!runtime.region.contains(clearance)) return "floor_machine_outside_region"
                    if (!clearance.block.isPassable) return "floor_machine_obstructed"
                }
            }
        }
        // Higher native scenery is allowed outside the actual swept machinery, including the
        // existing lamps over the front aisle. Deduplicate native reads across overlapping parts.
        val upperCells = mutableSetOf<Triple<Int, Int, Int>>()
        MineFloorActivityGeometry.sweptBoxes(type).forEach { box ->
            val points = listOf(box.min.x, box.max.x).flatMap { x ->
                listOf(box.min.z, box.max.z).map { z -> MineFloorActivityScene.at(at, Vector3f(x, 0f, z)) }
            }
            for (x in points.minOf { kotlin.math.floor(it.x + .0001).toInt() }..points.maxOf { kotlin.math.floor(it.x - .0001).toInt() }) {
                for (z in points.minOf { kotlin.math.floor(it.z + .0001).toInt() }..points.maxOf { kotlin.math.floor(it.z - .0001).toInt() }) {
                    val firstY = maxOf(at.blockY + 2, kotlin.math.floor(at.y + box.min.y + .0001).toInt())
                    val lastY = kotlin.math.floor(at.y + box.max.y - .0001).toInt()
                    for (y in firstY..lastY) upperCells.add(Triple(x, y, z))
                }
            }
        }
        for ((x, y, z) in upperCells) {
            val cell = Location(at.world, x + .5, y + .5, z + .5)
            if (!runtime.region.contains(cell)) return "floor_machine_outside_region"
            if (!at.world.isChunkLoaded(x shr 4, z shr 4)) return "floor_machine_chunk_unloaded"
            if (!cell.block.isPassable) return "floor_machine_obstructed"
        }
        return null
    }

    private fun createScene(runtime: MineRuntime, type: MineIncidentType, anchor: Location): MineFloorActivityScene? {
        val key = runtime.settings.id to type
        val failure = placementFailure(runtime, type)
        if (failure != null) return null
        return try {
            val packetRenderer = renderer ?: PaperPacketDisplays(plugin, "mine-floor-activities").also { renderer = it }
            MineFloorActivityScene(plugin, packetRenderer, runtime.settings.id, type, anchor.clone(), runtime.state.sequence)
                .also { it.spawn(); scenes[key] = it; warned.remove(key) }
        } catch (failure: Throwable) {
            if (warned.add(key)) state.log(Level.WARNING,
                "Mine floor scene failed zone=${runtime.settings.id} sequence=${runtime.state.sequence} type=$type anchor=$anchor", failure)
            null
        }
    }

    private fun sound(at: Location, sound: Sound) {
        if (plugin.config.getBoolean("ui.sounds", true)) at.world.playSound(at, sound, .7f, .95f)
    }

    private fun action(type: MineIncidentType, progress: Int, session: Session?): String? = when (type) {
        MineIncidentType.FREIGHT_WINCH -> when (progress) {
            0 -> "hook"
            1 -> if (session?.operator == null) "rope" else null
            2 -> if (session?.animationAt == null) "swing" else null
            3 -> "unload"
            else -> null
        }
        MineIncidentType.DRAINAGE_PUMP -> when (progress) {
            0 -> if (session?.carrier == null) "pipe" else "socket"
            in 1..4 -> if (session?.operator == null) "handle" else null
            5 -> if (session?.animationAt == null) "valve" else null
            else -> null
        }
        else -> null
    }

    companion object {
        val TYPES = setOf(MineIncidentType.FREIGHT_WINCH, MineIncidentType.DRAINAGE_PUMP)
        fun point(type: MineIncidentType) = if (type == MineIncidentType.FREIGHT_WINCH) "freight_winch" else "drainage_pump"
        fun required(type: MineIncidentType) = if (type == MineIncidentType.FREIGHT_WINCH) MineFloorActivity.WINCH_STEPS else MineFloorActivity.PUMP_STEPS
        const val SWING_MILLIS = 4_000L
        const val DRAIN_MILLIS = 2_000L
    }
}
