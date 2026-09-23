package ru.ruscrafting.farms.paper.mine.expedition

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionStage
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionState
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionStep
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryExperiment
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryExperiments
import kotlin.math.abs

/**
 * Physical six-button controller for the connected-factory crane.
 *
 * The billet pose is the only transient machine state here. The parent
 * machinery consumes [position] and moves its existing billet and chain;
 * this owner never spawns a second cargo assembly and never aims at a player
 * ray. A valid completion is submitted only after a real lowering tick lands
 * within the destination radius.
 */
internal class MineFactoryCraneControls(
    private val plugin: Plugin,
    private val markers: MineExpeditionMarkers,
    private val targets: MineFactoryExperimentTargets,
) {
    private enum class Phase {
        SOURCE,
        RAISING,
        RAISED,
        MOVING,
        LOWERING,
        LANDED,
    }

    private data class Session(
        val scene: MineExpeditionScene,
        val source: Location,
        val destination: Location,
        val consoleYaw: Float,
        var current: Location,
        var target: Location,
        var lastTick: Long,
        var owner: java.util.UUID? = null,
        var phase: Phase = Phase.SOURCE,
        var lastInput: Long = Long.MIN_VALUE,
        var missedLanding: Boolean = false,
        var completionAttempted: Boolean = false,
        var lastSound: Long = Long.MIN_VALUE,
        var driveX: Double = 0.0,
        var driveZ: Double = 0.0,
        var velocity: Vector = Vector(),
    )

    private val sessions = linkedMapOf<String, Session>()

    /** Stable physical button targets; the body is decorative and non-actionable. */
    fun targets(scene: MineExpeditionScene, state: MineExpeditionState, now: Long): List<MineExpeditionMarkers.Target> {
        if (!eligible(scene, state)) {
            sessions.remove(scope(scene))
            return emptyList()
        }
        val scope = scope(scene)
        session(scope, scene, now)
        val body = targets.target(
            scene,
            MineFactoryCraneLayout.console,
            org.bukkit.Material.POLISHED_DEEPSLATE,
            "crane-console",
            interactive = false,
            glowing = false,
            model = MineFactoryCraneLayout.console.model,
        )
        val landing = targets.target(
            scene,
            MineFactoryExperimentLayout.craneLanding,
            org.bukkit.Material.POLISHED_DEEPSLATE,
            "crane-landing",
            interactive = false,
            glowing = false,
            model = MineFactoryExperimentLayout.craneLanding.model,
        )
        val buttons = MineFactoryCraneLayout.buttons.mapNotNull { button ->
            targets.target(
                scene,
                button.fixture(),
                button.material,
                button.key,
                interactive = true,
                glowing = false,
                model = button.model,
            )?.let { if (button.id in setOf("crane_lift", "crane_lower")) it else it.copy(label = Component.empty()) }
        }
        return listOfNotNull(body, landing) + buttons
    }

    /**
     * Handles one physical button. Movement is queued here and applied by
     * [tick], keeping all motion bounded and ensuring lower is real physics
     * rather than a callback-time teleport.
     */
    fun interact(
        scope: String,
        scene: MineExpeditionScene,
        state: MineExpeditionState,
        player: Player,
        id: String,
        now: Long,
        complete: (MineExpeditionStep) -> Boolean,
    ): Boolean {
        if (!eligible(scene, state) || scope != scope(scene)) return false
        val button = MineFactoryCraneLayout.button(id) ?: return false
        val session = session(scope, scene, now)
        if (!acceptInput(session, now)) return true
        session.owner = player.uniqueId
        when (button.id) {
            "crane_lift" -> lift(session)
            "crane_lower" -> lower(session)
            "crane_left" -> horizontal(session, -1.0, 0.0)
            "crane_right" -> horizontal(session, 1.0, 0.0)
            // The operator faces +Z and looks toward -Z, so forward is -Z.
            "crane_forward" -> horizontal(session, 0.0, -1.0)
            "crane_back" -> horizontal(session, 0.0, 1.0)
        }
        click(session, button)
        // Completion is intentionally deferred to tick after descent. Keeping
        // the callback in the signature lets the parent retain one uniform
        // interaction adapter without allowing a button press to skip motion.
        return true
    }

    /** Advance one bounded physical step and submit completion after landing. */
    fun tick(
        scope: String,
        scene: MineExpeditionScene,
        state: MineExpeditionState,
        players: Collection<Player>,
        now: Long,
        complete: (Player, MineExpeditionStep) -> Boolean,
    ) {
        if (!eligible(scene, state) || scope != scope(scene)) return
        val session = sessions[scope] ?: return
        val owner = session.owner?.let { id -> players.firstOrNull { it.uniqueId == id } }
        if (session.owner != null && owner == null) {
            resetSafe(session)
            return
        }
        val delta = (now - session.lastTick).coerceIn(0L, MAX_TICK_MILLIS)
        session.lastTick = now
        if (session.owner == null || delta <= 0L) return
        if (session.phase == Phase.RAISED || session.phase == Phase.MOVING) {
            travel(session, delta / 1_000.0)
            boundedSound(session, now, owner)
            return
        }
        val distance = when (session.phase) {
            Phase.RAISING, Phase.LOWERING -> VERTICAL_SPEED * delta / 1_000.0
            else -> 0.0
        }
        val next = MineFactoryExperimentMotion.smooth(session.current.toVector(), session.target.toVector(), distance)
        session.current = session.current.clone().apply {
            x = next.x
            y = next.y
            z = next.z
        }
        if (session.phase == Phase.RAISING && reached(session.current, session.target, VERTICAL_TOLERANCE)) {
            session.phase = Phase.RAISED
        } else if (session.phase == Phase.LOWERING && reached(session.current, session.target, VERTICAL_TOLERANCE)) {
            session.phase = Phase.LANDED
            val atLanding = horizontalDistanceSquared(session.current, session.destination) <= LANDING_RADIUS * LANDING_RADIUS
            if (!atLanding) {
                session.missedLanding = true
                owner?.sendActionBar(targets.text("crane-miss", owner))
            } else if (!session.completionAttempted) {
                session.completionAttempted = true
                val step = MineFactoryExperiments.resolve(state, MineFactoryExperiment.MANUAL_CRANE, now)
                if (step.accepted && owner != null && complete(owner, step)) {
                    sessions.remove(scope)
                } else {
                    // A rejected state commit must retain ownership and pose;
                    // the operator can lift and retry without duplicating cargo.
                    session.missedLanding = true
                    owner?.sendActionBar(targets.text("crane-miss", owner))
                }
            }
        }
        if (session.phase == Phase.RAISED || session.phase == Phase.MOVING) {
            boundedSound(session, now, owner)
        }
    }

    fun position(scope: String): Location? = sessions[scope]?.current?.clone()

    fun hint(scope: String, player: Player): Component? {
        val session = sessions[scope] ?: return targets.text("crane-grab", player)
        return when {
            session.missedLanding -> targets.text("crane-miss", player)
            session.phase == Phase.LOWERING -> targets.text("crane-lower", player)
            session.phase == Phase.SOURCE || session.phase == Phase.LANDED -> targets.text("crane-lift", player)
            session.phase == Phase.RAISED || session.phase == Phase.MOVING -> targets.text("crane-move", player)
            else -> targets.text("crane-grab", player)
        }
    }

    fun release(player: Player) {
        sessions.values.filter { it.owner == player.uniqueId }.forEach(::resetSafe)
    }

    fun clear(scope: String) {
        sessions.remove(scope)
    }

    fun cleanup() {
        sessions.clear()
    }

    private fun eligible(scene: MineExpeditionScene, state: MineExpeditionState): Boolean =
        scene.kind == MineExpeditionKind.DEAD_FACTORY &&
            scene.placement.geometryVersion >= 3 &&
            scene.plan.stations.containsKey("pour_control") &&
            scene.plan.stations.containsKey("crane_load") &&
            MineFactoryExperiments.pending(state).firstOrNull() == MineFactoryExperiment.MANUAL_CRANE

    private fun session(scope: String, scene: MineExpeditionScene, now: Long): Session = sessions[scope]
        ?.takeIf { it.scene === scene }
        ?: run {
            val source = source(scene)
            val destination = destination(scene)
            Session(scene, source, destination, (targets.location(scene, MineFactoryCraneLayout.console)?.yaw ?: 0f) + MineFactoryCraneLayout.YAW,
                source.clone(), source.clone(), lastTick = now).also { sessions[scope] = it }
        }

    private fun source(scene: MineExpeditionScene): Location = targets.location(
        scene,
        MineFactoryExperimentLayout.Fixture("crane-source", "pour_control", Vector(0.0, 2.2, 0.0), "", 1f),
    ) ?: scene.at(scene.plan.stations.getValue("pour_control")).add(0.0, 2.2, 0.0)

    private fun destination(scene: MineExpeditionScene): Location = targets.location(
        scene,
        MineFactoryExperimentLayout.Fixture("crane-destination", "crane_load", Vector(0.0, 2.0, 0.0), "", 1f),
    ) ?: scene.at(scene.plan.stations.getValue("crane_load")).add(0.0, 2.0, 0.0)

    private fun lift(session: Session) {
        if (session.phase == Phase.RAISING || session.phase == Phase.RAISED || session.phase == Phase.MOVING) return
        session.missedLanding = false
        session.completionAttempted = false
        session.target = session.current.clone().apply { y = topY(session) }
        session.phase = Phase.RAISING
    }

    private fun lower(session: Session) {
        if (session.phase != Phase.RAISED) {
            plugin.server.getPlayer(session.owner ?: return)?.let { player ->
                player.sendActionBar(targets.text("crane-brake", player))
            }
            return
        }
        session.missedLanding = false
        session.completionAttempted = false
        session.target = session.current.clone().apply { y = landingY(session) }
        session.phase = Phase.LOWERING
    }

    /**
     * Land on the authored support when over source/destination and on the
     * factory floor elsewhere.  The floor level is derived from the authored
     * source offset, avoiding a world scan that could mistake a cavern roof
     * for the landing surface.
     */
    private fun landingY(session: Session): Double {
        if (abs(session.current.x - session.source.x) <= SOURCE_SUPPORT_X &&
            abs(session.current.z - session.source.z) <= SOURCE_SUPPORT_Z) {
            return session.source.y
        }
        if (abs(session.current.x - session.destination.x) <= DESTINATION_SUPPORT_X &&
            abs(session.current.z - session.destination.z) <= DESTINATION_SUPPORT_Z) {
            return session.destination.y
        }
        return session.source.y - FLOOR_CENTER_OFFSET
    }

    /** A second press on the same direction cuts its motor; the trolley coasts to rest. */
    private fun horizontal(session: Session, localDx: Double, localDz: Double) {
        if (session.phase != Phase.RAISED && session.phase != Phase.MOVING) return
        if (localDx != 0.0) session.driveX = if (session.driveX == localDx) 0.0 else localDx
        if (localDz != 0.0) session.driveZ = if (session.driveZ == localDz) 0.0 else localDz
        session.phase = Phase.MOVING
        session.missedLanding = false
        session.completionAttempted = false
    }

    private fun travel(session: Session, seconds: Double) {
        val radians = Math.toRadians(session.consoleYaw.toDouble())
        val desired = Vector(
            session.driveX * kotlin.math.cos(radians) + session.driveZ * kotlin.math.sin(radians),
            0.0,
            -session.driveX * kotlin.math.sin(radians) + session.driveZ * kotlin.math.cos(radians),
        )
        if (desired.lengthSquared() > 1.0) desired.normalize()
        desired.multiply(HORIZONTAL_SPEED)
        val previous = session.velocity.clone()
        session.velocity = MineFactoryExperimentMotion.smooth(previous, desired, ACCELERATION * seconds)
        // Trapezoidal integration preserves a visible stopping distance at different tick rates.
        val displacement = previous.add(session.velocity).multiply(seconds / 2.0)
        val minX = minOf(session.source.x, session.destination.x) - BOUNDARY_MARGIN
        val maxX = maxOf(session.source.x, session.destination.x) + BOUNDARY_MARGIN
        val minZ = minOf(session.source.z, session.destination.z) - BOUNDARY_MARGIN
        val maxZ = maxOf(session.source.z, session.destination.z) + BOUNDARY_MARGIN
        val x = session.current.x + displacement.x
        val z = session.current.z + displacement.z
        session.current.x = x.coerceIn(minX, maxX)
        session.current.z = z.coerceIn(minZ, maxZ)
        if (x != session.current.x || z != session.current.z) {
            session.driveX = 0.0
            session.driveZ = 0.0
            session.velocity.zero()
        }
        session.phase = if (session.velocity.lengthSquared() < 1.0e-8 &&
            session.driveX == 0.0 && session.driveZ == 0.0) Phase.RAISED else Phase.MOVING
    }

    private fun topY(session: Session): Double = maxOf(session.source.y, session.destination.y) + LIFT_HEIGHT

    private fun resetSafe(session: Session) {
        session.owner = null
        session.driveX = 0.0
        session.driveZ = 0.0
        session.velocity.zero()
        session.current = session.source.clone()
        session.target = session.source.clone()
        session.phase = Phase.SOURCE
        session.missedLanding = false
        session.completionAttempted = false
    }

    private fun acceptInput(session: Session, now: Long): Boolean {
        if (session.lastInput != Long.MIN_VALUE && now - session.lastInput < INPUT_COOLDOWN) return false
        session.lastInput = now
        return true
    }

    private fun click(session: Session, button: MineFactoryCraneLayout.Button) {
        if (!plugin.config.getBoolean("ui.sounds", true)) return
        session.current.world.playSound(session.current, Sound.BLOCK_LEVER_CLICK, .45f, when (button.id) {
            "crane_lift" -> 1.2f
            "crane_lower" -> .75f
            else -> 1.0f
        })
    }

    private fun boundedSound(session: Session, now: Long, owner: Player?) {
        if (owner == null || !plugin.config.getBoolean("ui.sounds", true) ||
            (session.lastSound != Long.MIN_VALUE && now - session.lastSound < SOUND_COOLDOWN)
        ) return
        session.lastSound = now
        owner.world.playSound(session.current, Sound.BLOCK_CHAIN_STEP, .25f, .9f)
    }

    private fun reached(current: Location, target: Location, tolerance: Double): Boolean =
        abs(current.y - target.y) <= tolerance

    private fun horizontalDistanceSquared(first: Location, second: Location): Double =
        (first.x - second.x) * (first.x - second.x) + (first.z - second.z) * (first.z - second.z)

    private fun scope(scene: MineExpeditionScene): String = "${scene.zoneId}:${scene.sequence}:${scene.objectiveNonce}"

    private companion object {
        const val LIFT_HEIGHT = 3.0
        const val BOUNDARY_MARGIN = 2.0
        const val LANDING_RADIUS = .35
        const val SOURCE_SUPPORT_X = 2.7
        const val SOURCE_SUPPORT_Z = 1.7
        const val DESTINATION_SUPPORT_X = 1.0
        const val DESTINATION_SUPPORT_Z = 1.25
        const val FLOOR_CENTER_OFFSET = 1.7
        const val HORIZONTAL_SPEED = 2.0
        const val ACCELERATION = 2.0
        const val VERTICAL_SPEED = 3.0
        const val VERTICAL_TOLERANCE = .06
        const val INPUT_COOLDOWN = 120L
        const val SOUND_COOLDOWN = 220L
        const val MAX_TICK_MILLIS = 250L
    }
}
