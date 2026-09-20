package ru.ruscrafting.farms.paper.mine.incident.creature

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.recovery.MineIncidentBlockJournal
import java.util.UUID
import kotlin.math.abs

/** Same-floor pursuit and bounded, drop-free vandalism over the existing incident recovery journal. */
internal class MineCreaturePests(
    private val journal: MineIncidentBlockJournal,
    private val navigation: MineCreatureNavigation,
    private val lift: ru.ruscrafting.farms.paper.mine.lift.MineLiftAccess? = null,
) {
    private class Session {
        val initialized = hashSetOf<UUID>()
        val pending = hashSetOf<WorksitePosition>()
        var nextDamage = 0L
        var nextAttack = 0L
        var ordinal = -1
    }
    private val sessions = hashMapOf<String, Session>()

    fun tick(runtime: MineRuntime, mobs: Map<Mob, WorksitePosition>, players: Collection<Player>, now: Long) {
        val session = sessions.getOrPut(runtime.settings.id, ::Session)
        mobs.forEach { (mob, anchor) ->
            if (session.initialized.add(mob.uniqueId)) navigation.initialize(mob)
            val target = players.asSequence().filter { eligible(runtime, anchor, mob, it) }
                .minByOrNull { it.location.distanceSquared(mob.location) }
            if (target == null) { navigation.stop(mob); return@forEach }
            navigation.chase(mob, target.location, runtime, anchor.y)
            if (now >= session.nextAttack && target.gameMode != GameMode.CREATIVE &&
                target.location.distanceSquared(mob.location) <= 2.6 && mob.hasLineOfSight(target)) {
                mob.swingMainHand()
                target.damage(3.0, mob)
                session.nextAttack = now + 1_000L
            }
            if (now >= session.nextDamage && !mob.hasLineOfSight(target)) damage(runtime, mob, anchor, target, session, now)
        }
    }

    fun cleanup(runtime: MineRuntime) { sessions.remove(runtime.settings.id) }

    internal fun eligible(runtime: MineRuntime, anchor: WorksitePosition, mob: Mob, player: Player): Boolean =
        player.isValid && !player.isDead && player.gameMode != GameMode.SPECTATOR &&
            player.world === mob.world && runtime.region.contains(player.location) &&
            abs(player.location.y - anchor.y) <= 3.25 && abs(mob.location.y - anchor.y) <= 3.25 &&
            player.location.distanceSquared(mob.location) <= 24.0 * 24.0

    private fun damage(runtime: MineRuntime, mob: Mob, anchor: WorksitePosition, target: Player, session: Session, now: Long) {
        session.nextDamage = now + 2_500L
        val damaged = journal.positions(runtime, INCIDENT_ID)
        if (damaged.size + session.pending.size >= MAX_DAMAGE) return
        val at = mob.location
        val candidates = buildList {
            for (dx in -1..1) for (dz in -1..1) for (dy in 0..1) {
                if (dx == 0 && dz == 0) continue
                val position = WorksitePosition(mob.world.name, at.blockX + dx, anchor.y + dy, at.blockZ + dz)
                if (position in damaged || position in session.pending) continue
                if (!mob.world.isChunkLoaded(position.x shr 4, position.z shr 4)) continue
                val block = mob.world.getBlockAt(position.x, position.y, position.z)
                if (!runtime.region.contains(block.location) || block.type !in runtime.mineableMaterials + STONE) continue
                if (lift?.floors().orEmpty().any { floor -> floor.exit.world === block.world &&
                    kotlin.math.abs(floor.y - anchor.y) < 3.0 &&
                    (floor.exit.x - block.x) * (floor.exit.x - block.x) + (floor.exit.z - block.z) * (floor.exit.z - block.z) < 49.0 }) continue
                // Only the wall beside the creature: never its supporting floor, railings or machinery.
                if (block.location.toCenterLocation().distanceSquared(at.clone().add(0.0, 0.8, 0.0)) > 4.5) continue
                add(position)
            }
        }
        val position = candidates.minByOrNull {
            mob.world.getBlockAt(it.x, it.y, it.z).location.distanceSquared(target.location)
        } ?: return
        val block = mob.world.getBlockAt(position.x, position.y, position.z)
        val original = block.blockData
        if (session.ordinal < 0) session.ordinal = journal.nextOrdinal(runtime, INCIDENT_ID)
        val ordinal = session.ordinal++
        session.pending += position
        journal.prepare(runtime, INCIDENT_ID, ordinal, position, Material.AIR).whenComplete { changed, failure ->
            journal.runOnMain {
                session.pending -= position
                if (failure == null && changed == true && sessions[runtime.settings.id] === session && mob.isValid) {
                    mob.swingMainHand()
                    block.world.playSound(block.location, Sound.BLOCK_STONE_BREAK, 0.8f, 0.7f)
                    block.world.spawnParticle(Particle.BLOCK, block.location.toCenterLocation(), 14, 0.3, 0.3, 0.3, original)
                }
            }
        }
    }

    companion object {
        const val MAX_DAMAGE = 16
        private const val INCIDENT_ID = "creature_nest"
        private val STONE = setOf(Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.ANDESITE, Material.DIORITE, Material.GRANITE)
    }
}
