package ru.ruscrafting.farms.paper.mine.expedition

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.mine.expedition.*
import ru.ruscrafting.farms.paper.worksite.WorksiteWaterJet

/** Owns the retained factory interruptions; the crane has its own physical control owner. */
internal class MineFactoryExperimentsController(
    private val plugin: Plugin,
    private val markers: MineExpeditionMarkers,
    private val locale: ArcFarmsLocale?,
    private val position: (MineExpeditionScene, String, Vector) -> Location?,
    private val visuals: MineFactoryExperimentVisuals = PacketMineFactoryExperimentVisuals(plugin),
) {
    private val sessions = mutableMapOf<String, MineFactoryExperimentSession>()
    private val targetFactory = MineFactoryExperimentTargets(locale, position)
    private val crane = MineFactoryCraneControls(plugin, markers, targetFactory)

    fun targets(scene: MineExpeditionScene, state: MineExpeditionState, now: Long): List<MineExpeditionMarkers.Target> {
        if (!supported(scene)) return emptyList()
        val key = scope(scene)
        val pending = MineFactoryExperiments.pending(state).firstOrNull() ?: run { clear(key); return emptyList() }
        val session = session(key, scene, pending, now)
        return when (pending) {
            MineFactoryExperiment.ROCK_JAM -> listOfNotNull(targetFactory.target(
                scene, MineFactoryExperimentLayout.rockJam, Material.TUFF, "rock-pry"))
            MineFactoryExperiment.COOLING -> coolingTargets(scene, session)
            MineFactoryExperiment.MANUAL_CRANE -> crane.targets(scene, state, now)
            else -> emptyList() // Retired enum names remain readable in old persisted state.
        }
    }

    fun interact(scope: String, scene: MineExpeditionScene, state: MineExpeditionState, player: Player,
        id: String, now: Long, complete: (MineExpeditionStep) -> Boolean): Boolean {
        if (!supported(scene) || scope != scope(scene)) return false
        val experiment = MineFactoryExperiments.pending(state).firstOrNull() ?: return false
        if (experiment == MineFactoryExperiment.MANUAL_CRANE)
            return crane.interact(scope, scene, state, player, id, now, complete)
        val session = session(scope, scene, experiment, now)
        if (session.lastInput != Long.MIN_VALUE && now - session.lastInput < 180L) return true
        session.lastInput = now
        if (session.owner != null && session.owner != player.uniqueId) {
            player.sendActionBar(targetFactory.text("busy", player)); return true
        }
        return when (experiment) {
            MineFactoryExperiment.ROCK_JAM -> interactRock(scope, state, player, id, now, session, complete)
            MineFactoryExperiment.COOLING -> {
                if (id != MineFactoryExperimentLayout.hoseNozzle.id) return false
                session.owner = player.uniqueId
                session.hoseEquipped = true
                session.lastTick = now
                player.sendMessage(targetFactory.text("cooling-connected", player))
                true
            }
            else -> false
        }
    }

    fun tick(scope: String, scene: MineExpeditionScene, state: MineExpeditionState, players: Collection<Player>,
        now: Long, complete: (Player, MineExpeditionStep) -> Boolean) {
        if (!supported(scene) || scope != scope(scene)) return
        val experiment = MineFactoryExperiments.pending(state).firstOrNull() ?: run { clear(scope); return }
        val session = session(scope, scene, experiment, now)
        players.filter { session.informed.add(it.uniqueId) }.forEach { player ->
            val key = when (experiment) {
                MineFactoryExperiment.ROCK_JAM -> "rock-context"
                MineFactoryExperiment.COOLING -> "cooling-context"
                else -> "crane-context"
            }
            player.sendMessage(targetFactory.text(key, player))
        }
        if (experiment == MineFactoryExperiment.MANUAL_CRANE) {
            crane.tick(scope, scene, state, players, now, complete); return
        }
        if (session.owner != null && players.none { it.uniqueId == session.owner }) resetTransient(scope, session)
        if (experiment == MineFactoryExperiment.COOLING) tickCooling(scope, state, players, now, session, complete)
    }

    fun hint(scope: String, player: Player, state: MineExpeditionState, now: Long): Component? =
        when (MineFactoryExperiments.pending(state).firstOrNull()) {
            MineFactoryExperiment.ROCK_JAM -> targetFactory.text("rock-context-short", player)
            MineFactoryExperiment.COOLING -> {
                val session = sessions[scope]
                targetFactory.text(if (session?.hoseEquipped == true) "cooling-progress" else "cooling-context-short", player,
                    mapOf("percent" to Component.text((coolingProgress(scope) * 100).toInt())))
            }
            MineFactoryExperiment.MANUAL_CRANE -> crane.hint(scope, player)
            else -> null
        }

    fun cranePosition(scope: String): Location? = crane.position(scope)
    fun pryProgress(scope: String): Double = sessions[scope]?.let {
        MineFactoryExperimentMotion.pryProgress(it.pryCount, PRY_COUNT)
    } ?: 0.0
    fun coolingProgress(scope: String): Double = ((sessions[scope]?.sprayMillis ?: 0L).toDouble() / COOLING_MILLIS).coerceIn(0.0, 1.0)
    fun release(player: Player) {
        crane.release(player)
        sessions.entries.filter { it.value.owner == player.uniqueId }.forEach { (key, session) -> resetTransient(key, session) }
    }
    fun clear(scope: String) { sessions.remove(scope); visuals.clear(scope); crane.clear(scope) }
    fun cleanup() { sessions.clear(); visuals.cleanup(); crane.cleanup() }

    private fun resetTransient(scope: String, session: MineFactoryExperimentSession) {
        session.owner = null; session.pryCount = 0; session.hoseEquipped = false; session.sprayMillis = 0L
        markers.translate(scope, MineFactoryExperimentLayout.rockJam.id, Vector())
        visuals.clear(scope)
    }

    private fun coolingTargets(scene: MineExpeditionScene, session: MineFactoryExperimentSession): List<MineExpeditionMarkers.Target> = listOfNotNull(
        targetFactory.target(scene, MineFactoryExperimentLayout.hoseReel, Material.COPPER_BLOCK, "cooling-pickup",
            interactive = false, glowing = false)?.copy(label = Component.empty()),
        targetFactory.target(scene, MineFactoryExperimentLayout.hoseNozzle, Material.CUT_COPPER, "cooling-pickup",
            interactive = !session.hoseEquipped, glowing = !session.hoseEquipped,
            model = if (session.hoseEquipped) "factory_hose_stand" else "factory_hose_nozzle")
            ?.let { if (session.hoseEquipped) it.copy(label = Component.empty()) else it },
        targetFactory.target(scene, MineFactoryExperimentLayout.hotBearing, Material.ORANGE_STAINED_GLASS, "cooling-progress",
            values = mapOf("percent" to Component.text((session.sprayMillis * 100 / COOLING_MILLIS).coerceIn(0, 100))))
    )

    private fun interactRock(
        scope: String,
        state: MineExpeditionState,
        player: Player,
        id: String,
        now: Long,
        session: MineFactoryExperimentSession,
        complete: (MineExpeditionStep) -> Boolean,
    ): Boolean {
        if (id != MineFactoryExperimentLayout.rockJam.id) {
            return id == "control_crusher_left"
        }
        if (session.owner != null && session.owner != player.uniqueId) return true
        session.owner = player.uniqueId
        session.pryCount = (session.pryCount + 1).coerceAtMost(PRY_COUNT)
        val progress = MineFactoryExperimentMotion.pryProgress(session.pryCount, PRY_COUNT)
        markers.translate(
            scope,
            MineFactoryExperimentLayout.rockJam.id,
            Vector(MineFactoryExperimentMotion.pryWobble(session.pryCount, PRY_COUNT) * 0.45, progress * 0.06, progress * 0.32),
        )
        rockEffect(session.scene, player, session.pryCount == PRY_COUNT)
        if (session.pryCount < PRY_COUNT) return true
        val step = MineFactoryExperiments.resolve(state, MineFactoryExperiment.ROCK_JAM, now)
        if (step.accepted && complete(step)) {
            player.sendActionBar(targetFactory.text("rock-free", player))
            sessions.remove(scope)
        }
        return true
    }

    private fun rockEffect(scene: MineExpeditionScene, player: Player, finished: Boolean) {
        val at = targetFactory.location(scene, MineFactoryExperimentLayout.rockJam) ?: return
        val world = at.world ?: return
        if (plugin.config.getBoolean("ui.particles", true)) {
            world.spawnParticle(Particle.BLOCK, at, if (finished) 16 else 7, .18, .18, .18, .03, Material.TUFF.createBlockData())
        }
        if (plugin.config.getBoolean("ui.sounds", true)) {
            world.playSound(at, if (finished) Sound.BLOCK_STONE_BREAK else Sound.BLOCK_STONE_HIT, .6f, if (finished) .8f else 1.1f)
            if (finished) player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, .35f, 1.15f)
        }
    }

    private fun tickCooling(scope: String, state: MineExpeditionState, players: Collection<Player>, now: Long,
        session: MineFactoryExperimentSession, complete: (Player, MineExpeditionStep) -> Boolean) {
        val bearing = bearingCenter(session.scene) ?: return
        if (now >= session.nextParticles && plugin.config.getBoolean("ui.particles", true)) {
            session.nextParticles = now + 150L
            bearing.world.spawnParticle(Particle.SMOKE, bearing, 4, .13, .18, .13, .025)
            bearing.world.spawnParticle(Particle.ELECTRIC_SPARK, bearing, 2, .08, .08, .08, .025)
        }
        if (!session.hoseEquipped) return
        val owner = players.firstOrNull { it.uniqueId == session.owner } ?: return
        val reel = targetFactory.location(session.scene, MineFactoryExperimentLayout.hoseReel) ?: return
        if (owner.location.distanceSquared(reel) > 64.0) { resetTransient(scope, session); return }
        val elapsed = (now - session.lastTick).coerceIn(0L, 250L)
        session.lastTick = now
        val eye = owner.eyeLocation
        val nozzle = eye.clone().add(eye.direction.clone().multiply(.65)).add(0.0, -.35, 0.0)
        val direction = eye.direction.clone().normalize()
        visuals.render(scope, HELD_NOZZLE_ID, nozzle, "hose_nozzle_held", .85f, eye.yaw, eye.pitch)
        renderHose(scope, reel.add(0.0, .65, .15), nozzle)
        // Taking the nozzle opens its water supply. Aim is the interaction;
        // there is no hidden Shift or repeated-click requirement.
        val spray = nozzle.clone().add(direction.clone().multiply(.55))
        val hit = WorksiteWaterJet.hitTargets(listOf("bearing" to bearing), spray, direction, 4.5, .8).isNotEmpty()
        if (plugin.config.getBoolean("ui.particles", true)) {
            WorksiteWaterJet.renderJet(spray, direction, 4.5, .35, 2)
            if (hit) bearing.world.spawnParticle(Particle.CLOUD, bearing, 3, .18, .25, .18, .035)
        }
        if (now >= session.nextWaterSound && plugin.config.getBoolean("ui.sounds", true)) {
            session.nextWaterSound = now + 450L
            owner.playSound(nozzle, Sound.ITEM_BUCKET_EMPTY, .3f, 1.1f)
            if (hit) bearing.world.playSound(bearing, Sound.BLOCK_FIRE_EXTINGUISH, .55f, 1.25f)
        }
        if (!hit) return
        session.sprayMillis = (session.sprayMillis + elapsed).coerceAtMost(COOLING_MILLIS)
        if (session.sprayMillis < COOLING_MILLIS) return
        val step = MineFactoryExperiments.resolve(state, MineFactoryExperiment.COOLING, now)
        if (step.accepted && complete(owner, step)) {
            owner.sendMessage(targetFactory.text("cooling-finished", owner))
            visuals.clear(scope); sessions.remove(scope)
        }
    }

    private fun bearingCenter(scene: MineExpeditionScene): Location? {
        val fixture = MineFactoryExperimentLayout.hotBearing
        val local = fixture.copyOffset().add(Vector(0.0, .43 * fixture.scale, .08 * fixture.scale))
        return position(scene, fixture.anchor, local)
    }

    private fun renderHose(scope: String, reel: Location, nozzle: Location) {
        fun point(t: Double): Location = reel.clone().add(
            (nozzle.x - reel.x) * t,
            (nozzle.y - reel.y) * t - kotlin.math.sin(t * Math.PI) * .35,
            (nozzle.z - reel.z) * t,
        )
        repeat(16) { index ->
            val from = point(index / 16.0); val to = point((index + 1) / 16.0)
            val delta = to.toVector().subtract(from.toVector())
            val center = from.clone().add(delta.clone().multiply(.5))
            center.direction = delta
            visuals.render(scope, "hose-link-$index", center, "hose_link", delta.length().toFloat().coerceAtLeast(.015f), center.yaw, center.pitch)
        }
    }

    private fun session(scope: String, scene: MineExpeditionScene, experiment: MineFactoryExperiment, now: Long): MineFactoryExperimentSession {
        val old = sessions[scope]
        if (old?.experiment == experiment) return old
        if (old != null) { visuals.clear(scope); crane.clear(scope) }
        return MineFactoryExperimentSession(scene, experiment, lastTick = now).also { sessions[scope] = it }
    }
    private fun supported(scene: MineExpeditionScene): Boolean = scene.kind == MineExpeditionKind.DEAD_FACTORY &&
        scene.placement.geometryVersion >= 3 && MineFactoryProgram.usesConnectedCrusherLine(scene.plan)
    private fun scope(scene: MineExpeditionScene) = "${scene.zoneId}:${scene.sequence}:${scene.objectiveNonce}"
    private companion object {
        const val PRY_COUNT = 3
        const val COOLING_MILLIS = 3_500L
        const val HELD_NOZZLE_ID = "held-hose-nozzle"
    }
}
