package ru.ruscrafting.farms.paper.mine.incident.crystal

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftEvent
import ru.ruscrafting.farms.domain.mine.crystal.MineCrystalCompletion
import ru.ruscrafting.farms.domain.mine.crystal.MineCrystalPhase
import ru.ruscrafting.farms.domain.mine.crystal.MineCrystalState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.MineTransitionCoordinator
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCandidateStock
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.blockType
import ru.ruscrafting.farms.paper.mine.incident.entity.hasMineObjectiveMarkerSpace
import ru.ruscrafting.farms.paper.mine.incident.orderMineIncidentPositions
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
import ru.ruscrafting.farms.paper.mine.recovery.MineIncidentBlockJournal
import ru.ruscrafting.farms.paper.mine.recovery.MineTemporaryEnsureResult
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.logging.Level

/** Four distinct native crystals charge once; their durable objective then drives one journalled beam extraction. */
internal class MineCrystalResonanceIncident(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val incidents: MineIncidentCoordinator,
    private val transitions: MineTransitionCoordinator,
    private val journal: MineIncidentBlockJournal,
    recovery: MineBlockRecoveryController,
    private val state: WorksiteStatePort,
    private val locale: ArcFarmsLocale? = null,
    private val candidateStock: MineIncidentCandidateStock? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val planner = MineCrystalWallPlan(index, recovery)
    private data class Session(val sequence: Long, val nonce: Long, var lastEmissionAt: Long = Long.MIN_VALUE,
        var pendingCut: Boolean = false, var lastBeat: MineCrystalPhase? = null)
    private val sessions = mutableMapOf<String, Session>()
    private val dust = mutableMapOf<Pair<Int, Float>, Particle.DustOptions>()

    @Suppress("UNUSED_PARAMETER")
    fun start(runtime: MineRuntime, required: Int = 4, now: Long): Boolean {
        val available = (candidateStock?.candidates(runtime, MineIncidentType.CRYSTAL_RESONANCE)
            ?: index.loadedTargets(runtime.settings.id, MineAnchorRole.CRYSTAL).take(32)).filter { usable(runtime, it) }
        val ordered = orderMineIncidentPositions(runtime, available, 4, MineIncidentType.CRYSTAL_RESONANCE.ordinal + 1L)
        val selected = ordered.firstOrNull()?.let { anchor -> ordered.filter { kotlin.math.abs(it.y - anchor.y) <= 2 }.take(4) }
            .orEmpty()
        if (selected.size != 4) return false
        val plan = planner.select(runtime, selected) ?: run {
            state.log(Level.WARNING, "Mine crystal placement unavailable zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                "crystals=${selected.size} reason=no_safe_indexed_wall_on_crystal_floor")
            return false
        }
        val started = MineShiftEngine.startIncident(runtime.state, MineIncidentType.CRYSTAL_RESONANCE, 4, now)
        if (!started.accepted) return false
        val candidates = selected.mapIndexed { ordinal, position -> ObjectiveTargetCandidate("crystal_node_${ordinal + 1}",
            position, ObjectiveTargetRole("crystal_node"), ordinal.toLong()) }
        val objective = ObjectiveTargetPool.plan(WorksiteObjectiveKey(runtime.settings.id, "incident_crystal_resonance",
            started.state.sequence), 4, candidates)
        transitions.apply(runtime, started.copy(state = started.state.copy(
            incident = started.state.incident!!.copy(crystal = plan), objective = objective)), null)
        return true
    }

    fun hit(runtime: MineRuntime, targetId: String, player: Player, insideForgivingWindow: Boolean): Boolean {
        if (!active(runtime) || !insideForgivingWindow || !upgradeLegacy(runtime)) return false
        val incident = runtime.state.incident ?: return false
        if (incident.crystal?.phase != MineCrystalPhase.CHARGING) return false
        val objective = runtime.state.objective ?: return false
        val target = objective.target(targetId) ?: return false
        if (!usable(runtime, target.position) || player.world !== runtime.region.world || player.isDead ||
            player.gameMode == GameMode.SPECTATOR || player.location.distanceSquared(location(runtime, target.position)) > 36.0) return false
        val completed = ObjectiveTargetPool.complete(objective, targetId, player.uniqueId)
        if (!completed.accepted) return false
        val progress = MineShiftEngine.workIncident(runtime.state.copy(objective = completed.state), player.uniqueId)
        if (!progress.accepted) return false
        val full = completed.state.completed == 4
        val crystal = requireNotNull(incident.crystal).let {
            if (full) it.copy(phase = MineCrystalPhase.CONVERGING, phaseStartedAt = clock(), operator = player.uniqueId) else it
        }
        transitions.apply(runtime, progress.copy(state = progress.state.copy(
            incident = progress.state.incident!!.copy(crystal = crystal))), player)
        player.playSound(location(runtime, target.position), Sound.BLOCK_AMETHYST_BLOCK_CHIME, .9f, .8f + completed.state.completed * .2f)
        player.sendActionBar(render("mine.crystal.charged", player, mapOf("done" to Component.text(completed.state.completed))))
        return true
    }

    fun onInteract(event: PlayerInteractEvent): Boolean {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return false
        val block = event.clickedBlock ?: return false
        val runtime = registry.at(block.location) ?: return false
        if (!active(runtime)) return false
        val target = runtime.state.objective?.targets?.firstOrNull {
            it.position == WorksitePosition(block.world.name, block.x, block.y, block.z)
        } ?: return false
        event.isCancelled = true
        hit(runtime, target.id, event.player, true)
        return true
    }

    /** Retained for old marker entities until their ordinary reconcile removes them. */
    fun onInteractEntity(runtime: MineRuntime, targetId: String, player: Player, timingAccepted: Boolean = true): Boolean =
        hit(runtime, targetId, player, timingAccepted)

    fun nextTarget(runtime: MineRuntime): WorksitePosition? = runtime.state.objective?.targets
        ?.firstOrNull { it.status != ObjectiveTargetStatus.COMPLETED }?.position

    fun tick(runtime: MineRuntime, participants: Collection<Player>, now: Long) {
        if (!active(runtime)) { cleanup(runtime); return }
        if (!upgradeLegacy(runtime)) return
        val incident = runtime.state.incident ?: return
        val crystal = incident.crystal ?: return
        val session = session(runtime)
        val targets = runtime.state.objective?.targets.orEmpty()
        if (targets.any { it.position.blockType() == null } || crystal.cuts.any { it.position.blockType() == null }) return
        if (targets.any { it.position.blockType() != Material.AMETHYST_CLUSTER }) { abort(runtime, "crystal_changed"); return }
        if (session.lastBeat != crystal.phase) {
            session.lastBeat = crystal.phase
            val sound = when (crystal.phase) {
                MineCrystalPhase.CHARGING -> null
                MineCrystalPhase.CONVERGING -> Sound.BLOCK_BEACON_ACTIVATE
                MineCrystalPhase.DRILLING -> Sound.ENTITY_WARDEN_SONIC_BOOM
                MineCrystalPhase.EXTRACTING -> Sound.BLOCK_AMETHYST_BLOCK_RESONATE
            }
            if (sound != null) viewers(runtime, participants).forEach { it.playSound(wallLocation(runtime, crystal), sound, .8f, 1.0f) }
        }
        val age = (now - crystal.phaseStartedAt).coerceAtLeast(0)
        when (crystal.phase) {
            MineCrystalPhase.CHARGING -> Unit
            MineCrystalPhase.CONVERGING -> if (age >= MineCrystalGeometry.CONVERGE_MILLIS) phase(runtime, MineCrystalPhase.DRILLING, now)
            MineCrystalPhase.DRILLING -> drill(runtime, crystal, session, age, now)
            MineCrystalPhase.EXTRACTING -> if (age >= MineCrystalGeometry.EXTRACT_MILLIS && ensureBore(runtime, crystal)) {
                finish(runtime, participants)
            }
        }
        updateVisuals(runtime, participants, now)
    }

    private fun drill(runtime: MineRuntime, crystal: MineCrystalState, session: Session, age: Long, now: Long) {
        if (session.pendingCut) return
        val cutPositions = journal.positions(runtime, INCIDENT_ID).toSet()
        val next = crystal.cuts.indexOfFirst { it.position !in cutPositions }
        if (next == -1) {
            if (!ensureBore(runtime, crystal)) return
            if (age >= MineCrystalGeometry.DRILL_MILLIS) phase(runtime, MineCrystalPhase.EXTRACTING, now)
            return
        }
        if (age < 500 + next * 650L) return
        val cut = crystal.cuts[next]
        if (!planner.eligible(runtime, cut.position) || cut.position.blockType()?.name != cut.material) {
            abort(runtime, "wall_changed"); return
        }
        session.pendingCut = true
        journal.prepare(runtime, INCIDENT_ID, next, cut.position, Material.AIR).whenComplete { accepted, failure ->
            journal.runOnMain {
                if (!current(runtime, session)) return@runOnMain
                session.pendingCut = false
                if (failure != null || accepted != true) {
                    state.log(Level.WARNING, "Mine crystal drill failed zone=${runtime.settings.id} sequence=${session.sequence} " +
                        "nonce=${session.nonce} cell=$next", failure)
                    abort(runtime, "journal_rejected")
                } else {
                    val at = location(runtime, cut.position)
                    viewers(runtime, runtime.region.world.players).forEach { player ->
                        player.spawnParticle(Particle.BLOCK, at, 24, .36, .36, .36, .12,
                            Material.valueOf(cut.material).createBlockData())
                        player.playSound(at, Sound.BLOCK_DEEPSLATE_BREAK, 1.0f, .75f)
                    }
                }
            }
        }
    }

    private fun ensureBore(runtime: MineRuntime, crystal: MineCrystalState): Boolean {
        val results = crystal.cuts.map { journal.ensureTemporaryResult(it.position, Material.AIR) }
        if (MineTemporaryEnsureResult.REJECTED in results) {
            abort(runtime, "wall_recovery_conflict")
            return false
        }
        return MineTemporaryEnsureResult.PENDING !in results
    }

    private fun finish(runtime: MineRuntime, participants: Collection<Player>) {
        val before = runtime.state
        val result = MineCrystalCompletion.finish(before, runtime.rules(), runtime.currentOrder()?.normalizedRequirements.orEmpty())
        if (!result.accepted) return
        val operator = before.incident?.crystal?.operator?.let(org.bukkit.Bukkit::getPlayer)
        val recipients = viewers(runtime, participants)
        transitions.apply(runtime, result, operator)
        val credited = result.state.mined - before.mined
        recipients.forEach { player ->
            player.sendActionBar(render("mine.crystal.result", player, mapOf("count" to Component.text(credited))))
            player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.6f)
        }
        cleanup(runtime)
    }

    fun updateVisuals(runtime: MineRuntime, participants: Collection<Player>, now: Long) {
        if (!active(runtime)) { cleanup(runtime); return }
        val crystal = runtime.state.incident?.crystal ?: return
        val session = session(runtime)
        if (session.lastEmissionAt != Long.MIN_VALUE && now - session.lastEmissionAt < MineCrystalGeometry.EMISSION_MILLIS) return
        val viewers = viewers(runtime, participants)
        if (viewers.isEmpty()) return
        val targets = runtime.state.objective?.targets.orEmpty()
        if (targets.any { it.position.blockType() == null }) return
        session.lastEmissionAt = now
        val nodes = targets.map { MineCrystalWallPlan.tip(it.position) }
        val wall = MineCrystalGeometry.impact(crystal.cuts.map {
            MineCrystalWallPlan.contact(it.position, crystal.faceX, crystal.faceZ)
        }, crystal.phase, (now - crystal.phaseStartedAt).coerceAtLeast(0))
        val samples = MineCrystalGeometry.sample(nodes, targets.indices.filterTo(mutableSetOf()) {
            targets[it].status == ObjectiveTargetStatus.COMPLETED
        }, wall, crystal.phase, (now - crystal.phaseStartedAt).coerceAtLeast(0), now)
        samples.forEach { sample ->
            val at = Location(runtime.region.world, sample.position.x, sample.position.y, sample.position.z)
            val options = dust.getOrPut(sample.color to sample.size) { Particle.DustOptions(Color.fromRGB(sample.color), sample.size) }
            viewers.forEach { it.spawnParticle(Particle.DUST, at, 1, 0.0, 0.0, 0.0, 0.0, options) }
        }
        if (crystal.phase == MineCrystalPhase.EXTRACTING) {
            // Visible fragments stream out of the actual bore; they are particles, never collectible duplicate loot.
            val centre = MineCrystalGeometry.hub(nodes)
            val progress = ((now - crystal.phaseStartedAt).coerceAtLeast(0) % 600) / 600.0
            val at = org.joml.Vector3d(wall).lerp(centre, progress)
            viewers.forEach { it.spawnParticle(Particle.BLOCK, Location(runtime.region.world, at.x, at.y, at.z),
                5, .12, .12, .12, 0.0, Material.valueOf(crystal.cuts.first().material).createBlockData()) }
        }
    }

    fun hint(runtime: MineRuntime, player: Player): Component? {
        if (!active(runtime)) return null
        val phase = runtime.state.incident?.crystal?.phase ?: MineCrystalPhase.CHARGING
        return render("mine.crystal.${phase.name.lowercase()}", player,
            mapOf("done" to Component.text(runtime.state.objective?.completed ?: 0)))
    }

    fun protects(location: Location): Boolean = registry.at(location)?.let { runtime ->
        active(runtime) && runtime.state.incident?.crystal?.cuts?.any { it.position ==
            WorksitePosition(location.world.name, location.blockX, location.blockY, location.blockZ) } == true
    } == true

    fun cleanup(runtime: MineRuntime) { sessions.remove(runtime.settings.id) }
    fun cleanupAll() { sessions.clear(); dust.clear() }

    private fun upgradeLegacy(runtime: MineRuntime): Boolean {
        val incident = runtime.state.incident ?: return false
        if (incident.crystal != null) return true
        val objective = runtime.state.objective ?: return false
        // An unloaded chunk is pending. Preserve previous clicks and replace the obsolete 2-of-4 quota only when ready.
        if (objective.targets.any { it.position.blockType() == null }) return false
        val targets = objective.targets
        if (targets.size != 4 || targets.any { !usable(runtime, it.position) }) {
            abort(runtime, "legacy_crystals_unavailable"); return false
        }
        val plan = planner.select(runtime, targets.map { it.position }) ?: run { abort(runtime, "legacy_wall_unavailable"); return false }
        transitions.apply(runtime, EngineResult(runtime.state.copy(
            objective = objective.copy(required = 4, reserve = emptyList()),
            incident = incident.copy(required = 4, progress = targets.count { it.status == ObjectiveTargetStatus.COMPLETED }, crystal = plan),
        ), true), null)
        return true
    }

    private fun usable(runtime: MineRuntime, position: WorksitePosition): Boolean {
        if (position.blockType() != Material.AMETHYST_CLUSTER || !hasMineObjectiveMarkerSpace(position) ||
            !index.allowsEvent(position)) return false
        val block = runtime.region.world.getBlockAt(position.x, position.y, position.z)
        return runtime.region.contains(block.location) && (block.blockData as? Directional)?.facing == BlockFace.UP
    }

    private fun phase(runtime: MineRuntime, phase: MineCrystalPhase, now: Long) {
        val incident = runtime.state.incident ?: return
        val crystal = incident.crystal ?: return
        transitions.apply(runtime, EngineResult(runtime.state.copy(incident = incident.copy(
            crystal = crystal.copy(phase = phase, phaseStartedAt = now))), true, events = emptyList<MineShiftEvent>()), null)
    }

    private fun abort(runtime: MineRuntime, reason: String) {
        state.log(Level.WARNING, "Mine crystal retired zone=${runtime.settings.id} sequence=${runtime.state.sequence} reason=$reason")
        incidents.abort(runtime)
        cleanup(runtime)
    }

    private fun session(runtime: MineRuntime): Session {
        val nonce = runtime.state.incident!!.objectiveNonce
        return sessions[runtime.settings.id]?.takeIf { it.sequence == runtime.state.sequence && it.nonce == nonce }
            ?: Session(runtime.state.sequence, nonce).also { sessions[runtime.settings.id] = it }
    }
    private fun current(runtime: MineRuntime, session: Session): Boolean = active(runtime) &&
        runtime.state.sequence == session.sequence && runtime.state.incident?.objectiveNonce == session.nonce &&
        sessions[runtime.settings.id] === session
    private fun active(runtime: MineRuntime): Boolean = runtime.state.phase == MinePhase.INCIDENT &&
        runtime.state.incident?.type == MineIncidentType.CRYSTAL_RESONANCE
    private fun viewers(runtime: MineRuntime, players: Collection<Player>): List<Player> {
        val positions = runtime.state.objective?.targets.orEmpty().map { location(runtime, it.position) }
        return players.filter { it.isOnline && !it.isDead && it.world === runtime.region.world &&
            it.gameMode != GameMode.SPECTATOR && positions.any { p -> p.distanceSquared(it.location) <= 48.0 * 48.0 } }
    }
    private fun location(runtime: MineRuntime, p: WorksitePosition) = Location(runtime.region.world, p.x + .5, p.y + .5, p.z + .5)
    private fun wallLocation(runtime: MineRuntime, crystal: MineCrystalState) = location(runtime, crystal.cuts.first().position)
    private fun render(key: String, player: Player, values: Map<String, Component> = emptyMap()) =
        locale?.renderPath(key, player, values) ?: Component.empty()
    private companion object { const val INCIDENT_ID = "crystal_resonance" }
}
