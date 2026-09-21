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
import ru.ruscrafting.farms.domain.MineWorkingState
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.logging.Level
import kotlin.math.*

/** Steering, owned-volume collision, durable drilling, and carrier lifetime for one drive. */
internal class MineDriveController(
    plugin: Plugin,
    private val world: MineWorkingWorld,
    private val access: WorksiteAccessPort,
    private val state: WorksiteStatePort,
    private val tasks: WorksiteTaskPort,
    private val presentation: MineWorkingPresentation,
    private val rigs: MineDriveRig = MineDriveRig(plugin),
) {
    private val saving = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()

    fun zone(entity: Entity) = rigs.zone(entity)
    fun mount(runtime: MineRuntime, player: Player) = rigs.mount(runtime, player)
    fun release(player: Player) = rigs.release(player)
    fun cleanup(zone: String) { saving.remove(zone); failed.remove(zone); rigs.cleanup(zone) }
    fun cleanup() { saving.clear(); failed.clear(); rigs.cleanup() }
    fun reconcileLoaded() { saving.clear(); failed.clear(); rigs.reconcileLoaded() }
    fun busy(zone: String) = zone in saving

    fun tick(runtime: MineRuntime, scene: MineWorkingScene, now: Long,
        participant: (Player) -> Boolean, reached: (Player, MineWorkingState) -> Unit) {
        val incident = runtime.state.incident ?: return
        val working = incident.working ?: return
        if (!MineDriveLayout.enabled(working.placement)) return
        val rig = rigs.ensure(runtime)
        val cart = rig.cart
        val driver = cart.passengers.filterIsInstance<Player>().firstOrNull()
        cart.velocity = Vector()
        if (driver == null || !participant(driver) || driver.gameMode == GameMode.SPECTATOR) {
            if (driver != null) rigs.release(driver)
            rigs.render(rig, false)
            return
        }
        if (now >= rig.hintAt) {
            presentation.feedback(driver, if (runtime.settings.id in failed) "drive-save-failed" else "drive-controls")
            rig.hintAt = now + 3_000
        }
        if (runtime.settings.id in saving || runtime.settings.id in failed) { rigs.render(rig, false); return }
        val input = driver.currentInput
        val steering = (if (input.isLeft) 1 else 0) - (if (input.isRight) 1 else 0)
        rig.heading = MineDriveLayout.inwardHeading(working.placement.direction, rig.heading - steering * 2.8f)
        val throttle = (if (input.isForward) 1 else 0) - (if (input.isBackward) 1 else 0)
        val radians = Math.toRadians(rig.heading.toDouble())
        val forward = Vector(-sin(radians), 0.0, cos(radians))
        val velocity = forward.clone().multiply(throttle * .085)
        val next = cart.location.clone().add(velocity)
        val (localSide, localForward) = MineDriveLayout.local(working.placement, next.x, next.z)
        if (localForward >= MineDriveLayout.LENGTH - 4 && abs(localSide) <= 2.5) {
            rigs.release(driver)
            reached(driver, working)
            return
        }
        if (throttle == 0) { rigs.render(rig, false); return }
        // Cover both the predicted carrier envelope and its cutter, rather than
        // deleting a pre-authored row on the far side of an obstacle.
        val columns = linkedSetOf<Int>()
        var obstructed = false
        val centers = if (throttle > 0) listOf(next, next.clone().add(forward.clone().multiply(1.6))) else listOf(next)
        for (center in centers) {
            val (s, f) = MineDriveLayout.local(working.placement, center.x, center.z)
            val cs = floor(s + .5).toInt(); val cf = floor(f + .5).toInt()
            for (ds in -1..1) for (df in -1..1) {
                if (!MineDriveLayout.driveable(cs + ds, cf + df)) {
                    obstructed = true
                } else columns += MineDriveLayout.id(cs + ds, cf + df)
            }
        }
        val naturalBedrock = mutableSetOf<Int>()
        for (id in columns) for (up in 1..4) {
            val p = MineDriveLayout.position(working.placement, id, up)
            if (!cart.world.isChunkLoaded(p.x shr 4, p.z shr 4) || p !in scene.plan.blocks) return
            val material = cart.world.getBlockAt(p.x,p.y,p.z).type
            if (material == Material.BEDROCK) { obstructed = true; naturalBedrock += id }
            else if (up <= 3 && !material.isAir && throttle < 0) obstructed = true
        }
        columns.removeAll(naturalBedrock)
        // Expose the unbreakable face by cutting its surrounding ordinary rock,
        // while keeping the carrier stopped until the whole envelope is clear.
        rigs.render(rig, throttle > 0)
        val old = working.drive ?: MineDriveProgress(heading = rig.heading)
        val additions = if (throttle > 0) columns.filterTo(linkedSetOf()) { id ->
            id !in old.carved && (1..4).any { up ->
                val p = MineDriveLayout.position(working.placement,id,up)
                !cart.world.getBlockAt(p.x,p.y,p.z).type.isAir
            }
        } else emptySet()
        if (additions.isNotEmpty()) {
            val checkpoint = MineDriveLayout.id(floor(localSide + .5).toInt(), floor(localForward + .5).toInt())
            val carved = old.carved + columns
            val lamp = if (checkpoint in carved && old.lamps.none { id ->
                val dx = MineDriveLayout.side(id) - localSide; val dz = MineDriveLayout.forward(id) - localForward
                dx * dx + dz * dz < 49
            }) setOf(checkpoint) else emptySet()
            val progress = old.copy(carved = carved, lamps = old.lamps + lamp,
                checkpoint = checkpoint, heading = rig.heading)
            save(runtime, scene, working.copy(drive = progress))
            if (now >= rig.effectAt) {
                val at = cart.location.clone().add(forward.clone().multiply(2.0)).add(0.0,.8,0.0)
                cart.world.spawnParticle(Particle.BLOCK, at, 18, .6,.6,.25,.03, Material.DEEPSLATE.createBlockData())
                cart.world.spawnParticle(Particle.CRIT, at, 5, .4,.3,.2,.05)
                cart.world.playSound(at, Sound.BLOCK_GRINDSTONE_USE, .55f, .55f)
                cart.world.playSound(at, Sound.BLOCK_DEEPSLATE_BREAK, .7f, .7f)
                rig.effectAt = now + 250
            }
        } else if (obstructed) blocked(driver, rig, now) else cart.velocity = velocity
    }

    private fun blocked(player: Player, rig: MineDriveRig.Rig, now: Long) {
        rigs.render(rig, false)
        if (now < rig.effectAt) return
        presentation.feedback(player, "drive-blocked")
        player.playSound(rig.cart.location, Sound.BLOCK_ANVIL_LAND, .25f, .65f)
        rig.effectAt = now + 1_500
    }

    private fun save(runtime: MineRuntime, scene: MineWorkingScene, next: MineWorkingState) {
        val previous = runtime.state
        val incident = previous.incident ?: return
        val zone = runtime.settings.id
        if (!saving.add(zone)) return
        runtime.state = previous.copy(incident = incident.copy(working = next))
        val token = tasks.lifecycleToken()
        state.persistAsync().whenComplete { _, error -> tasks.runSync(token) {
            saving.remove(zone)
            if (runtime.state.sequence != previous.sequence || runtime.state.incident?.objectiveNonce != incident.objectiveNonce ||
                world.scene(runtime) !== scene) return@runSync
            if (error == null) world.project(runtime)
            else {
                runtime.state = previous
                failed += zone
                state.log(Level.SEVERE, "Mine drive save failed zone=$zone; carrier stopped before excavation", error)
            }
        } }
    }
}
