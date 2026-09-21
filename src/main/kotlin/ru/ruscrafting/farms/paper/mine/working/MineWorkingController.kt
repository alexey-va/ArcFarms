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
import net.kyori.adventure.text.Component
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
    private val drive: MineDriveController,
    private val liftReturn: (MineRuntime) -> Location? = { null },
) {
    private val pendingSaves = mutableSetOf<String>()
    private val retiring = mutableSetOf<String>()
    private val drillOperators = mutableMapOf<String, UUID>()
    private val completionGrace = mutableMapOf<String, CompletionGrace>()

    private val preparedPlacements = mutableMapOf<Pair<String, MineIncidentType>, MineWorkingPlacement>()
    private var preparationLane = 0

    fun prewarm(runtime: MineRuntime, now: Long) {
        if (runtime.state.incident != null || transitioning(runtime)) return
        val types = listOf(MineIncidentType.TRACK_DAMAGE, MineIncidentType.TUNNEL_DRIVE, MineIncidentType.RAIL_EXTENSION)
        val type = types[preparationLane++ % types.size]
        val key = runtime.settings.id to type
        preparedPlacements[key]?.let { world.prewarm(runtime,type,it);return }
        placement.search(runtime, type, now) { preparedPlacements[key] = it; world.prewarm(runtime,type,it) }
    }

    fun start(runtime: MineRuntime, type: MineIncidentType, now: Long): Boolean {
        if (transitioning(runtime)) return false
        val chosen = preparedPlacements[runtime.settings.id to type] ?: return false
        val nonce = runtime.state.incidentCursor.toLong() + 1
        if (!world.prepare(runtime, type, chosen, nonce)) return false
        val working = MineWorkingEngine.initial(type, chosen)
        val plan = MineWorkingLayout.plan(type, chosen)
        return incidents.start(runtime, type, total(type, plan), now, working = working).also { if (!it) world.startRestore(runtime) }
    }

    fun diagnostics(runtime: MineRuntime, type: MineIncidentType): MineIncidentPlacementReport = placement.diagnostics(runtime, type)

    fun tick(runtime: MineRuntime, now: Long) {
        if (runtime.settings.id in retiring) { retire(runtime); return }
        completionGrace[runtime.settings.id]?.let { grace ->
            tickCompletionGrace(runtime, grace, now)
            return
        }
        val incident = runtime.state.incident
        val working = incident?.working
        if (working == null) {
            if (world.hasScene(runtime)) retire(runtime)
            return
        }
        if (world.preparationFailed(runtime)) { retire(runtime); incidents.abort(runtime); return }
        if (!world.isReady(runtime) || runtime.settings.id in pendingSaves) return
        val scene = world.scene(runtime) ?: return
        val reheated = MineWorkingEngine.reheat(working, now)
        if (reheated != working) {
            runtime.state = runtime.state.copy(incident = incident.copy(working = reheated))
            state.persistAsync()
        }
        presentation.reconcile(runtime, scene)
        if (incident.type == MineIncidentType.TUNNEL_DRIVE && MineDriveLayout.enabled(working.placement)) return
        val operator = drillOperators[runtime.settings.id]?.let(org.bukkit.Bukkit::getPlayer)
        val drillFace = presentation.drillPosition(runtime, scene)
        val drilling = working.stage == MineWorkingStage.EXCAVATE && operator != null &&
            participant(runtime, operator) && drillFace != null && operator.location.distanceSquared(drillFace.location(operator.world)) <= 36.0
        presentation.animateDrill(runtime, scene, drilling, now)
        if (drilling && runtime.settings.id !in pendingSaves &&
            access.allowInteraction("mine-working-drill:${runtime.settings.id}", DRILL_STEP_MILLIS)) {
            val index = scene.plan.excavation.indices.firstOrNull { it !in working.completed }
            if (index != null) advance(runtime, operator!!, index, scene.plan.excavation.size, drillSection = true)
        }
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

    fun updateDrive(now: Long) {
        registry.snapshot().filter { it.state.incident?.type == MineIncidentType.TUNNEL_DRIVE &&
            it.settings.id !in retiring && it.settings.id !in completionGrace }.forEach { runtime ->
            val working = runtime.state.incident?.working ?: return@forEach
            if (!MineDriveLayout.enabled(working.placement) || !world.isReady(runtime)) return@forEach
            val scene = world.scene(runtime) ?: return@forEach
            drive.tick(runtime, scene, now, { participant(runtime, it) }) { player, final ->
                val incident = runtime.state.incident ?: return@tick
                val sequence = runtime.state.sequence
                world.project(runtime, incident.type, final)
                incidents.work(runtime, player, amount = (incident.required - incident.progress).coerceAtLeast(1))
                beginCompletionGrace(runtime, sequence)
                presentation.feedback(player, "drive-complete")
                player.playSound(player.location, Sound.BLOCK_BELL_USE, .8f, 1.3f)
            }
        }
    }

    private fun runtimeAt(location: Location): MineRuntime? = registry.at(location)
        ?: registry.snapshot().firstOrNull { world.scene(it)?.inside(location) == true }

    fun canMine(player: Player, block: org.bukkit.block.Block): Boolean {
        val runtime = runtimeAt(block.location) ?: return false
        val working = runtime.state.incident?.working ?: return false
        if (block.type != Material.COBBLESTONE || working.stage != MineWorkingStage.CLEAR_TRACK || !world.isReady(runtime) || !participant(runtime, player) ||
            !player.inventory.itemInMainHand.type.name.endsWith("_PICKAXE")) return false
        val position = WorksitePosition(block.world.name, block.x, block.y, block.z)
        val target = world.scene(runtime)?.plan?.rubble?.indexOf(position) ?: -1
        return target >= 0 && target !in working.completed && near(player, position)
    }

    fun onBreak(event: BlockBreakEvent): Boolean {
        val runtime = runtimeAt(event.block.location) ?: return false
        if (!world.protects(event.block.location)) return false
        event.isCancelled = true
        event.isDropItems = false
        event.expToDrop = 0
        val working = runtime.state.incident?.working ?: return true
        val scene = world.scene(runtime) ?: return true
        if (!participant(runtime, event.player) || !world.isReady(runtime) || runtime.settings.id in pendingSaves) return true
        if (!event.player.inventory.itemInMainHand.type.name.endsWith("_PICKAXE")) return true
        if (event.block.type != Material.COBBLESTONE) return true
        val targets = when (working.stage) {
            MineWorkingStage.CLEAR_TRACK -> scene.plan.rubble
            else -> return true
        }
        val index = targets.indexOf(WorksitePosition(event.block.world.name, event.block.x, event.block.y, event.block.z))
        if (index < 0 || index in working.completed || !near(event.player, targets[index])) return true
        advance(runtime, event.player, index, targets.size)
        return true
    }

    fun onInteract(event: PlayerInteractEvent): Boolean {
        val block = event.clickedBlock ?: return false
        val runtime = runtimeAt(block.location) ?: return false
        if (!world.protects(block.location)) return false
        if (event.action == Action.LEFT_CLICK_BLOCK) {
            val working = runtime.state.incident?.working
            val scene = world.scene(runtime)
            val position = WorksitePosition(block.world.name, block.x, block.y, block.z)
            val target = scene?.plan?.rubble?.indexOf(position) ?: -1
            if (block.type == Material.COBBLESTONE && working?.stage == MineWorkingStage.CLEAR_TRACK && target >= 0 && target !in working.completed &&
                participant(runtime, event.player) && world.isReady(runtime) && near(event.player, position) &&
                event.player.inventory.itemInMainHand.type.name.endsWith("_PICKAXE")) {
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.ALLOW)
                event.setUseItemInHand(org.bukkit.event.Event.Result.ALLOW)
            } else event.isCancelled = true
            return true
        }
        // Right clicks on scene blocks must not open vanilla container inventories.
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
        drive.zone(event.rightClicked)?.let { zone ->
            event.isCancelled = true
            val runtime = registry.byId(zone) ?: return true
            if (event.hand == EquipmentSlot.HAND && participant(runtime, event.player) && world.isReady(runtime) &&
                runtime.state.incident?.type == MineIncidentType.TUNNEL_DRIVE) drive.mount(runtime, event.player)
            return true
        }
        val (zone, target) = presentation.target(event.rightClicked) ?: return false
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) return true
        val runtime = registry.byId(zone) ?: return true
        val scene = completionGrace[zone]?.scene ?: world.scene(runtime) ?: return true
        if(target.id=="return-lift") {
            if(allowed(runtime,event.player) && scene.inside(event.player.location) && near(event.player,target.position)) {
                drive.release(event.player)
                if(travel.evacuatePlayer(event.player,liftReturn(runtime) ?: scene.surface())) {
                    equipment.clear(runtime,event.player.uniqueId)
                    travel.reconcile(event.player,inside=false)
                }
            }
        } else if (target.id == "drill") {
            if (participant(runtime, event.player) && near(event.player, target.position)) {
                if (drillOperators[runtime.settings.id] == event.player.uniqueId) drillOperators.remove(runtime.settings.id)
                else drillOperators[runtime.settings.id] = event.player.uniqueId
            }
        } else if (target.id == "entry") {
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

    private fun advance(runtime: MineRuntime, player: Player, target: Int, required: Int, drillSection: Boolean = false) {
        val incident = runtime.state.incident ?: return
        val working = incident.working ?: return
        var step = MineWorkingEngine.completeTarget(working, target, required, clock())
        var amount = 1
        if (drillSection && step.accepted && working.stage == MineWorkingStage.EXCAVATE) {
            val excavation = world.scene(runtime)?.plan?.excavation.orEmpty()
            val layer = excavation.getOrNull(target)?.let { distanceAlong(working.placement, it) }
            excavation.indices.filter { it != target && it !in working.completed &&
                distanceAlong(working.placement, excavation[it]) == layer }.forEach { next ->
                val accepted = MineWorkingEngine.completeTarget(step.state, next, required, clock())
                if (accepted.accepted) { step = accepted; amount++ }
            }
        }
        if (!step.accepted || !pendingSaves.add(runtime.settings.id)) return
        if (step.finished || step.state.stage != working.stage) equipment.clear(runtime)
        val current = runtime.state.incident ?: run { pendingSaves.remove(runtime.settings.id); return }
        val next = runtime.state.copy(incident = current.copy(working = step.state))
        incidents.work(runtime, player, amount = amount, state = next)
        val sequence = runtime.state.sequence
        val nonce = incident.objectiveNonce
        if (step.finished) {
            // The coordinator clears the incident on the final action. Project
            // that last state before the incident lookup disappears.
            world.project(runtime, incident.type, step.state)
            beginCompletionGrace(runtime, sequence)
        }
        val token = tasks.lifecycleToken()
        state.persistAsync().whenComplete { _, failure ->
            tasks.runSync(token) {
                pendingSaves.remove(runtime.settings.id)
                val currentNonce = runtime.state.incident?.objectiveNonce
                val stale = when {
                    runtime.state.sequence != sequence -> true
                    step.finished -> currentNonce != null && currentNonce != nonce
                    else -> currentNonce != nonce
                }
                if (stale) return@runSync
                if (failure != null) {
                    state.log(Level.SEVERE, "Mine working progress persistence failed zone=${runtime.settings.id} sequence=$sequence stage=${working.stage}", failure)
                    forceCleanup(runtime)
                    if (!step.finished) incidents.abort(runtime)
                    return@runSync
                }
                if (!step.finished) {
                    world.project(runtime)
                    world.scene(runtime)?.let { presentation.reconcile(runtime, it) }
                }
                player.playSound(player.location, if (working.stage == MineWorkingStage.HEAT) Sound.BLOCK_FIRE_EXTINGUISH
                    else Sound.BLOCK_ANVIL_USE, 0.5f, 1.2f)
            }
        }
    }

    fun guardMovement(event: PlayerMoveEvent): Boolean {
        if (event.player.gameMode == org.bukkit.GameMode.SPECTATOR) {
            travel.reconcile(event.player, inside = false)
            return false
        }
        if (travel.isAuthorized(event.player, event.to)) return false
        // Same-world drive: boarding and walking do not require a portal receipt.
        if (registry.snapshot().any { isDrive(it) && allowed(it, event.player) &&
                world.isReady(it) && world.scene(it)?.inside(event.to) == true }) return false
        val runtime = registry.snapshot().firstOrNull { candidate ->
            (world.scene(candidate) ?: completionGrace[candidate.settings.id]?.scene)?.inside(event.to) == true
        }
        if (runtime == null) {
            travel.record(event.player)?.let { record ->
                registry.byId(record.zoneId)?.let { equipment.clear(it, event.player.uniqueId) }
            }
            if (travel.retains(event.player)) travel.reconcile(event.player, inside = false)
            return false
        }
        val scene = world.scene(runtime) ?: completionGrace[runtime.settings.id]?.scene ?: return false
        val grace = completionGrace[runtime.settings.id]
        if (grace != null) {
            // A completed scene is a short-lived public set. Let ordinary walking
            // pass through it; teleport guards still prevent entering by teleport.
            if (event !is PlayerTeleportEvent) return false
            event.isCancelled = true
            return true
        }
        if (travel.record(event.player)?.let { it.zoneId == runtime.settings.id && it.sequence == runtime.state.sequence } == true &&
            runtime.state.incident?.working != null && world.isReady(runtime)) return false
        if (event !is PlayerTeleportEvent && runtime.settings.id !in retiring && world.isReady(runtime) &&
            event.player.location.distanceSquared(scene.surface()) <= INTERACTION_DISTANCE_SQUARED) {
            // A normal walk into the authored entrance is already a valid entry.
            // Register the durable on-foot lease without cancelling the move;
            // cancellation made the entrance feel like an invisible wall.
            enter(runtime, scene, event.player, event.to)
            return false
        }
        event.isCancelled = true
        presentation.feedback(event.player, "preparing")
        return true
    }

    private fun enter(
        runtime: MineRuntime,
        scene: MineWorkingScene,
        player: Player,
        destination: Location = player.location.clone(),
    ) {
        if (!allowed(runtime, player) || !world.isReady(runtime) || runtime.settings.id in retiring) return
        val sequence = runtime.state.sequence
        val nonce = runtime.state.incident?.objectiveNonce ?: return
        travel.enterOnFoot(WorksiteExpeditionTravel.EntryRequest(
            player, runtime.settings.id, sequence, runtime.settings.permission, scene.surface().also { it.yaw = player.location.yaw; it.pitch = player.location.pitch }, destination.clone(),
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
        drive.release(player)
        drillOperators.entries.removeIf { it.value == player.uniqueId }
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

    fun transitioning(runtime: MineRuntime): Boolean = runtime.settings.id in pendingSaves || drive.busy(runtime.settings.id) ||
        runtime.settings.id in retiring || runtime.settings.id in completionGrace || world.isRestoring(runtime)

    /** Used by admin replacement to end a completed scene without waiting for grace. */
    fun forceCleanup(runtime: MineRuntime) {
        val grace = completionGrace.remove(runtime.settings.id)
        if (grace == null) retire(runtime) else retire(runtime, grace.scene)
    }

    fun blocksOreSupply(runtime: MineRuntime): Boolean = world.hasScene(runtime) || transitioning(runtime)

    fun cancelPending(zone: String) = placement.cancel(zone)
    fun retire(runtime: MineRuntime) {
        val grace = completionGrace.remove(runtime.settings.id)
        retire(runtime, grace?.scene)
    }

    private fun retire(runtime: MineRuntime, retained: MineWorkingScene?) {
        val retainedScene = retained ?: world.scene(runtime)
        placement.cancel(runtime.settings.id)
        retiring += runtime.settings.id
        equipment.clear(runtime)
        drillOperators.remove(runtime.settings.id)
        drive.cleanup(runtime.settings.id)
        presentation.cleanup(runtime.settings.id)
        val evacuated = if (retainedScene == null) {
            travel.evacuate(runtime.settings.id)
        } else {
            var success = travel.evacuate(runtime.settings.id, retainedScene.blocks.sequence)
            retainedScene.blocks.world.players.filter { retainedScene.inside(it.location) }.forEach { player ->
                if (!travel.evacuatePlayer(player, retainedScene.surface())) success = false
                else travel.reconcile(player, inside = false)
            }
            success
        }
        if (!evacuated) return
        if (retainedScene != null) {
            if (world.occupied(retainedScene)) return
            world.release(retainedScene)
            world.startRestore(retainedScene)
        } else {
            if (!world.hasScene(runtime) || world.occupied(runtime)) {
                if (!world.hasScene(runtime)) retiring.remove(runtime.settings.id)
                return
            }
            world.startRestore(runtime)
        }
        retiring.remove(runtime.settings.id)
    }

    fun process(): Int = world.process()
    fun reconcileLoaded() {
        drive.reconcileLoaded()
        presentation.reconcileLoaded()
        world.reconcileLoaded()
        registry.snapshot().flatMap { it.region.world.players }.distinctBy { it.uniqueId }.forEach(::recover)
    }
    fun onChunkLoad(chunk: org.bukkit.Chunk) = world.onChunkLoad(chunk)
    fun beforeReload() {
        drive.cleanup()
        // The lifecycle supervisor discards old save callbacks. Their locks must
        // not survive and prevent progress in the reconfigured working.
        pendingSaves.clear()
        placement.clear(); preparedPlacements.clear()
    }
    fun cleanup() {
        drive.cleanup()
        registry.snapshot().forEach { runtime ->
            equipment.clear(runtime)
            presentation.cleanup(runtime.settings.id)
            travel.evacuate(runtime.settings.id)
        }
        placement.clear(); preparedPlacements.clear()
        pendingSaves.clear()
        retiring.clear()
        completionGrace.clear()
        drillOperators.clear()
        // Active incident state survives shutdown. Keep its complete journal
        // so startup can resume it; a partial restore would discard originals
        // while the saved incident still expects the whole working.
        world.clearQueues()
        presentation.close()
    }

    private fun isDrive(runtime: MineRuntime): Boolean = runtime.state.incident?.let {
        it.type == MineIncidentType.TUNNEL_DRIVE && it.working?.placement?.let(MineDriveLayout::enabled) == true
    } == true

    private fun participant(runtime: MineRuntime, player: Player): Boolean = allowed(runtime, player) &&
        (isDrive(runtime) || travel.record(player)?.let { it.zoneId == runtime.settings.id && it.sequence == runtime.state.sequence } == true)

    private fun allowed(runtime: MineRuntime, player: Player): Boolean =
        player.isOnline && !player.isDead && player.gameMode != org.bukkit.GameMode.SPECTATOR && player.world === runtime.region.world &&
            !access.isAdminEditing(player) && access.hasAccess(player, runtime.settings.permission)

    private fun near(player: Player, position: WorksitePosition): Boolean = player.world.name == position.world &&
        player.location.distanceSquared(position.location(player.world)) <= INTERACTION_DISTANCE_SQUARED

    private fun beginCompletionGrace(runtime: MineRuntime, sequence: Long) {
        val scene = world.retainedScene(runtime.settings.id, sequence)
        if (scene == null) {
            retire(runtime)
            return
        }
        val completedAt = clock()
        completionGrace[runtime.settings.id] = CompletionGrace(
            sequence = sequence,
            scene = scene,
            completedAt = completedAt,
            warningAt = completedAt + HARD_DEADLINE_MILLIS - WARNING_MILLIS,
            deadlineAt = completedAt + HARD_DEADLINE_MILLIS,
        )
        drive.cleanup(runtime.settings.id)
        presentation.cleanup(runtime.settings.id)
        presentation.reconcileReturn(runtime,scene,true)
        world.retain(scene)
    }

    private fun tickCompletionGrace(runtime: MineRuntime, grace: CompletionGrace, now: Long) {
        presentation.reconcileReturn(runtime,grace.scene,true)
        if (now >= grace.deadlineAt) {
            forceCleanup(runtime)
            return
        }
        if (!grace.warningSent && now >= grace.warningAt) {
            grace.warningSent = true
            grace.scene.blocks.world.players.filter { nearCompletion(grace.scene, it) }.forEach { player ->
                presentation.feedback(player, "closing-warning", mapOf("seconds" to Component.text(WARNING_SECONDS)))
            }
        }
        if (now < grace.completedAt + GRACE_MILLIS) return
        if (grace.scene.blocks.world.players.none { nearCompletion(grace.scene, it) }) forceCleanup(runtime)
    }

    private fun nearCompletion(scene: MineWorkingScene, player: Player): Boolean {
        if (!player.isOnline || player.world !== scene.blocks.world) return false
        if (player.location.distanceSquared(scene.surface()) <= COMPLETION_DISTANCE_SQUARED) return true
        return scene.plan.footprint.any { player.location.distanceSquared(it.location(scene.blocks.world)) <= COMPLETION_DISTANCE_SQUARED }
    }

    private fun distanceAlong(placement: MineWorkingPlacement, position: WorksitePosition): Int = when (placement.direction) {
        0 -> position.z - placement.entrance.z
        1 -> placement.entrance.x - position.x
        2 -> placement.entrance.z - position.z
        else -> position.x - placement.entrance.x
    }

    private fun total(type: MineIncidentType, plan: MineWorkingPlan): Int = when (type) {
        MineIncidentType.TUNNEL_DRIVE -> if (MineDriveLayout.enabled(plan.placement)) MineDriveLayout.CONTRIBUTION_BUDGET else plan.excavation.size + plan.supports.size
        MineIncidentType.RAIL_EXTENSION, MineIncidentType.TRACK_DAMAGE -> plan.rubble.size + plan.rails.size + plan.cartRoute.size
        MineIncidentType.ORE_WORKSHOP -> MineWorkingEngine.BATCHES * (MineWorkingEngine.CRUSH_STROKES + 3)
        else -> error("Unsupported lateral working: $type")
    }

    private companion object {
        const val INTERACTION_DISTANCE_SQUARED = 25.0
        const val CRUSH_STROKE_MILLIS = 800L
        const val CART_STEP_MILLIS = 700L
        const val DRILL_STEP_MILLIS = 1_500L
        const val GRACE_MILLIS = 60_000L
        const val HARD_DEADLINE_MILLIS = 5 * 60_000L
        const val WARNING_MILLIS = 15_000L
        const val WARNING_SECONDS = 15
        const val COMPLETION_DISTANCE_SQUARED = 64.0
    }

    private data class CompletionGrace(
        val sequence: Long,
        val scene: MineWorkingScene,
        val completedAt: Long,
        val warningAt: Long,
        val deadlineAt: Long,
        var warningSent: Boolean = false,
    )
}
