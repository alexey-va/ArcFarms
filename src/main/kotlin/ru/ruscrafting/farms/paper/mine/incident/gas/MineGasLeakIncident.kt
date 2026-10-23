package ru.ruscrafting.farms.paper.mine.incident.gas

import org.bukkit.GameMode
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.sequence.MineSequenceIncident
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex

internal class MineGasLeakIncident(
    registry: MineRuntimeRegistry, index: MineBlockIndex, incidents: MineIncidentCoordinator,
    candidateStock: ru.ruscrafting.farms.paper.mine.incident.MineIncidentCandidateStock? = null,
) : MineSequenceIncident(
    MineIncidentType.GAS_LEAK, MineAnchorRole.SUPPORT, "gas_vent", registry, index, incidents,
    candidateStock = candidateStock,
    successSound = Sound.BLOCK_FIRE_EXTINGUISH,
) {
    private data class Session(
        val sequence: Long,
        val nonce: Long,
        var lastHazardAt: Long? = null,
        var lastCloudAt: Long = 0L,
    )

    private val sessions = mutableMapOf<String, Session>()

    fun useVent(runtime: MineRuntime, targetId: String, player: Player): Boolean = use(runtime, targetId, player)

    /**
     * Applies the gas hazard from the real vent objectives and renders a bounded cloud around
     * each vent. The caller supplies the already access-filtered mine participants.
     */
    fun tick(runtime: MineRuntime, participants: Collection<Player>, now: Long) {
        if (!active(runtime)) {
            sessions.remove(runtime.settings.id)
            return
        }
        val targets = runtime.state.objective?.targets.orEmpty()
            .filter { it.status != ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus.COMPLETED }
        if (targets.isEmpty()) {
            sessions.remove(runtime.settings.id)
            return
        }
        val session = sessions[runtime.settings.id]
            ?.takeIf { it.sequence == runtime.state.sequence && it.nonce == runtime.state.incident?.objectiveNonce }
            ?: Session(runtime.state.sequence, runtime.state.incident!!.objectiveNonce).also { sessions[runtime.settings.id] = it }
        val points = targets.map { target ->
            org.bukkit.Location(runtime.region.world, target.position.x + 0.5, target.position.y + 1.0, target.position.z + 0.5)
        }
        val viewers = participants.filter { player ->
            player.isOnline && !player.isDead && player.gameMode != GameMode.SPECTATOR &&
                player.world === runtime.region.world
        }
        if (session.lastHazardAt?.let { now - it < HAZARD_PERIOD_MILLIS } == true) {
            renderClouds(session, points, viewers, now)
            return
        }
        session.lastHazardAt = now
        viewers.forEach { player ->
            val source = points.minByOrNull { it.distanceSquared(player.location) } ?: return@forEach
            if (source.distanceSquared(player.location) > HAZARD_RADIUS_SQUARED) return@forEach
            if (player.gameMode != GameMode.CREATIVE) {
                player.damage(HIT_DAMAGE)
                player.addPotionEffect(PotionEffect(PotionEffectType.NAUSEA, NAUSEA_DURATION_TICKS, 0, true, false, true))
            }
        }
        renderClouds(session, points, viewers, now)
    }

    private fun renderClouds(session: Session, points: Collection<org.bukkit.Location>, viewers: Collection<Player>, now: Long) {
        if (viewers.isEmpty() || now - session.lastCloudAt < 250L) return
        session.lastCloudAt = now
        val lobes = MineGasCloudGeometry.sample(now)
        points.take(4).forEach { point ->
            viewers.filter { it.location.distanceSquared(point) <= VIEW_RADIUS_SQUARED }.forEach { player ->
                // A narrow hissing source ties the rolling banks to the actual vent control.
                player.spawnParticle(Particle.SMOKE, point, 8, .13, .2, .13, .025)
                player.spawnParticle(Particle.CLOUD, point.clone().add(0.0, .35, 0.0), 4, .25, .2, .25, .018)
                lobes.forEach { lobe ->
                    val at = point.clone().add(lobe.x, lobe.y, lobe.z)
                    player.spawnParticle(Particle.DUST, at, 5, lobe.spread, .24, lobe.spread, 0.0,
                        Particle.DustOptions(org.bukkit.Color.fromRGB(lobe.color), lobe.size))
                    player.spawnParticle(Particle.CLOUD, at, if (lobe.wisps) 2 else 1, .55, .25, .55, .008)
                }
            }
        }
    }

    private fun active(runtime: MineRuntime): Boolean =
        runtime.state.phase == ru.ruscrafting.farms.domain.MinePhase.INCIDENT &&
            runtime.state.incident?.type == MineIncidentType.GAS_LEAK

    private companion object {
        const val HAZARD_PERIOD_MILLIS = 1_000L
        const val HAZARD_RADIUS_SQUARED = 6.5 * 6.5
        const val VIEW_RADIUS_SQUARED = 40.0 * 40.0
        const val HIT_DAMAGE = 1.0
        const val NAUSEA_DURATION_TICKS = 60
    }
}
