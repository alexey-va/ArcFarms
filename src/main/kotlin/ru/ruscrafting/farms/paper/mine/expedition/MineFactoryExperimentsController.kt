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
import kotlin.math.PI

/**
 * Runtime owner for the optional connected-factory experiments.
 *
 * Durable selection and resolution stay in [MineFactoryExperiments]. This
 * owner keeps only player ownership, animation progress and packet-facing
 * poses. It deliberately never creates a cargo lease or ordinary item drop.
 */
internal class MineFactoryExperimentsController(
    private val plugin: Plugin,
    private val markers: MineExpeditionMarkers,
    private val locale: ArcFarmsLocale?,
    private val position: (MineExpeditionScene, String, Vector) -> Location?,
    private val visuals: MineFactoryExperimentVisuals = PacketMineFactoryExperimentVisuals(plugin),
) {
    private val sessions = mutableMapOf<String, MineFactoryExperimentSession>()
    private val targetFactory = MineFactoryExperimentTargets(locale, position)
    private val visualScenes = MineFactoryExperimentVisualScenes(plugin, targetFactory, visuals, ::scope, sessions)

    /** Stable packet targets for the current pending experiment. */
    fun targets(scene: MineExpeditionScene, state: MineExpeditionState, now: Long): List<MineExpeditionMarkers.Target> {
        if (!supported(scene)) return emptyList()
        val scope = scope(scene)
        val pending = MineFactoryExperiments.pending(state).firstOrNull()
        if (pending == null) {
            sessions.remove(scope)
            if (state.stage == MineExpeditionStage.FACTORY_INSTALL) visualScenes.renderInstalledMould(scene, state, scope)
            else visuals.clear(scope)
            return emptyList()
        }
        val session = session(scope, scene, pending, now)
        return when (pending) {
            MineFactoryExperiment.ROCK_JAM -> listOfNotNull(
                targetFactory.target(
                    scene,
                    MineFactoryExperimentLayout.rockJam,
                    Material.TUFF,
                    "rock-pry",
                    values = mapOf("count" to Component.text(session.pryCount), "total" to Component.text(PRY_COUNT)),
                ),
            )
            MineFactoryExperiment.MOULD -> visualScenes.mouldTargets(scene, state, session)
            MineFactoryExperiment.MANUAL_CRANE -> visualScenes.craneTargets(scene, session)
            MineFactoryExperiment.DRIVE_REPAIR -> emptyList()
            MineFactoryExperiment.ROUTING -> listOfNotNull(
                targetFactory.target(
                    scene,
                    MineFactoryExperimentLayout.routeGate,
                    Material.COPPER_BLOCK,
                    when (session.routeCycle) {
                        ROUTE_IDLE, ROUTE_WAITING -> "route-switch"
                        ROUTE_OUTBOUND -> "route-return"
                        else -> "route-moving"
                    },
                ),
                targetFactory.target(scene, MineFactoryExperimentLayout.routeBin, Material.POLISHED_DEEPSLATE, "route-return", glowing = false)?.copy(label = Component.empty()),
            )
            MineFactoryExperiment.COOLING -> visualScenes.coolingTargets(scene, session)
        }
    }

    /** Handles an experiment target before the normal objective action owner. */
    fun interact(
        scope: String,
        scene: MineExpeditionScene,
        state: MineExpeditionState,
        player: Player,
        id: String,
        now: Long,
        complete: (MineExpeditionStep) -> Boolean,
    ): Boolean {
        if (!supported(scene) || scope != scope(scene)) return false
        val experiment = MineFactoryExperiments.pending(state).firstOrNull() ?: return false
        val session = session(scope, scene, experiment, now)
        if (experiment != MineFactoryExperiment.DRIVE_REPAIR && !acceptInput(session, now)) return true
        return when (experiment) {
            MineFactoryExperiment.ROCK_JAM -> interactRock(scope, state, player, id, now, session, complete)
            MineFactoryExperiment.MOULD -> interactMould(scene, state, player, id, now, session, complete)
            MineFactoryExperiment.MANUAL_CRANE -> interactCrane(scope, state, player, id, now, session, complete)
            MineFactoryExperiment.DRIVE_REPAIR -> false
            MineFactoryExperiment.ROUTING -> interactRouting(scope, state, player, id, now, session, complete)
            MineFactoryExperiment.COOLING -> interactCooling(scene, state, player, id, now, session)
        }
    }

    /** Handles right-click air/block while the crane operator is already aiming. */
    fun interactAir(
        scope: String,
        scene: MineExpeditionScene,
        state: MineExpeditionState,
        player: Player,
        now: Long,
        complete: (MineExpeditionStep) -> Boolean,
    ): Boolean {
        if (!supported(scene) || scope != scope(scene) ||
            MineFactoryExperiments.pending(state).firstOrNull() != MineFactoryExperiment.MANUAL_CRANE
        ) return false
        val session = sessions[scope] ?: return false
        if (!session.grabbed || session.owner != player.uniqueId) return false
        if (!acceptInput(session, now)) return true
        val landing = session.landing ?: landingPosition(scene).also { session.landing = it }
        val source = sourcePosition(scene)
        val bounds = MineFactoryExperimentMotion.Bounds(
            minOf(source.x, landing.x) - 1.0,
            maxOf(source.x, landing.x) + 1.0,
            minOf(source.z, landing.z) - 2.0,
            maxOf(source.z, landing.z) + 2.0,
        )
        val aim = MineFactoryExperimentMotion.boundedAim(
            player.eyeLocation,
            player.eyeLocation.direction,
            MineFactoryExperimentMotion.AimPlane(landing.y - CRANE_LOAD_HEIGHT, bounds),
        )?.also { it.y = landing.y }
        if (aim == null || MineFactoryExperimentMotion.distanceSquared(aim, landing.toVector()) > CRANE_SNAP_RADIUS * CRANE_SNAP_RADIUS ||
            !craneSettled(session, landing)
        ) {
            player.sendActionBar(targetFactory.text("crane-miss", player))
            return true
        }
        val step = MineFactoryExperiments.resolve(state, MineFactoryExperiment.MANUAL_CRANE, now)
        if (step.accepted && complete(step)) {
            session.current = landing.clone()
            session.grabbed = false
            session.owner = null
        }
        return true
    }

    /** Advances side-job timers and emits bounded particle/sound feedback. */
    fun tick(
        scope: String,
        scene: MineExpeditionScene,
        state: MineExpeditionState,
        players: Collection<Player>,
        now: Long,
        complete: (Player, MineExpeditionStep) -> Boolean,
    ) {
        if (!supported(scene) || scope != scope(scene)) return
        val experiment = MineFactoryExperiments.pending(state).firstOrNull()
        val session = sessions[scope]
        if (session == null || experiment == null || session.experiment != experiment) {
            if (session != null) visuals.clear(scope)
            sessions.remove(scope)
            return
        }
        if (session.owner != null && players.none { it.uniqueId == session.owner }) {
            resetTransient(scope, session)
        }
        when (experiment) {
            MineFactoryExperiment.MOULD -> visualScenes.tickMould(scope, state, players, now, session, complete)
            MineFactoryExperiment.ROUTING -> tickRouting(scope, state, players, now, session, complete)
            MineFactoryExperiment.COOLING -> tickCooling(scope, state, players, now, session, complete)
            MineFactoryExperiment.MANUAL_CRANE -> tickCrane(scope, scene, state, players, now, session)
            else -> Unit
        }
    }

    /** Action-bar hint for the active experiment; null means ordinary guidance may speak. */
    fun hint(scope: String, player: Player, state: MineExpeditionState, now: Long): Component? {
        val experiment = MineFactoryExperiments.pending(state).firstOrNull() ?: return null
        val session = sessions[scope]
        return when (experiment) {
            MineFactoryExperiment.ROCK_JAM -> targetFactory.text(
                "rock-pry",
                player,
                mapOf("count" to Component.text(session?.pryCount ?: 0), "total" to Component.text(PRY_COUNT)),
            )
            MineFactoryExperiment.MOULD -> targetFactory.text("mould-select", player)
            MineFactoryExperiment.MANUAL_CRANE -> targetFactory.text(
                if (session?.grabbed == true) "crane-aim" else "crane-grab",
                player,
            )
            MineFactoryExperiment.DRIVE_REPAIR -> null
            MineFactoryExperiment.ROUTING -> targetFactory.text(
                when (session?.routeCycle) { ROUTE_RETURNING, ROUTE_DONE -> "route-moving"; ROUTE_OUTBOUND -> "route-return"; else -> "route-switch" },
                player,
            )
            MineFactoryExperiment.COOLING -> targetFactory.text(
                if (session?.hoseEquipped == true) "cooling-aim" else "cooling-pickup",
                player,
                mapOf("percent" to Component.text(((session?.sprayMillis ?: 0L) * 100 / COOLING_MILLIS).coerceIn(0L, 100L))),
            )
        }
    }

    /** Core center used by the parent machinery while the manual crane is aimed. */
    fun cranePosition(scope: String): Location? = sessions[scope]?.current?.clone()

    /** Exposed for the parent packet-offset hook; no target location is changed. */
    fun pryProgress(scope: String): Double = sessions[scope]?.let {
        MineFactoryExperimentMotion.pryProgress(it.pryCount, PRY_COUNT)
    } ?: 0.0

    /** Exposed for the parent packet-offset hook and tests. */
    fun routeProgress(scope: String): Double = sessions[scope]?.routeProgress ?: 0.0

    /** Dynamic ore pose for the parent marker transform hook; no cart or lease is created. */
    fun routeOrePosition(scope: String): Location? {
        val session = sessions[scope] ?: return null
        if (session.experiment != MineFactoryExperiment.ROUTING || session.routeCycle == ROUTE_IDLE) return null
        val from = beltPosition(session.scene, -2.0) ?: return null
        val buffer = beltPosition(session.scene, -3.4) ?: return null
        val inlet = beltPosition(session.scene, 3.8) ?: return null
        val phase = if (session.startedAt < 0L) 1.0 else session.routeProgress.coerceIn(0.0, 1.0)
        return when (session.routeCycle) {
            ROUTE_OUTBOUND -> interpolate(from, buffer, phase, 0.0)
            ROUTE_WAITING -> buffer
            ROUTE_RETURNING -> interpolate(buffer, inlet, phase, 0.0)
            ROUTE_DONE -> inlet
            else -> null
        }
    }

    fun coolingProgress(scope: String): Double =
        ((sessions[scope]?.sprayMillis?.toDouble() ?: 0.0) / COOLING_MILLIS.toDouble())
            .coerceIn(0.0, 1.0)

    fun release(player: Player) {
        sessions.entries.filter { it.value.owner == player.uniqueId }.forEach { (scope, session) ->
            resetTransient(scope, session)
        }
    }

    fun clear(scope: String) {
        sessions.remove(scope)
        visuals.clear(scope)
    }

    fun cleanup() {
        sessions.clear()
        visuals.cleanup()
    }

    private fun resetTransient(scope: String, session: MineFactoryExperimentSession) {
        session.owner = null
        when (session.experiment) {
            MineFactoryExperiment.ROCK_JAM -> {
                session.pryCount = 0
                markers.translate(scope, MineFactoryExperimentLayout.rockJam.id, Vector())
            }
            MineFactoryExperiment.MOULD -> {
                visualScenes.resetMould(scope, session)
            }
            MineFactoryExperiment.MANUAL_CRANE -> {
                session.grabbed = false
                session.current = sourcePosition(session.scene)
            }
            MineFactoryExperiment.COOLING -> {
                session.hoseEquipped = false
                session.sprayMillis = 0L
                visuals.remove(scope, HELD_NOZZLE_ID)
            }
            MineFactoryExperiment.ROUTING, MineFactoryExperiment.DRIVE_REPAIR -> Unit
        }
    }

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

    private fun interactMould(
        scene: MineExpeditionScene,
        state: MineExpeditionState,
        player: Player,
        id: String,
        now: Long,
        session: MineFactoryExperimentSession,
        complete: (MineExpeditionStep) -> Boolean,
    ): Boolean {
        val choice = (0..2).firstOrNull { MineFactoryExperimentLayout.mould(it).id == id }
            ?: return id == MineFactoryExperimentLayout.mouldSocket.id
        val plan = state.factoryExperiments ?: return true
        if (session.mouldChoice >= 0) return true
        if (choice != plan.product) {
            player.sendActionBar(targetFactory.text("mould-wrong", player))
            return true
        }
        session.owner = player.uniqueId
        session.mouldChoice = choice
        session.mouldStartedAt = now
        session.lastTick = now
        player.sendActionBar(targetFactory.text("mould-fitting", player))
        return true
    }

    private fun interactCrane(
        scope: String,
        state: MineExpeditionState,
        player: Player,
        id: String,
        now: Long,
        session: MineFactoryExperimentSession,
        complete: (MineExpeditionStep) -> Boolean,
    ): Boolean {
        if (id == MineFactoryExperimentLayout.craneControl.id) {
            if (session.owner != null && session.owner != player.uniqueId) return true
            session.owner = player.uniqueId
            session.grabbed = true
            session.lastTick = now
            if (session.current == null) session.current = sourcePosition(session.scene)
            if (session.landing == null) session.landing = landingPosition(session.scene)
            sound(session.current, Sound.BLOCK_CHAIN_PLACE, .7f)
            return true
        }
        if (id != MineFactoryExperimentLayout.craneLanding.id || !session.grabbed || session.owner != player.uniqueId) {
            return id == MineFactoryExperimentLayout.craneLanding.id
        }
        if (!craneSettled(session, session.landing ?: landingPosition(session.scene))) {
            player.sendActionBar(targetFactory.text("crane-miss", player))
            return true
        }
        val step = MineFactoryExperiments.resolve(state, MineFactoryExperiment.MANUAL_CRANE, now)
        if (step.accepted && complete(step)) {
            session.current = session.landing?.clone()
            session.grabbed = false
            session.owner = null
        }
        return true
    }

    private fun interactRouting(
        scope: String,
        state: MineExpeditionState,
        player: Player,
        id: String,
        now: Long,
        session: MineFactoryExperimentSession,
        complete: (MineExpeditionStep) -> Boolean,
    ): Boolean {
        if (id != MineFactoryExperimentLayout.routeGate.id) {
            return id == "control_crusher_left" || id == "crushed_output" || id == MineFactoryExperimentLayout.routeBin.id
        }
        if (session.owner != null && session.owner != player.uniqueId) return true
        session.owner = player.uniqueId
        when (session.routeCycle) {
            ROUTE_IDLE -> {
                session.routeCycle = ROUTE_OUTBOUND
                session.startedAt = now
                session.lastTick = now
                sound(targetFactory.location(session.scene, MineFactoryExperimentLayout.routeGate), Sound.BLOCK_LEVER_CLICK, .7f)
            }
            ROUTE_WAITING -> {
                session.routeCycle = ROUTE_RETURNING
                session.startedAt = now
                session.lastTick = now
                sound(targetFactory.location(session.scene, MineFactoryExperimentLayout.routeGate), Sound.BLOCK_LEVER_CLICK, 1.1f)
            }
            ROUTE_DONE -> resolveRouting(scope, state, player, now, session) { _, step -> complete(step) }
        }
        return true
    }

    private fun interactCooling(
        scene: MineExpeditionScene,
        state: MineExpeditionState,
        player: Player,
        id: String,
        now: Long,
        session: MineFactoryExperimentSession,
    ): Boolean {
        if (id == MineFactoryExperimentLayout.hoseNozzle.id || id == MineFactoryExperimentLayout.hoseReel.id) {
            if (session.owner != null && session.owner != player.uniqueId) return true
            session.owner = player.uniqueId
            session.hoseEquipped = true
            session.lastTick = now
            return true
        }
        return id == MineFactoryExperimentLayout.hotBearing.id
    }

    private fun tickRouting(
        scope: String,
        state: MineExpeditionState,
        players: Collection<Player>,
        now: Long,
        session: MineFactoryExperimentSession,
        complete: (Player, MineExpeditionStep) -> Boolean,
    ) {
        val owner = session.owner?.let { id -> players.firstOrNull { it.uniqueId == id } }
        if (session.routeCycle == ROUTE_DONE) {
            owner?.let { resolveRouting(scope, state, it, now, session, complete) }
            return
        }
        if (session.startedAt < 0L) return
        session.routeProgress = MineFactoryExperimentMotion.phase(session.startedAt, now, ROUTING_MILLIS)
        when (session.routeCycle) {
            ROUTE_OUTBOUND -> {
                markers.rotate(scope, MineFactoryExperimentLayout.routeGate.id, session.routeProgress * PI / 2.0)
                renderRouteOre(scope, session)
                if (session.routeProgress >= 1.0) {
                    session.routeCycle = ROUTE_WAITING
                    session.startedAt = -1L
                    owner?.sendActionBar(targetFactory.text("route-switch", owner))
                }
            }
            ROUTE_RETURNING -> {
                markers.rotate(scope, MineFactoryExperimentLayout.routeGate.id, (1.0 - session.routeProgress) * PI / 2.0)
                renderRouteOre(scope, session)
                if (session.routeProgress >= 1.0) {
                    session.routeCycle = ROUTE_DONE
                    session.startedAt = -1L
                    owner?.let { resolveRouting(scope, state, it, now, session, complete) }
                }
            }
        }
    }

    private fun resolveRouting(
        scope: String,
        state: MineExpeditionState,
        player: Player,
        now: Long,
        session: MineFactoryExperimentSession,
        complete: ((Player, MineExpeditionStep) -> Boolean)? = null,
    ) {
        val step = MineFactoryExperiments.resolve(state, MineFactoryExperiment.ROUTING, now)
        if (step.accepted && (complete == null || complete(player, step))) {
            visuals.remove(scope, ROUTE_ORE_ID)
            sessions.remove(scope)
        }
    }

    private fun renderRouteOre(scope: String, session: MineFactoryExperimentSession) {
        val at = routeOrePosition(scope) ?: return
        visuals.render(scope, ROUTE_ORE_ID, at, "ore_piece", .85f, glowing = false)
    }

    private fun interpolate(from: Location, to: Location, phase: Double, yOffset: Double): Location =
        from.clone().add(
            (to.x - from.x) * phase,
            yOffset + (to.y - from.y) * phase,
            (to.z - from.z) * phase,
        )

    private fun beltPosition(scene: MineExpeditionScene, x: Double): Location? =
        targetFactory.location(scene, MineFactoryExperimentLayout.Fixture(
            "route-material", "decor_conveyor_raw", Vector(x, 1.80, 0.0), "", 1f))

    private fun tickCooling(
        scope: String,
        state: MineExpeditionState,
        players: Collection<Player>,
        now: Long,
        session: MineFactoryExperimentSession,
        complete: (Player, MineExpeditionStep) -> Boolean,
    ) {
        renderBearingSmoke(session)
        if (!session.hoseEquipped) return
        val owner = session.owner?.let { id -> players.firstOrNull { it.uniqueId == id } }
        if (owner == null || !owner.isSneaking) {
            session.lastTick = now
            owner?.let { renderHeldNozzle(scope, it) }
            return
        }
        val elapsed = (now - session.lastTick).coerceIn(0L, MAX_TICK_MILLIS)
        session.lastTick = now
        val hit = renderCoolingJet(scope, session, owner, now)
        if (!hit) return
        session.sprayMillis = (session.sprayMillis + elapsed).coerceAtMost(COOLING_MILLIS)
        if (session.sprayMillis < COOLING_MILLIS) return
        val step = MineFactoryExperiments.resolve(state, MineFactoryExperiment.COOLING, now)
        if (step.accepted && complete(owner, step)) sessions.remove(scope)
    }

    private fun tickCrane(
        scope: String,
        scene: MineExpeditionScene,
        state: MineExpeditionState,
        players: Collection<Player>,
        now: Long,
        session: MineFactoryExperimentSession,
    ) {
        if (!session.grabbed || session.owner == null) return
        val owner = players.firstOrNull { it.uniqueId == session.owner } ?: return
        val current = session.current ?: sourcePosition(scene).also { session.current = it }
        val source = sourcePosition(scene)
        val landing = session.landing ?: landingPosition(scene).also { session.landing = it }
        val bounds = MineFactoryExperimentMotion.Bounds(
            minOf(source.x, landing.x) - 1.0,
            maxOf(source.x, landing.x) + 1.0,
            minOf(source.z, landing.z) - 2.0,
            maxOf(source.z, landing.z) + 2.0,
        )
        val aim = MineFactoryExperimentMotion.boundedAim(
            owner.eyeLocation,
            owner.eyeLocation.direction,
            MineFactoryExperimentMotion.AimPlane(landing.y - CRANE_LOAD_HEIGHT, bounds),
        )?.also { it.y = landing.y } ?: return
        // The broad painted pad catches the aim; the load itself still travels
        // smoothly to its center before a drop can be accepted.
        if (aim.distanceSquared(landing.toVector()) <= CRANE_SNAP_RADIUS * CRANE_SNAP_RADIUS) {
            aim.copy(landing.toVector())
        }
        val delta = (now - session.lastTick).coerceIn(0L, MAX_TICK_MILLIS)
        session.lastTick = now
        val smooth = MineFactoryExperimentMotion.smooth(current.toVector(), aim, CRANE_STEP_PER_TICK * (delta / 50.0))
        session.current = current.clone().apply { x = smooth.x; y = smooth.y; z = smooth.z }
        if (current.distanceSquared(session.current!!) > .001 &&
            (session.lastSound == Long.MIN_VALUE || now >= session.lastSound)) {
            sound(session.current, Sound.BLOCK_CHAIN_STEP, .65f)
            session.lastSound = now + 450L
        }
    }

    private fun craneSettled(session: MineFactoryExperimentSession, landing: Location): Boolean {
        val current = session.current ?: return false
        return MineFactoryExperimentMotion.distanceSquared(current.toVector(), landing.toVector()) <=
            .09 && kotlin.math.abs(current.y - landing.y) <= CRANE_LOWER_TOLERANCE
    }

    private fun renderCoolingJet(scope: String, session: MineFactoryExperimentSession, owner: Player, now: Long): Boolean {
        val nozzle = heldNozzleLocation(owner)
        val bearing = targetFactory.location(session.scene, MineFactoryExperimentLayout.hotBearing) ?: return false
        val direction = owner.eyeLocation.direction.clone().normalize()
        val hit = WorksiteWaterJet.hitTargets(
            listOf("bearing" to bearing.clone().add(0.0, .43, .08)),
            nozzle,
            direction,
            COOLING_RANGE,
            COOLING_RADIUS,
        ).isNotEmpty()
        renderHeldNozzle(scope, owner)
        if (plugin.config.getBoolean("ui.particles", true)) {
            WorksiteWaterJet.renderJet(nozzle.clone().add(direction.clone().multiply(.5)), direction, COOLING_RANGE, 0.35, 2)
            if (hit) {
                bearing.world.spawnParticle(Particle.CLOUD, bearing.clone().add(0.0, 0.7, 0.0), 2, .18, .18, .18, .01)
                bearing.world.spawnParticle(Particle.SMOKE, bearing.clone().add(0.0, 0.7, 0.0), 1, .12, .15, .12, .01)
            }
        }
        if (hit && plugin.config.getBoolean("ui.sounds", true) && (session.lastSound == Long.MIN_VALUE || now >= session.lastSound)) {
            session.lastSound = now + 300L
            bearing.world.playSound(bearing, Sound.BLOCK_FIRE_EXTINGUISH, .45f, 1.25f)
            owner.playSound(owner.location, Sound.ITEM_BUCKET_EMPTY, .25f, 1.3f)
        }
        return hit
    }

    private fun renderBearingSmoke(session: MineFactoryExperimentSession) {
        if (!plugin.config.getBoolean("ui.particles", true)) return
        val bearing = targetFactory.location(session.scene, MineFactoryExperimentLayout.hotBearing) ?: return
        val world = bearing.world ?: return
        world.spawnParticle(Particle.SMOKE, bearing.clone().add(0.0, .7, 0.0), 1, .12, .15, .12, .01)
    }

    private fun sound(at: Location?, sound: Sound, pitch: Float) {
        if (at != null && plugin.config.getBoolean("ui.sounds", true)) at.world.playSound(at, sound, .45f, pitch)
    }

    private fun renderHeldNozzle(scope: String, owner: Player) {
        val eye = owner.eyeLocation
        visuals.render(
            scope,
            HELD_NOZZLE_ID,
            heldNozzleLocation(owner),
            "hose_nozzle_held",
            HELD_NOZZLE_SCALE,
            eye.yaw,
            eye.pitch,
        )
    }

    private fun heldNozzleLocation(owner: Player): Location {
        val eye = owner.eyeLocation
        return eye.clone().add(eye.direction.clone().normalize().multiply(.5)).add(0.0, -.4, 0.0)
    }

    private fun session(scope: String, scene: MineExpeditionScene, experiment: MineFactoryExperiment, now: Long): MineFactoryExperimentSession {
        val existing = sessions[scope]
        if (existing != null && existing.experiment == experiment) return existing
        val created = MineFactoryExperimentSession(scene, experiment, lastTick = now)
        if (experiment == MineFactoryExperiment.MANUAL_CRANE) {
            created.current = sourcePosition(scene)
            created.landing = landingPosition(scene)
        }
        sessions[scope] = created
        return created
    }

    private fun sourcePosition(scene: MineExpeditionScene): Location =
        targetFactory.location(scene, MineFactoryExperimentLayout.Fixture("source", "pour_control", Vector(0.0, 2.2, 0.0), "", 1f))
            ?: scene.at(MineFactoryLine.effectiveStation(scene.plan, "pour_control")).add(0.0, 2.2, 0.0)

    private fun landingPosition(scene: MineExpeditionScene): Location =
        targetFactory.location(scene, MineFactoryExperimentLayout.Fixture("landing", "crane_load", Vector(0.0, 2.0, 0.0), "", 1f))
            ?: scene.at(MineFactoryLine.effectiveStation(scene.plan, "crane_load")).add(0.0, 2.0, 0.0)

    private fun acceptInput(session: MineFactoryExperimentSession, now: Long): Boolean {
        if (session.lastInput != Long.MIN_VALUE && now - session.lastInput < INPUT_COOLDOWN) return false
        session.lastInput = now
        return true
    }

    private fun supported(scene: MineExpeditionScene): Boolean =
        scene.kind == MineExpeditionKind.DEAD_FACTORY && scene.placement.geometryVersion >= 3 &&
            MineFactoryProgram.usesConnectedCrusherLine(scene.plan)

    private fun scope(scene: MineExpeditionScene): String = "${scene.zoneId}:${scene.sequence}:${scene.objectiveNonce}"

    private companion object {
        const val ROUTE_IDLE = 0
        const val ROUTE_OUTBOUND = 1
        const val ROUTE_WAITING = 2
        const val ROUTE_RETURNING = 3
        const val ROUTE_DONE = 4
        const val PRY_COUNT = 3
        const val INPUT_COOLDOWN = 180L
        const val ROUTING_MILLIS = 2_500L
        const val COOLING_MILLIS = 2_500L
        const val COOLING_RANGE = 3.5
        const val COOLING_RADIUS = .72
        const val MAX_TICK_MILLIS = 250L
        const val CRANE_STEP_PER_TICK = 0.22
        const val CRANE_SNAP_RADIUS = 1.8
        const val CRANE_LOAD_HEIGHT = .5
        const val CRANE_LOWER_TOLERANCE = .18
        const val HELD_NOZZLE_SCALE = .64f
        const val HELD_NOZZLE_ID = "held-hose-nozzle"
        const val ROUTE_ORE_ID = "route-ore"
    }
}
