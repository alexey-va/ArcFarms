package ru.ruscrafting.farms.paper.mine.working

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import ru.ruscrafting.farms.domain.MineDriveProgress
import ru.ruscrafting.farms.domain.MineRailProgress
import ru.ruscrafting.farms.domain.MineRailProgression
import ru.ruscrafting.farms.domain.MineRailServiceKind
import ru.ruscrafting.farms.domain.MineWorkingState
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.logging.Level
import kotlin.math.*

/** Native motion over the scene's original-block journal, with a durable excavation buffer. */
internal class MineDriveController(
    plugin: Plugin,
    private val world: MineWorkingWorld,
    private val access: WorksiteAccessPort,
    private val state: WorksiteStatePort,
    private val tasks: WorksiteTaskPort,
    private val presentation: MineWorkingPresentation,
    private val rigs: MineDriveRig = MineDriveRig(plugin),
) {
    private val saving = mutableMapOf<String, Any>()
    private val durable = mutableMapOf<String, Set<Int>>()
    private val saved = mutableMapOf<String, MineDriveProgress>()
    private val saveAt = mutableMapOf<String, Long>()
    private val failed = mutableSetOf<String>()
    private val servicing = mutableSetOf<String>()

    fun zone(entity: Entity) = rigs.zone(entity)
    fun mount(runtime: MineRuntime, player: Player) = rigs.mount(runtime, player)
    fun release(player: Player) = rigs.release(player)
    fun cleanup(zone: String) {
        saving.remove(zone); durable.remove(zone); saved.remove(zone); saveAt.remove(zone)
        failed.remove(zone); servicing.remove(zone); rigs.cleanup(zone)
    }
    fun cleanup() { saving.clear(); durable.clear(); saved.clear(); saveAt.clear(); failed.clear(); servicing.clear(); rigs.cleanup() }
    fun reconcileLoaded() { cleanup(); rigs.reconcileLoaded() }
    fun busy(zone: String) = zone in saving

    /** Manual maintenance shares the drive persistence fence; no second save owner. */
    fun service(runtime: MineRuntime, next: MineRailProgress, onSaved: () -> Unit = {}): Boolean {
        val zone=runtime.settings.id
        if(busy(zone) || zone in failed) return false
        val current=runtime.state.incident?.working ?: return false
        val progress=current.drive ?: return false
        if(progress.rail?.service == null) return false
        val scene=world.scene(runtime) ?: return false
        servicing += zone
        save(runtime,scene,current.copy(drive=progress.copy(rail=next)),onSaved)
        return true
    }
    fun location(zone: String) = rigs.get(zone)?.cart?.location
    fun heading(zone: String) = rigs.get(zone)?.heading ?: 0f

    fun tick(runtime: MineRuntime, scene: MineWorkingScene, now: Long,
        participant: (Player) -> Boolean, reached: (Player, MineWorkingState) -> Unit) {
        val working = runtime.state.incident?.working ?: return
        if (!MineDriveLayout.machine(scene.plan.type,working.placement)) return
        val rail = MineDriveLayout.rail(scene.plan.type,working.placement)
        val zone = runtime.settings.id
        val rig = rigs.ensure(runtime)
        rig.maintenance = rail && working.drive?.rail?.service != null
        val cart = rig.cart
        val driver = cart.passengers.filterIsInstance<Player>().firstOrNull()
        val at = cart.location
        val (side, forward) = MineDriveLayout.local(working.placement, at.x, at.z)
        durable.getOrPut(zone) { working.drive?.let { it.prepared + it.carved }.orEmpty() }
        if (driver != null && participant(driver) && driver.gameMode != GameMode.SPECTATOR &&
            MineDriveLayout.reached(side,forward,working.placement.geometryVersion) && (!rail || MineRailProgression.due(working.drive?.rail ?: MineRailProgress(),working.placement.layoutSeed,forward) == null)) {
            stop(rig)
            if (!busy(zone) && zone !in failed) {
                if (rail && working.drive?.rail?.finished != true) {
                    val progress=working.drive ?: return
                    val rails=progress.rail ?: return
                    if (rails.route.lastOrNull() != progress.checkpoint || rails.route.size < 30) return
                    save(runtime,scene,working.copy(drive=progress.copy(rail=rails.copy(finished=true))))
                } else { if(rail) world.project(runtime); rigs.release(driver); reached(driver, working) }
            }
            return
        }
        if(zone in servicing) { stop(rig); return }
        // Reserve before mounting as well. Disk latency cannot become a pause at every block face.
        prepare(runtime, scene, now)
        if (rail) {
            val current=runtime.state.incident?.working ?: return
            val progress=current.drive
            val rails=progress?.rail ?: MineRailProgress()
            val service=MineRailProgression.due(rails,current.placement.layoutSeed,forward)
            if(service != null) {
                rig.maintenance = true
                stop(rig)
                if(rails.service == null && !busy(zone) && zone !in failed && progress != null)
                    save(runtime,scene,current.copy(drive=progress.copy(rail=rails.copy(service=service))))
                if(driver != null && now>=rig.hintAt) {
                    presentation.feedback(driver,if(service.kind==MineRailServiceKind.JAM) "rail-jammed" else "rail-empty")
                    rig.hintAt=now+3_000
                }
                return
            }
        }
        if (driver == null || !participant(driver) || driver.gameMode == GameMode.SPECTATOR) {
            if (driver != null) rigs.release(driver)
            stop(rig); return
        }
        if (now >= rig.hintAt) {
            presentation.feedback(driver, if (zone in failed) "drive-save-failed" else if(rail) "rail-controls" else "drive-controls")
            rig.hintAt = now + 3_000
        }
        if (zone in failed) { stop(rig); return }
        val input = driver.currentInput
        val steering = (if (input.isLeft) 1 else 0) - (if (input.isRight) 1 else 0)
        val throttle = (if (input.isForward) 1 else 0) - (if (input.isBackward) 1 else 0)
        val heading = MineDriveMotion.heading(rig.heading - steering * MineDriveMotion.TURN)
        val speed = MineDriveMotion.speed(rig.speed, throttle)
        if (abs(speed) < .001 && steering == 0) { stop(rig); return }
        val radians = Math.toRadians(heading.toDouble())
        val direction = Vector(-sin(radians), 0.0, cos(radians))
        val velocity = direction.clone().multiply(speed)
        val next = at.clone().add(velocity)
        val (nextSide, nextForward) = MineDriveLayout.local(working.placement, next.x, next.z)
        val relative = heading - working.placement.direction * 90f
        val cells = MineDriveMotion.footprint(side, forward, relative) +
            MineDriveMotion.footprint(nextSide, nextForward, relative)
        var boundary = nextForward < 2.0
        var bedrock = false
        var waiting = false
        val columns = linkedSetOf<Int>()
        val solidColumns = linkedSetOf<Int>()
        val allowed = durable.getValue(zone)
        // The whole chassis/cutter envelope stays inside owned space at all angles, including reverse.
        for ((s, f) in cells) {
            if (!MineDriveLayout.insideBoundary(s, f,working.placement.geometryVersion)) { boundary = true; continue }
            val id = MineDriveLayout.id(s, f,working.placement.geometryVersion)
            var solid = false
            var protected = false
            for (up in 1..4) {
                val p = MineDriveLayout.position(working.placement, id, up)
                if (p !in scene.plan.blocks || !cart.world.isChunkLoaded(p.x shr 4, p.z shr 4)) {
                    boundary = true; protected = true; break
                }
                val material = cart.world.getBlockAt(p.x, p.y, p.z).type
                if (material == Material.BEDROCK) { bedrock = true; protected = true }
                // Ceiling lanterns do not collide with the low cab or get excavated again.
                if (up <= 3 && !material.isAir && material != Material.RAIL && material != Material.LIGHT) solid = true
            }
            if (solid) solidColumns += id
            if (!protected && MineDriveLayout.driveable(s, f,working.placement.geometryVersion,rail)) {
                if (id in allowed) columns += id else if (solid) waiting = true
            } else if (solid && !protected) boundary = true
        }
        val current = runtime.state.incident?.working ?: return
        val old = current.drive ?: MineDriveProgress(checkpoint=MineDriveLayout.id(0,2,working.placement.geometryVersion),heading = rig.heading)
        val additions = columns - old.carved
        val checkpoint = MineDriveLayout.id(floor(side + .5).toInt(), floor(forward + .5).toInt(),working.placement.geometryVersion)
        val lamps = if (checkpoint in old.carved + additions && old.lamps.none { id ->
                (MineDriveLayout.side(id,working.placement.geometryVersion)-side).pow(2) + (MineDriveLayout.forward(id,working.placement.geometryVersion)-forward).pow(2) < 16
            }) old.lamps + checkpoint else old.lamps
        val progress = old.copy(carved = old.carved + additions, lamps = lamps,
            checkpoint = if (checkpoint in old.carved + additions) checkpoint else old.checkpoint,
            heading = if (boundary || bedrock || waiting) rig.heading else heading,
            rail = if(rail && checkpoint in old.carved + additions)
                MineRailProgression.follow(old.rail ?: MineRailProgress(),checkpoint) else old.rail)
        if (progress != old) {
            update(runtime, current.copy(drive = progress))
            // Every added cell is already durable; project only the cutter's current swept cells.
            world.project(runtime)
            if(rail && progress.rail?.route != old.rail?.route && now >= rig.railSoundAt) {
                val rear=cart.location.clone().subtract(direction.clone().multiply(2.1)).add(0.0,.1,0.0)
                cart.world.playSound(rear,Sound.BLOCK_CHAIN_PLACE,.65f,.9f)
                cart.world.playSound(rear,Sound.BLOCK_WOOD_PLACE,.4f,.7f)
                cart.world.spawnParticle(Particle.CRIT,rear,5,.45,.12,.2,.03)
                rig.railSoundAt=now+350
            }
        }
        if (additions.any { it in solidColumns }) effects(rig, direction, speed, now)
        if (boundary || bedrock || waiting) {
            stop(rig)
            if (boundary || bedrock) blocked(driver, rig, now, boundary)
        } else {
            rig.heading = heading; rig.speed = speed
            cart.velocity = velocity
            rigs.render(rig, throttle != 0 || steering != 0)
            if (abs(speed) > .001 && now >= rig.soundAt) {
                cart.world.playSound(cart.location, Sound.BLOCK_PISTON_EXTEND, .22f, .6f)
                rig.soundAt = now + 600
            }
        }
    }

    private fun prepare(runtime: MineRuntime, scene: MineWorkingScene, now: Long) {
        val zone = runtime.settings.id
        if (busy(zone) || zone in failed) return
        val working = runtime.state.incident?.working ?: return
        val old = working.drive ?: MineDriveProgress(checkpoint=MineDriveLayout.id(0,2,working.placement.geometryVersion),heading = working.placement.direction * 90f)
        val prepared = old.prepared + old.carved + MineDriveMotion.excavationCells(working.placement,MineDriveLayout.rail(scene.plan.type,working.placement))
        val next = old.copy(prepared = prepared)
        val needsBuffer = !durable.getValue(zone).containsAll(prepared)
        if (!needsBuffer && (saved[zone] == next || now < (saveAt[zone] ?: 0))) return
        saveAt[zone] = now + 500
        save(runtime, scene, working.copy(drive = next))
    }

    private fun update(runtime: MineRuntime, working: MineWorkingState) {
        val incident = runtime.state.incident ?: return
        runtime.state = runtime.state.copy(incident = incident.copy(working = working))
    }

    private fun stop(rig: MineDriveRig.Rig) { rig.speed = 0.0; rig.cart.velocity = Vector(); rigs.render(rig, false) }

    private fun effects(rig: MineDriveRig.Rig, direction: Vector, speed: Double, now: Long) {
        if (now < rig.effectAt) return
        val at = rig.cart.location.clone().add(direction.clone().multiply(if (speed < 0) -1.6 else 2.0)).add(0.0,.8,0.0)
        rig.cart.world.spawnParticle(Particle.BLOCK, at, 18, .6,.6,.25,.03, Material.DEEPSLATE.createBlockData())
        rig.cart.world.spawnParticle(Particle.CRIT, at, 5, .4,.3,.2,.05)
        rig.cart.world.playSound(at, Sound.BLOCK_GRINDSTONE_USE, .55f, .55f)
        rig.cart.world.playSound(at, Sound.BLOCK_DEEPSLATE_BREAK, .7f, .7f)
        rig.effectAt = now + 250
    }

    private fun blocked(player: Player, rig: MineDriveRig.Rig, now: Long, boundary: Boolean) {
        if (now < rig.effectAt) return
        presentation.feedback(player, if (boundary) "drive-boundary" else "drive-blocked")
        player.playSound(rig.cart.location, Sound.BLOCK_ANVIL_LAND, .25f, .65f)
        rig.effectAt = now + 1_500
    }

    private fun save(runtime: MineRuntime, scene: MineWorkingScene, next: MineWorkingState, onSaved: () -> Unit = {}) {
        val previous = runtime.state
        val incident = previous.incident ?: return
        val zone = runtime.settings.id
        val operation = Any()
        saving[zone] = operation
        update(runtime, next)
        val token = tasks.lifecycleToken()
        state.persistAsync().whenComplete { _, error -> tasks.runSync(token) {
            if (saving[zone] !== operation) return@runSync
            saving.remove(zone)
            servicing.remove(zone)
            if (runtime.state.sequence != previous.sequence || runtime.state.incident?.objectiveNonce != incident.objectiveNonce ||
                world.scene(runtime) !== scene) return@runSync
            if (error == null) {
                val progress = requireNotNull(next.drive)
                durable[zone] = progress.prepared + progress.carved
                saved[zone] = progress
                onSaved()
            } else {
                // Carving already performed under an earlier acknowledged buffer remains valid.
                val current = runtime.state.incident?.working ?: return@runSync
                current.drive?.let { update(runtime, current.copy(drive = it.copy(prepared = durable[zone].orEmpty()))) }
                failed += zone
                rigs.get(zone)?.let(::stop)
                state.log(Level.SEVERE, "Mine drive save failed zone=$zone; carrier stopped before unreserved excavation", error)
            }
        } }
    }
}
