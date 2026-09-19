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
) : MineSequenceIncident(
    MineIncidentType.GAS_LEAK, MineAnchorRole.SUPPORT, "gas_vent", registry, index, incidents,
    successSound = Sound.BLOCK_FIRE_EXTINGUISH,
) {
    private data class Session(
        val sequence: Long,
        var lastHazardAt: Long? = null,
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
            ?.takeIf { it.sequence == runtime.state.sequence }
            ?: Session(runtime.state.sequence).also { sessions[runtime.settings.id] = it }
        val points = targets.map { target ->
            org.bukkit.Location(runtime.region.world, target.position.x + 0.5, target.position.y + 1.0, target.position.z + 0.5)
        }
        val viewers = participants.filter { player ->
            player.isOnline && !player.isDead && player.gameMode != GameMode.SPECTATOR &&
                player.world === runtime.region.world
        }
        if (session.lastHazardAt?.let { now - it < HAZARD_PERIOD_MILLIS } == true) {
            renderClouds(points, viewers)
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
        renderClouds(points, viewers)
    }

    private fun renderClouds(points: Collection<org.bukkit.Location>, viewers: Collection<Player>) {
        if (viewers.isEmpty()) return
        points.forEach { point ->
            viewers.filter { it.location.distanceSquared(point) <= VIEW_RADIUS_SQUARED }.forEach { player ->
                player.spawnParticle(Particle.CLOUD, point, CLOUD_COUNT, 1.8, 0.9, 1.8, 0.01)
                player.spawnParticle(Particle.SMOKE, point.clone().add(0.0, 0.32, 0.0), SMOKE_COUNT, 1.4, 0.7, 1.4, 0.01)
                player.spawnParticle(Particle.DUST, point, CLOUD_COUNT, 1.8, 0.8, 1.8, 0.0,
                    Particle.DustOptions(org.bukkit.Color.fromRGB(139, 163, 89), 1.6f))
            }
        }
    }

    private fun active(runtime: MineRuntime): Boolean =
        runtime.state.phase == ru.ruscrafting.farms.domain.MinePhase.INCIDENT &&
            runtime.state.incident?.type == MineIncidentType.GAS_LEAK

    private companion object {
        const val HAZARD_PERIOD_MILLIS = 1_000L
        const val HAZARD_RADIUS_SQUARED = 3.5 * 3.5
        const val VIEW_RADIUS_SQUARED = 40.0 * 40.0
        const val HIT_DAMAGE = 1.0
        const val NAUSEA_DURATION_TICKS = 60
        const val CLOUD_COUNT = 10
        const val SMOKE_COUNT = 5
    }
}
