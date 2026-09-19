package ru.ruscrafting.farms.paper.mine.working

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentPlacementReport
import ru.ruscrafting.farms.paper.worksite.*
import java.util.UUID
import java.util.logging.Level

/** Owns one foreground lateral-working journey; mining orders and rewards stay in their existing owners. */
internal class MineWorkingController(
    private val registry: MineRuntimeRegistry,
    private val placement: MineWorkingPlacementService,
    private val world: MineWorkingWorld,
    private val incidents: MineIncidentCoordinator,
    private val equipment: MineWorkingEquipment,
    private val presentation: MineWorkingPresentation,
    private val travel: WorksiteExpeditionTravel,
    private val access: WorksiteAccessPort,
    private val state: WorksiteStatePort,
    private val tasks: WorksiteTaskPort,
    private val clock: () -> Long,
) {
    private val pendingSaves = mutableSetOf<String>()
    private val retiring = mutableSetOf<String>()

    fun start(runtime: MineRuntime, type: MineIncidentType, now: Long): Boolean {
        if (transitioning(runtime)) return false
        return placement.search(runtime, type, now) { chosen ->
            if (runtime.state.incident != null) return@search
            val nonce = runtime.state.incidentCursor.toLong() + 1
            if (!world.prepare(runtime, type, chosen, nonce)) return@search
            val working = MineWorkingEngine.initial(type, chosen)
            val plan = MineWorkingLayout.plan(type, chosen)
            if (!incidents.start(runtime, type, total(type, plan), now, working = working)) world.startRestore(runtime)
        }
    }

    fun diagnostics(runtime: MineRuntime, type: MineIncidentType): MineIncidentPlacementReport = placement.diagnostics(runtime, type)

    fun tick(runtime: MineRuntime, now: Long) {
        if (runtime.settings.id in retiring) { retire(runtime); return }
        val incident = runtime.state.incident
        val working = incident?.working
        if (working == null) {
            if (world.hasScene(runtime)) retire(runtime)
            return
        }
        if (!world.isReady(runtime) || runtime.settings.id in pendingSaves) return
        val scene = world.scene(runtime) ?: return
        val reheated = MineWorkingEngine.reheat(working, now)
        if (reheated != working) {
            runtime.state = runtime.state.copy(incident = incident.copy(working = reheated))
            state.persistAsync()
        }
        presentation.reconcile(runtime, scene)
        if (working.stage == MineWorkingStage.HEAT) {
            runtime.region.world.players.filter { scene.inside(it.location) }.forEach { player ->
                presentation.stageHint(runtime, player, now)?.let(player::sendActionBar)
            }
        }
        if (working.stage == MineWorkingStage.TEST_TRACK) {
            val next = scene.plan.cartRoute.getOrNull(working.completed.size) ?: return
            val player = runtime.region.world.players.firstOrNull {
                participant(runtime, it) && scene.inside(it.location) && it.location.distanceSquared(next.location(it.world)) <= 9.0
            } ?: return
            if (access.allowInteraction("mine-working-cart:${runtime.settings.id}", CART_STEP_MILLIS)) {
                advance(runtime, player, working.completed.size, scene.plan.cartRoute.size)
            }
        }
    }

    fun onBreak(event: BlockBreakEvent): Boolean {
        val runtime = registry.at(event.block.location) ?: return false
        if (!world.protects(event.block.location)) return false
        event.isCancelled = true
        event.isDropItems = false
        event.expToDrop = 0
        val working = runtime.state.incident?.working ?: return true
        val scene = world.scene(runtime) ?: return true
        if (!participant(runtime, event.player) || !world.isReady(runtime) || runtime.settings.id in pendingSaves) return true
        if (!event.player.inventory.itemInMainHand.type.name.endsWith("_PICKAXE")) return true
        val targets = when (working.stage) {
            MineWorkingStage.EXCAVATE -> scene.plan.excavation
            MineWorkingStage.CLEAR_TRACK -> scene.plan.rubble
            else -> return true
        }
        val index = targets.indexOf(WorksitePosition(event.block.world.name, event.block.x, event.block.y, event.block.z))
        if (index < 0 || index in working.completed || !near(event.player, targets[index])) return true
        if (working.stage == MineWorkingStage.EXCAVATE) {
            val frontier = targets.indices.firstOrNull { it !in working.completed } ?: return true
            if (distanceAlong(working.placement, targets[index]) > distanceAlong(working.placement, targets[frontier])) {
                presentation.feedback(event.player, "next-section")
                return true
            }
        }
        advance(runtime, event.player, index, targets.size)
        return true
    }

    fun onInteract(event: PlayerInteractEvent): Boolean {
        val block = event.clickedBlock ?: return false
        val runtime = registry.at(block.location) ?: return false
        if (!world.protects(block.location)) return false
        // No vanilla furnace/container/workbench can export a temporary assembly or its contents.
        event.isCancelled = true
        val scene = world.scene(runtime) ?: return true
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return true
        val position = WorksitePosition(block.world.name, block.x, block.y, block.z)
        val target = presentation.targets(runtime, scene).firstOrNull {
            it.position == position || it.position.copy(y = it.position.y - 1) == position
        } ?: return true
        interact(runtime, event.player, target, scene)
        return true
    }

    fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean {
        val (zone, target) = presentation.target(event.rightClicked) ?: return false
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) return true
        val runtime = registry.byId(zone) ?: return true
        val scene = world.scene(runtime) ?: return true
        if (target.id == "entry") {
            if (travel.retains(event.player)) {
                equipment.clear(runtime, event.player.uniqueId)
                travel.exit(event.player)
            } else enter(runtime, scene, event.player)
        } else interact(runtime, event.player, target, scene)
        return true
    }

    private fun interact(runtime: MineRuntime, player: Player, target: MineWorkingTarget, scene: MineWorkingScene) {
        val working = runtime.state.incident?.working ?: return
        if (!participant(runtime, player) || !world.isReady(runtime) || !near(player, target.position) ||
            runtime.settings.id in pendingSaves || !access.allowInteraction("mine-working:${player.uniqueId}", 250L)) return
        when (working.stage) {
            MineWorkingStage.SUPPORT -> {
                val index = target.id.toIntOrNull() ?: return
                if (!equipment.has(runtime, player, "supports")) {
                    equipment.issue(runtime, player, "supports", Material.SPRUCE_LOG, cargo = false)
                    return
                }
                advance(runtime, player, index, scene.plan.supports.size)
            }
            MineWorkingStage.LAY_TRACK -> {
                val index = target.id.toIntOrNull() ?: return
                if (!equipment.has(runtime, player, "rails")) {
                    equipment.issue(runtime, player, "rails", Material.RAIL, cargo = false)
                    return
                }
                advance(runtime, player, index, scene.plan.rails.size)
            }
            MineWorkingStage.LOAD -> when (target.id) {
                "ore" -> equipment.issue(runtime, player, "ore", Material.RAW_IRON, cargo = true)
                "crusher" -> if (equipment.consume(runtime, player, "ore")) advance(runtime, player, 0, 1)
            }
            MineWorkingStage.CRUSH -> if (target.id == "crusher" &&
                access.allowInteraction("mine-working-crush:${runtime.settings.id}", CRUSH_STROKE_MILLIS)) {
                advance(runtime, player, working.completed.size, MineWorkingEngine.CRUSH_STROKES)
            }
            MineWorkingStage.HEAT -> if (target.id == "furnace") {
                if (MineWorkingEngine.canQuench(working, clock())) advance(runtime, player, 0, 1)
                else presentation.stageHint(runtime, player, clock())?.let(player::sendActionBar)
            }
            MineWorkingStage.SHIP -> when (target.id) {
                "output" -> equipment.issue(runtime, player, "billet", Material.IRON_INGOT, cargo = true)
                "shipping" -> if (equipment.consume(runtime, player, "billet")) advance(runtime, player, 0, 1)
            }
            MineWorkingStage.TEST_TRACK -> if (target.id.toIntOrNull() == working.completed.size && access.allowInteraction("mine-working-cart:${runtime.settings.id}", CART_STEP_MILLIS)) {
                advance(runtime, player, working.completed.size, scene.plan.cartRoute.size)
            }
            MineWorkingStage.EXCAVATE, MineWorkingStage.CLEAR_TRACK -> presentation.feedback(player, "stage.${working.stage.name.lowercase()}")
        }
    }

    private fun advance(runtime: MineRuntime, player: Player, target: Int, required: Int) {
        val incident = runtime.state.incident ?: return
        val working = incident.working ?: return
        val step = MineWorkingEngine.completeTarget(working, target, required, clock())
        if (!step.accepted || !pendingSaves.add(runtime.settings.id)) return
        if (step.finished || step.state.stage != working.stage) equipment.clear(runtime)
        val current = runtime.state.incident ?: run { pendingSaves.remove(runtime.settings.id); return }
        val next = runtime.state.copy(incident = current.copy(working = step.state))
        incidents.work(runtime, player, state = next)
        val sequence = runtime.state.sequence
        val nonce = incident.objectiveNonce
        val token = tasks.lifecycleToken()
        state.persistAsync().whenComplete { _, failure ->
            tasks.runSync(token) {
                pendingSaves.remove(runtime.settings.id)
                if (runtime.state.sequence != sequence ||
                    runtime.state.incident?.objectiveNonce?.let { it != nonce } == true) return@runSync
                if (failure != null) {
                    state.log(Level.SEVERE, "Mine working progress persistence failed zone=${runtime.settings.id} sequence=$sequence stage=${working.stage}", failure)
                    retire(runtime)
                    incidents.abort(runtime)
                    return@runSync
                }
                if (step.finished) retire(runtime) else {
                    world.project(runtime)
                    world.scene(runtime)?.let { presentation.reconcile(runtime, it) }
                }
                player.playSound(player.location, if (working.stage == MineWorkingStage.HEAT) Sound.BLOCK_FIRE_EXTINGUISH
                    else Sound.BLOCK_ANVIL_USE, 0.5f, 1.2f)
            }
        }
    }

    fun guardMovement(event: PlayerMoveEvent): Boolean {
        if (travel.isAuthorized(event.player, event.to)) return false
        val runtime = registry.snapshot().firstOrNull { world.scene(it)?.inside(event.to) == true }
        if (runtime == null) {
            travel.record(event.player)?.let { record ->
                registry.byId(record.zoneId)?.let { equipment.clear(it, event.player.uniqueId) }
            }
            if (travel.retains(event.player)) travel.reconcile(event.player, inside = false)
            return false
        }
        val scene = world.scene(runtime) ?: return false
        if (travel.record(event.player)?.let { it.zoneId == runtime.settings.id && it.sequence == runtime.state.sequence } == true &&
            runtime.state.incident?.working != null && world.isReady(runtime)) return false
        event.isCancelled = true
        if (event !is PlayerTeleportEvent && runtime.settings.id !in retiring && world.isReady(runtime)) enter(runtime, scene, event.player)
        else presentation.feedback(event.player, "preparing")
        return true
    }

    private fun enter(runtime: MineRuntime, scene: MineWorkingScene, player: Player) {
        if (!allowed(runtime, player) || !world.isReady(runtime) || runtime.settings.id in retiring) return
        val sequence = runtime.state.sequence
        val nonce = runtime.state.incident?.objectiveNonce ?: return
        travel.enter(WorksiteExpeditionTravel.EntryRequest(
            player, runtime.settings.id, sequence, runtime.settings.permission, scene.surface(), scene.blocks.start.clone(),
        )) { runtime.state.sequence == sequence && runtime.state.incident?.objectiveNonce == nonce &&
            runtime.settings.id !in retiring && world.isReady(runtime) }
    }

    fun guidanceHint(runtime: MineRuntime, player: Player, now: Long): net.kyori.adventure.text.Component? =
        if (participant(runtime, player)) presentation.stageHint(runtime, player, now)
        else presentation.entryHint(runtime, player)

    fun guidanceTargets(runtime: MineRuntime, player: Player): List<WorksiteGuidanceTarget> {
        val scene = world.scene(runtime) ?: return emptyList()
        val targets = if (participant(runtime, player)) presentation.targets(runtime, scene)
            else listOf(MineWorkingTarget("entry", scene.plan.entrance.copy(y = scene.floor + 1), "entry"))
        return targets.take(3).map { target -> WorksiteGuidanceTarget(
            "working_${target.id}", ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole("working"),
            target.position.location(runtime.region.world), org.bukkit.Color.fromRGB(255, 183, 65),
        ) }
    }

    fun retains(player: Player, destination: Location): Boolean = travel.isAuthorized(player, destination) ||
        travel.record(player)?.let { record -> registry.byId(record.zoneId)?.let { runtime ->
            runtime.state.sequence == record.sequence && runtime.state.incident?.working != null &&
                world.scene(runtime)?.inside(destination) == true
        } } == true
    fun protects(location: Location): Boolean = world.protects(location)
    fun recover(player: Player) = travel.recover(player)

    fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason) {
        registry.snapshot().filter { it.state.incident?.working != null }.forEach { equipment.clear(it, player.uniqueId) }
        when (reason) {
            WorksitePlayerReleaseReason.QUIT, WorksitePlayerReleaseReason.RELOAD, WorksitePlayerReleaseReason.SHUTDOWN,
            WorksitePlayerReleaseReason.OBJECTIVE_REPLACED -> { travel.exit(player); travel.quit(player) }
            WorksitePlayerReleaseReason.JOIN_STALE, WorksitePlayerReleaseReason.DEATH -> travel.quit(player)
            else -> travel.reconcile(player, inside = false)
        }
    }

    fun isActive(identity: ServiceItemIdentity): Boolean = equipment.isActive(identity)
    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) = equipment.release(playerId, identity, reason)

    fun transitioning(runtime: MineRuntime): Boolean = runtime.settings.id in pendingSaves ||
        runtime.settings.id in retiring || world.isRestoring(runtime)

    fun blocksOreSupply(runtime: MineRuntime): Boolean = world.hasScene(runtime) || transitioning(runtime)

    fun cancelPending(zone: String) = placement.cancel(zone)
    fun retire(runtime: MineRuntime) {
        placement.cancel(runtime.settings.id)
        retiring += runtime.settings.id
        equipment.clear(runtime)
        presentation.cleanup(runtime.settings.id)
        if (!travel.evacuate(runtime.settings.id)) return
        if (world.occupied(runtime)) return
        world.startRestore(runtime)
        retiring.remove(runtime.settings.id)
    }

    fun process(): Int = world.process()
    fun reconcileLoaded() {
        presentation.reconcileLoaded()
        world.reconcileLoaded()
        registry.snapshot().flatMap { it.region.world.players }.distinctBy { it.uniqueId }.forEach(::recover)
    }
    fun onChunkLoad(chunk: org.bukkit.Chunk) = world.onChunkLoad(chunk)
    fun beforeReload() {
        // The lifecycle supervisor discards old save callbacks. Their locks must
        // not survive and prevent progress in the reconfigured working.
        pendingSaves.clear()
        placement.clear()
    }
    fun cleanup() {
        registry.snapshot().forEach { runtime ->
            equipment.clear(runtime)
            presentation.cleanup(runtime.settings.id)
            travel.evacuate(runtime.settings.id)
        }
        placement.clear()
        pendingSaves.clear()
        retiring.clear()
        // Active incident state survives shutdown. Keep its complete journal
        // so startup can resume it; a partial restore would discard originals
        // while the saved incident still expects the whole working.
        world.clearQueues()
    }

    private fun participant(runtime: MineRuntime, player: Player): Boolean = allowed(runtime, player) &&
        travel.record(player)?.let { it.zoneId == runtime.settings.id && it.sequence == runtime.state.sequence } == true

    private fun allowed(runtime: MineRuntime, player: Player): Boolean =
        player.isOnline && !player.isDead && player.world === runtime.region.world &&
            !access.isAdminEditing(player) && access.hasAccess(player, runtime.settings.permission)

    private fun near(player: Player, position: WorksitePosition): Boolean = player.world.name == position.world &&
        player.location.distanceSquared(position.location(player.world)) <= INTERACTION_DISTANCE_SQUARED

    private fun distanceAlong(placement: MineWorkingPlacement, position: WorksitePosition): Int = when (placement.direction) {
        0 -> position.z - placement.entrance.z
        1 -> placement.entrance.x - position.x
        2 -> placement.entrance.z - position.z
        else -> position.x - placement.entrance.x
    }

    private fun total(type: MineIncidentType, plan: MineWorkingPlan): Int = when (type) {
        MineIncidentType.TUNNEL_DRIVE -> plan.excavation.size + plan.supports.size
        MineIncidentType.RAIL_EXTENSION, MineIncidentType.TRACK_DAMAGE -> plan.rubble.size + plan.rails.size + plan.cartRoute.size
        MineIncidentType.ORE_WORKSHOP -> MineWorkingEngine.BATCHES * (MineWorkingEngine.CRUSH_STROKES + 3)
        else -> error("Unsupported lateral working: $type")
    }

    private companion object {
        const val INTERACTION_DISTANCE_SQUARED = 25.0
        const val CRUSH_STROKE_MILLIS = 800L
        const val CART_STEP_MILLIS = 700L
    }
}
