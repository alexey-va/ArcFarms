package ru.ruscrafting.farms.paper.mine.expedition

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineWorkshopHeat
import ru.ruscrafting.farms.domain.mine.expedition.*
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.worksite.*
import java.util.UUID

/** Off-site incidents share travel, journals, HUD and domain checkpoints with ordinary mine work. */
internal class MineExpeditionController(
    plugin: Plugin,
    private val registry: MineRuntimeRegistry,
    private val world: MineExpeditionWorld,
    private val incidents: MineIncidentCoordinator,
    private val travel: WorksiteExpeditionTravel,
    private val surfacePoint: (MineRuntime) -> Location?,
    private val access: WorksiteAccessPort,
    private val statePort: WorksiteStatePort,
    private val locale: ArcFarmsLocale?,
    private val clock: () -> Long,
    tasks: WorksiteTaskPort? = null,
) {
    private data class Driver(val playerId: UUID, val stage: MineExpeditionStage, var nextStepAt: Long, var started: Boolean = false)
    private val markers = MineExpeditionMarkers(plugin)
    private val factoryPresentation=MineFactoryPresentation(plugin,markers)
    private val editor = tasks?.let { MineFurnishingEditor(plugin,it,locale,world::allScenes,markers) }
    private val actions = MineExpeditionActions(plugin, locale)
    private val experiments = MineFactoryExperimentsController(plugin, markers, locale, ::fixturePosition)
    private val descentPresentation = MineDescentPresentation(plugin, markers)
    private val machinery = MineExpeditionMachinery(plugin, world::project) { scene,id,p ->
        editor?.position(scene,id,p) ?: p
    }
    private val drivers = mutableMapOf<String, Driver>()
    private val projectedStage = mutableMapOf<String, MineExpeditionStage>()
    private data class FactoryResult(val until: Long, val model: String)
    private val factoryResults = mutableMapOf<Long, FactoryResult>()
    private var lastRetentionTick = 0L

    fun configured(runtime: MineRuntime): Boolean = surfacePoint(runtime) != null
    fun available(type: MineIncidentType): Boolean = world.available(type)

    fun start(runtime: MineRuntime, type: MineIncidentType, now: Long): Boolean {
        if (world.allScenes().any { editor?.locked(it) == true && it.kind.name == type.name }) return false
        if (!MineExpeditionEngine.supports(type) || !configured(runtime) || !world.available(type)) return false
        if (!incidents.start(runtime, type, MineExpeditionEngine.required(type), now)) return false
        tick(runtime, now)
        return true
    }

    fun tick(runtime: MineRuntime, now: Long) {
        val incident = runtime.state.incident ?: return clearGateway(runtime)
        if (!MineExpeditionEngine.supports(incident.type)) return clearGateway(runtime)
        val surface = surfacePoint(runtime) ?: return
        val scene = world.ensure(runtime, surface, now)
        world.failure(runtime)?.let {
            retire(runtime)
            incidents.abort(runtime)
            return
        }
        markers.reconcile(gatewayScope(runtime), listOf(MineExpeditionMarkers.Target("enter", surface,
            Material.LODESTONE, render(if (scene == null) "preparing" else "enter"))))
        if (scene?.ready == true) reconcileScene(runtime, scene, now)
    }

    fun process(): Int = world.process()

    /** Fast presentation lane also keeps large scene construction bounded across ticks. */
    fun updateVisuals(now: Long) {
        world.process()
        editor?.tick()
        registry.snapshot().forEach { runtime ->
            val scene = world.scene(runtime) ?: return@forEach
            if (scene.ready) reconcileScene(runtime, scene, now)
        }
        if (now - lastRetentionTick >= 1_000L) {
            lastRetentionTick = now
            reconcileRetained(now)
        }
    }

    private fun reconcileScene(runtime: MineRuntime, scene: MineExpeditionScene, now: Long) {
        val current = runtime.state.incident?.expedition ?: return
        val scope = scope(scene)
        val players = scene.world.players.filter { participant(runtime, scene, it) }
        markers.reconcile("exit:${scene.journalSequence}", exitMarkers(scene))
        if (!machinery.sync(scene, current)) return
        experiments.tick(scope, scene, current, players, now) { player, step -> commit(runtime, scene, player, current, step) }
        if (runtime.state.incident?.expedition != current) return
        machinery.manualCrane(scene, experiments.cranePosition(scope))
        val pendingExperiment = MineFactoryExperiments.pending(current).isNotEmpty()
        if (MineFactoryProgram.usesConnectedCrusherLine(scene.plan)) {
            for (id in listOf("charge_transfer", "roller_transfer")) {
                machinery.turn(scene, id, actions.transferPhase(scope, id))
            }
        }
        if(current.stage==MineExpeditionStage.FACTORY_CRANE || current.stage==MineExpeditionStage.FACTORY_INSTALL) {
            val id=if(current.stage==MineExpeditionStage.FACTORY_CRANE) "crane_control" else "assembly_socket"
            machinery.turn(scene,id,actions.operationPhase(scope,id,now))
        }
        machinery.animate(scene, current, now, actions.claimed(scope, current.stage, 0))
        projectStage(scene, current)
        val targets = targets(scene, current)
        if (!pendingExperiment) actions.tick(scope, scene, current, targets, players, now,
            { player, step -> commit(runtime, scene, player, current, step) },
            { id, radians ->
                markers.rotate(scope, id, if(id.startsWith("control_") || id=="pour_console") {
                    if(radians>0.0) Math.PI else 0.0
                } else radians)
                machinery.turn(scene, if(id=="pour_console") "pour_control" else id, radians)
            })
        if (runtime.state.incident?.expedition != current) return
        val driver = drivers[scope]
        if (driver != null && driver.stage == current.stage && now >= driver.nextStepAt) {
            val player = players.firstOrNull { it.uniqueId == driver.playerId }
            val center = machinery.center(scene, current)
            if (player == null || center == null || player.location.distanceSquared(center) > 144.0 ||
                (driver.started && !machinery.riding(scene, player))) {
                drivers.remove(scope)
                machinery.park(scene)
            } else {
                driver.nextStepAt = now + MOTION_STEP_MILLIS
                val step = MineExpeditionEngine.advanceMotion(current, machinery.motionSteps(scene, current))
                if (step.accepted && machinery.advance(scene, current, step.state, players)) {
                    driver.started = true
                    if (!commit(runtime, scene, player, current, step)) {
                        machinery.rollback(scene, current)
                        drivers.remove(scope)
                    }
                }
            }
        }
        val latest = runtime.state.incident?.expedition ?: return
        if (latest.stage == MineExpeditionStage.FACTORY_HEAT &&
            !MineFactoryProgram.usesConnectedCrusherLine(scene.plan)) {
            val reheated = MineExpeditionEngine.reheat(latest, now)
            if (reheated != latest) {
                runtime.state = runtime.state.copy(incident = runtime.state.incident!!.copy(expedition = reheated))
                statePort.persistAsync()
            }
        }
        val activeTargets = (if (pendingExperiment) emptyList() else targets(scene, latest)).filterNot { target ->
            target.target >= 0 && (
                target.interaction == MineExpeditionInteraction.PICKUP && actions.claimed(scope, latest.stage, target.target) ||
                (MineFactoryProgram.usesConnectedCrusherLine(scene.plan) || modernDescent(scene)) &&
                    target.interaction == MineExpeditionInteraction.DELIVER && !actions.claimed(scope, latest.stage, target.target))
        }
        val experimentTargets = experiments.targets(scene, latest, now)
        val visibleTargets = activeTargets.map { target -> marker(scene, latest, target, now) } + experimentTargets
        markers.reconcile("furnish:${scene.journalSequence}", MineExpeditionFurnishings.targets(scene,editor,visibleTargets.mapTo(hashSetOf()) { it.id }))
        markers.reconcile(scope, visibleTargets)
        val pourSignal = if (actions.pourReady(scope, now)) Material.LIME_CONCRETE else Material.YELLOW_CONCRETE
        markers.signal(scope, "pour_console", pourSignal)
        markers.signal("furnish:${scene.journalSequence}", "pour_console", Material.GRAY_CONCRETE)
        factoryPresentation.tick(scene,latest,scope,now,machinery.turns(scene),
            actions.operationPhase(scope,"control_crusher_left",now)>0.0,
            actions.factoryHeat(scope))
        descentPresentation.tick(scene, latest, scope, now, machinery.turns(scene),
            moving = drivers[scope]?.started == true,
            pumpStarting = actions.operationPhase(scope, "core_valve_2", now) > 0.0,
            cellClaimed = actions.claimed(scope, MineExpeditionStage.DESCENT_POWER_CELLS, 0))
    }

    private fun marker(scene: MineExpeditionScene, state: MineExpeditionState, target: MineExpeditionObjective,
        now: Long): MineExpeditionMarkers.Target {
        val label = when {
            modernDescent(scene) -> when (target.id) {
                "counterweight_0" -> "control.descent-brake-jam"
                "counterweight_1" -> "control.descent-tension"
                "counterweight_2" -> "control.descent-brake-release"
                "power_supply" -> "control.descent-cell-source"
                "power_socket" -> "control.descent-cell-socket"
                "core_valve_0" -> "control.descent-intake"
                "core_valve_1" -> "control.descent-prime"
                "core_valve_2" -> "control.descent-pump-start"
                "drive" -> if (state.stage == MineExpeditionStage.DESCENT_ENGINE) "control.descent-up" else "control.descent-down"
                else -> "control.${target.id.replace(Regex("_[0-9]+$"), "")}"
            }
            MineFactoryProgram.usesConnectedCrusherLine(scene.plan) && target.id.startsWith("repair_supply_") -> "control.repair-pickup"
            MineFactoryProgram.usesConnectedCrusherLine(scene.plan) && target.id=="crusher_repair" -> "control.repair-install"
            MineFactoryProgram.usesConnectedCrusherLine(scene.plan) && state.stage==MineExpeditionStage.FACTORY_COAL -> when(target.id) {
                "fuel_supply" -> "control.charge-cart"
                "crusher_feed" -> "control.charge-load"
                "control_crusher_left" -> "control.charge-crush"
                "crushed_output" -> "control.mix-cart"
                else -> "control.mix-load"
            }
            state.stage == MineExpeditionStage.FACTORY_WATER && target.id.startsWith("control_pump_") -> "control.pump-start"
            state.stage == MineExpeditionStage.FACTORY_WATER && target.id.startsWith("control_crusher_") -> "control.crusher-start"
            state.stage == MineExpeditionStage.FACTORY_CRANE -> "control.crane-start"
            state.stage == MineExpeditionStage.FACTORY_HEAT -> {
                val heat = actions.factoryHeat(scope(scene))
                val connected = MineFactoryProgram.usesConnectedCrusherLine(scene.plan)
                when {
                    heat?.ready == true || (!connected && MineExpeditionEngine.canFinishHeat(state, now)) -> "heat-ready"
                    connected && heat?.running == true -> "heat-progress"
                    else -> "heat-start"
                }
            }
            state.stage == MineExpeditionStage.FACTORY_COAL && target.interaction == MineExpeditionInteraction.DELIVER -> "control.fuel-progress"
            state.stage == MineExpeditionStage.FACTORY_COAL && target.interaction == MineExpeditionInteraction.PICKUP -> "control.fuel-cart"
            target.interaction == MineExpeditionInteraction.POUR -> actions.pourLabel(scope(scene), now)
            target.interaction == MineExpeditionInteraction.CRANK -> "control.turn"
            target.interaction == MineExpeditionInteraction.VALVE -> "control.valve"
            else -> "control.${target.id.replace(Regex("_[0-9]+$"), "")}" 
        }
        val factoryHeat = actions.factoryHeat(scope(scene))
        val heatReady = if (MineFactoryProgram.usesConnectedCrusherLine(scene.plan)) {
            factoryHeat?.ready == true
        } else factoryHeat?.ready ?: MineExpeditionEngine.canFinishHeat(state, now)
        val material = if (state.stage == MineExpeditionStage.FACTORY_HEAT && heatReady)
            Material.LIME_DYE else MineExpeditionActions.material(target.material)
        val markerLabel = if (MineFactoryProgram.usesConnectedCrusherLine(scene.plan) &&
            state.stage == MineExpeditionStage.FACTORY_HEAT) {
            val heat = factoryHeat ?: MineWorkshopHeat()
            val heatKey = when {
                heat.ready -> "factory-heat-ready"
                heat.running -> "heat-progress"
                else -> "heat-start"
            }
            val path = if (heat.ready) "mine.expedition.$heatKey" else "mine.working.workshop.$heatKey"
            locale?.renderPath(path, null, mapOf(
                "progress" to Component.text((heat.progress * 100).toInt()),
                "temperature" to Component.text((heat.progress * 100).toInt()),
            )) ?: render(label, values = progressValues(state, now, factoryHeat) + actions.pourValues(scope(scene),now))
        } else render(label, values = progressValues(state, now, factoryHeat) + actions.pourValues(scope(scene),now))
        val fixture = MineExpeditionFurnishings.fixtures(scene).firstOrNull { it.id == target.id }
        return MineExpeditionMarkers.Target(target.id, scene.at(target.position), material,
            markerLabel, target.interaction == MineExpeditionInteraction.BREAK,
            model=if (target.interaction == MineExpeditionInteraction.BREAK || target.id=="drive") null
                else fixture?.model ?: MineExpeditionFurnishings.model(target.id,scene.kind,modernDescent(scene)),
            modelScale=fixture?.scale ?: 1f,
            yaw=editor?.yaw(scene,target.id) ?: fixture?.yaw ?: 0)
    }

    private fun exitMarkers(scene: MineExpeditionScene): List<MineExpeditionMarkers.Target> =
        MineExpeditionPortalPolicy.returnStationIds(scene.plan).map { id -> MineExpeditionMarkers.Target("return_$id", scene.station(id),
            Material.RECOVERY_COMPASS, render("exit"), glowing = scene.reserved || scene.completedAt != 0L) }

    fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean {
        if (actions.owns(event.rightClicked)) { event.isCancelled = true; return true }
        if (event.isCancelled) return false
        val identity = markers.ownedIdentity(event.rightClicked) ?: return false
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND || !near(event.player, event.rightClicked.location, 5.0)) return true
        val scope = identity.substringBeforeLast('/')
        val id = identity.substringAfterLast('/')
        if (scope.startsWith("exit:")) {
            world.allScenes().firstOrNull { "exit:${it.journalSequence}" == scope }?.let { exit(event.player, it) }
            return true
        }
        registry.snapshot().firstOrNull { gatewayScope(it) == scope }?.let { runtime ->
            if (id == "enter") enter(runtime, event.player)
            return true
        }
        world.retainedScenes().firstOrNull { scope(it) == scope }?.let { scene ->
            if (id.startsWith("return_")) { exit(event.player, scene); return true }
            val runtime = registry.byId(scene.zoneId) ?: return true
            interact(runtime, scene, event.player, id)
        }
        return true
    }

    fun onInteract(event: PlayerInteractEvent): Boolean {
        val block = event.clickedBlock ?: return false
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return false
        val scene = world.allScenes().firstOrNull { it.contains(block.location) } ?: return false
        val target = markers.nearest(block.location, "exit:${scene.journalSequence}")
            ?: markers.nearest(block.location, scope(scene)) ?: return false
        // Lever controls accept a direct hit on their own small interaction volume.
        if(target.model=="machine_console" || target.model=="factory_crane_panel" ||
            target.model?.startsWith("factory_crane_button_")==true) return false
        event.isCancelled = true
        if (!near(event.player, target.location, 5.0)) return true
        if (target.id.startsWith("return_")) exit(event.player, scene)
        else registry.byId(scene.zoneId)?.let { interact(it, scene, event.player, target.id) }
        return true
    }

    private fun interact(runtime: MineRuntime, scene: MineExpeditionScene, player: Player, id: String) {
        val current = runtime.state.incident?.expedition ?: return
        if (world.scene(runtime) !== scene || !scene.ready || !participant(runtime, scene, player) ||
            !access.allowInteraction("mine-expedition:${player.uniqueId}", 200L)) return
        if (MineFactoryExperiments.pending(current).isNotEmpty()) {
            val target = experiments.targets(scene, current, clock()).firstOrNull { it.id == id } ?: return
            if (!near(player, target.location, 5.0)) return
            experiments.interact(scope(scene), scene, current, player, id, clock()) { step -> commit(runtime, scene, player, current, step) }
            return
        }
        val target = targets(scene, current).firstOrNull { it.id == id } ?: return
        if (!near(player, scene.at(target.position), 5.0)) return
        actions.interact(scope(scene), scene, current, player, target, clock(),
            { step -> commit(runtime, scene, player, current, step) },
            { drivers[scope(scene)] = Driver(player.uniqueId, current.stage, clock()) })
    }

    fun onBreak(event: BlockBreakEvent): Boolean {
        val scene = world.allScenes().firstOrNull { it.contains(event.block.location) } ?: return false
        if (scene.reserved || scene.completedAt != 0L || access.isAdminEditing(event.player)) return false
        event.isCancelled = true
        event.isDropItems = false
        event.expToDrop = 0
        val runtime = registry.byId(scene.zoneId) ?: return true
        val current = runtime.state.incident?.expedition ?: return true
        if (world.scene(runtime) !== scene || !participant(runtime, scene, event.player)) return true
        val target = targets(scene, current).firstOrNull { it.interaction == MineExpeditionInteraction.BREAK &&
            it.position == scene.local(event.block.location) } ?: return true
        if (!near(event.player, event.block.location, 6.0)) return true
        val original = event.block.blockData.asString
        if (world.project(scene, mapOf(target.position to "minecraft:air"))) {
            if (!commit(runtime, scene, event.player, current, MineExpeditionEngine.completeTarget(current, target.target, clock()))) {
                world.project(scene, mapOf(target.position to original))
                projectedStage.remove(scope(scene))
            }
        }
        return true
    }

    fun canMine(player: Player, block: Block): Boolean {
        val runtime = runtimeFor(player) ?: return false
        val scene = world.scene(runtime) ?: return false
        val current = runtime.state.incident?.expedition ?: return false
        return participant(runtime, scene, player) && targets(scene, current).any {
            it.interaction == MineExpeditionInteraction.BREAK && it.position == scene.local(block.location)
        }
    }

    private fun enter(runtime: MineRuntime, player: Player) {
        val surface = surfacePoint(runtime) ?: return
        val scene = world.scene(runtime)?.takeIf { it.ready && it.placement.geometryVersion >= 2 && it.world === runtime.region.world } ?: run {
            player.sendActionBar(render("preparing", player)); return
        }
        if (!near(player, surface, 5.0) || !access.hasAccess(player, runtime.settings.permission)) return
        val destination = MineExpeditionPortalPolicy.arrival(scene.kind, scene.station("entry"))
        if (!destination.block.isPassable || !destination.clone().add(0.0, 1.0, 0.0).block.isPassable ||
            !destination.clone().add(0.0, -1.0, 0.0).block.type.isSolid) {
            player.sendActionBar(render("preparing", player))
            return
        }
        travel.enter(WorksiteExpeditionTravel.EntryRequest(player, runtime.settings.id, runtime.state.sequence,
            runtime.settings.permission, surface, destination), stillValid = {
                world.scene(runtime) === scene && scene.ready
            })
    }

    private fun exit(player: Player, scene: MineExpeditionScene) {
        actions.release(player)
        experiments.release(player)
        machinery.release(player)
        if (travel.retains(player)) travel.exit(player) else travel.evacuatePlayer(player, scene.surface)
    }

    private fun commit(runtime: MineRuntime, scene: MineExpeditionScene, player: Player,
        before: MineExpeditionState, step: MineExpeditionStep): Boolean {
        val incident = runtime.state.incident ?: return false
        if (!step.accepted || incident.expedition != before || world.scene(runtime) !== scene) return false
        val delta = MineExpeditionEngine.progressDelta(incident.type, before, step.state)
        val next = runtime.state.copy(incident = incident.copy(expedition = step.state))
        if (delta > 0) {
            if (!incidents.work(runtime, player, delta, next).accepted) return false
            player.world.playSound(player.location, Sound.BLOCK_COPPER_BULB_TURN_ON, 0.8f, 1f)
        } else {
            runtime.state = next
            statePort.persistAsync()
        }
        if (step.state.stage != before.stage) {
            drivers.remove(scope(scene))
            machinery.park(scene)
            actions.clear(scope(scene))
            experiments.clear(scope(scene))
            machinery.sync(scene, step.state)
        }
        if (step.finished) {
            world.markCompleted(scene, clock())
            clearScene(scene)
            clearGateway(runtime)
            if (scene.kind == MineExpeditionKind.DEAD_FACTORY) {
                factoryResults[scene.journalSequence] = FactoryResult(clock() + 12_000L, "finished_gear")
                showFactoryResult(scene, clock(), first = true)
            } else scene.world.players.filter { scene.contains(it.location) }.toList().forEach { exit(it, scene) }
        }
        return true
    }

    private fun projectStage(scene: MineExpeditionScene, current: MineExpeditionState) {
        if (projectedStage[scope(scene)] == current.stage) return
        val center = machinery.localCenter(scene, current)
        if (scene.kind == MineExpeditionKind.DRILLING_ARK) {
            val blocks = (0..2).associate { index -> center.offset(index - 1, 1, 8) to
                if (current.stage == MineExpeditionStage.ARK_JAM && index !in current.completed) "minecraft:tuff" else "minecraft:air" }
            if (!world.project(scene, blocks)) return
        }
        if (modernDescent(scene) && current.stage == MineExpeditionStage.DESCENT_COUNTERWEIGHTS) {
            val brake = scene.plan.stations.getValue("counterweight_0").offset(dz = -2)
            val block = if (0 in current.completed) "minecraft:air" else "minecraft:stone"
            if (!world.project(scene, mapOf(brake to block))) return
        }
        projectedStage[scope(scene)] = current.stage
    }

    fun participants(): Collection<Player> = world.retainedScenes().flatMap { scene ->
        scene.world.players.filter { scene.contains(it.location) }
    }.distinctBy(Player::getUniqueId)

    fun runtimeFor(player: Player): MineRuntime? = world.retainedScenes().firstOrNull { scene ->
        scene.contains(player.location) && registry.byId(scene.zoneId)?.let { world.scene(it) === scene } == true
    }?.let { registry.byId(it.zoneId) }

    fun guidanceHint(runtime: MineRuntime, player: Player, now: Long): Component? {
        val incident = runtime.state.incident?.takeIf { MineExpeditionEngine.supports(it.type) } ?: return null
        val current = incident.expedition
        val scene = world.scene(runtime)
        return when {
            scene?.ready != true || current == null -> render("preparing", player)
            !scene.contains(player.location) -> render("enter-hint", player)
            experiments.hint(scope(scene), player, current, now) != null -> experiments.hint(scope(scene), player, current, now)
            MineFactoryProgram.usesConnectedCrusherLine(scene.plan) &&
                current.stage == MineExpeditionStage.FACTORY_HEAT && actions.factoryHeat(scope(scene))?.ready == true ->
                locale?.renderPath("mine.expedition.factory-heat-ready", player, mapOf(
                    "progress" to Component.text((actions.factoryHeat(scope(scene))!!.progress * 100).toInt()),
                )) ?: render("heat-ready", player)
            actions.hint(scope(scene), player, now) != null -> actions.hint(scope(scene), player, now)
            MineFactoryProgram.usesConnectedCrusherLine(scene.plan) &&
                MineFactoryProgram.chargeTransferPending(current) -> render("line.auto-feed", player)
            MineFactoryProgram.usesConnectedCrusherLine(scene.plan) && current.stage in setOf(MineExpeditionStage.FACTORY_WATER,MineExpeditionStage.FACTORY_COAL) -> {
                val checkpoint=targets(scene,current).firstOrNull()?.target?.coerceAtLeast(0) ?: 2
                val suffix=if(actions.carrying(player,scope(scene))) "-carry" else ""
                val phase=if(current.stage==MineExpeditionStage.FACTORY_WATER) "commission" else "charge"
                render("line.$phase-$checkpoint$suffix",player)
            }
            MineFactoryProgram.pressTransferPending(scene.plan, current) -> render("line.auto-press", player)
            actions.carrying(player, scope(scene)) -> render(if (current.stage == MineExpeditionStage.FACTORY_COAL) "fuel-carry" else if(current.stage == MineExpeditionStage.FACTORY_INSTALL) "iron-cart-carry" else "carry", player)
            current.stage == MineExpeditionStage.FACTORY_WATER -> render("program.${current.factoryProgram}", player)
            current.stage == MineExpeditionStage.FACTORY_COAL -> render("fuel-progress", player, progressValues(current, now))
            current.stage == MineExpeditionStage.FACTORY_HEAT &&
                MineFactoryProgram.usesConnectedCrusherLine(scene.plan) -> {
                val heat = actions.factoryHeat(scope(scene)) ?: MineWorkshopHeat()
                val key = when {
                    heat.ready -> "heat-ready"
                    heat.running -> "heat-progress"
                    else -> "heat-start"
                }
                locale?.renderPath("mine.working.workshop.$key", player, mapOf(
                    "progress" to Component.text((heat.progress * 100).toInt()),
                    "temperature" to Component.text((heat.progress * 100).toInt()),
                )) ?: Component.text(key)
            }
            modernDescent(scene) -> render("stage.descent_v4_${current.stage.name.removePrefix("DESCENT_").lowercase()}", player)
            current.stage == MineExpeditionStage.FACTORY_HEAT -> render(
                if (MineExpeditionEngine.canFinishHeat(current, now)) "heat-ready" else "heat-progress", player, progressValues(current, now))
            else -> render("stage.${current.stage.name.lowercase()}", player)
        }
    }

    fun guidanceTargets(runtime: MineRuntime, player: Player): List<WorksiteGuidanceTarget> {
        val scene = world.scene(runtime)
        val current = runtime.state.incident?.expedition
        val positions = if (scene == null || current == null || !scene.contains(player.location)) {
            listOfNotNull(surfacePoint(runtime)?.let { "entry" to it })
        } else if (MineFactoryExperiments.pending(current).isNotEmpty()) {
            experiments.targets(scene, current, clock()).filter { it.glowing }
                .sortedBy { it.location.distanceSquared(player.location) }.take(3).map { it.id to it.location }
        } else targets(scene, current).filter { target ->
            if (actions.carrying(player, scope(scene))) target.interaction == MineExpeditionInteraction.DELIVER
            else target.interaction != MineExpeditionInteraction.DELIVER
        }.sortedBy { scene.at(it.position).distanceSquared(player.location) }.take(3).map { it.id to scene.at(it.position) }
        return positions.map { (id, at) -> WorksiteGuidanceTarget("expedition_$id", ObjectiveTargetRole("expedition"),
            at, Color.fromRGB(255, 187, 77), columnParticles = 1, columnStep = 0.05) }
    }

    private fun reconcileRetained(now: Long) {
        markers.retainSites(world.allScenes().filter { it.ready }.mapTo(hashSetOf()) { it.journalSequence })
        world.allScenes().filter { it.ready }.forEach { scene ->
            markers.reconcile("exit:${scene.journalSequence}", exitMarkers(scene))
            if (scene.reserved) markers.reconcile("furnish:${scene.journalSequence}", MineExpeditionFurnishings.targets(scene,editor,emptySet()))
        }
        world.retainedScenes().forEach { scene ->
            val runtime = registry.byId(scene.zoneId)
            if (runtime != null && world.scene(runtime) === scene) return@forEach
            if (!scene.ready) return@forEach
            if (scene.completedAt == 0L) world.markCompleted(scene, now)
            if (showFactoryResult(scene, now)) return@forEach
            clearScene(scene)
            val occupants = scene.world.players.filter { scene.contains(it.location) && travel.retains(it) }
            occupants.forEach { exit(it, scene) }
            if (occupants.none { travel.retains(it) }) world.releaseStatic(scene)

        }
    }

    private fun showFactoryResult(scene: MineExpeditionScene, now: Long, first: Boolean = false): Boolean {
        val completed = factoryResults[scene.journalSequence] ?: return false
        val until = completed.until
        if (now >= until) { factoryResults.remove(scene.journalSequence); return false }
        val base = scene.plan.stations.getValue("assembly_socket")
        val yaw = editor?.yaw(scene, "assembly_socket") ?: 0
        val angle = Math.toRadians(yaw.toDouble())
        // Stand the finished gear on the front of the bench, clear of the raised ram.
        val result = scene.at(editor?.position(scene, "assembly_socket", base) ?: base)
            .add(kotlin.math.sin(angle) * 1.4, 1.5, kotlin.math.cos(angle) * 1.4)
        markers.reconcile("furnish:${scene.journalSequence}", MineExpeditionFurnishings.targets(scene, editor, emptySet()))
        markers.reconcile("result:${scene.journalSequence}", listOf(MineExpeditionMarkers.Target(
            "finished_product", result, Material.IRON_BLOCK, render("result-label"), model = completed.model, yaw = yaw)))
        markers.reconcile("exit:${scene.journalSequence}", exitMarkers(scene))
        scene.world.players.filter { scene.contains(it.location) }.forEach {
            it.sendActionBar(render("result-return", it, mapOf("seconds" to Component.text(((until - now + 999) / 1000).toString()))))
        }
        factoryPresentation.finished(result, first)
        return true
    }

    fun retire(runtime: MineRuntime) {
        world.scene(runtime)?.let { scene ->
            factoryResults.remove(scene.journalSequence)
            if (scene.completedAt == 0L) world.markCompleted(scene, clock())
            clearScene(scene)
            scene.world.players.filter { scene.contains(it.location) && travel.retains(it) }.toList().forEach { exit(it, scene) }
        } ?: world.retireUnbuilt(runtime)
        clearGateway(runtime)
    }

    fun retains(player: Player, destination: Location): Boolean = travel.isAuthorized(player, destination) ||
        world.allScenes().any { it.contains(destination) && travel.retains(player) }
    fun protects(location: Location): Boolean = world.protects(location)
    fun recover(player: Player) = travel.recover(player)
    fun onChunkLoad(chunk: Chunk) { world.onChunkLoad(chunk); markers.onChunkLoad(chunk); actions.removeOrphans(chunk.entities.asIterable()) }
    fun protects(entity: org.bukkit.entity.Entity) = actions.owns(entity)
    fun reconcileLoaded() {
        editor?.initialize()
        actions.removeOrphans(Bukkit.getWorlds().flatMap { it.entities })
        val runtime = registry.snapshot().firstOrNull(::configured)
        runtime?.let { world.configure(it, requireNotNull(surfacePoint(it))) }
        world.initialize(runtime != null)
    }
    fun editFurnishings(player: Player, action: String?) {
        if (!player.hasPermission("arcfarms.admin")) return
        val kind=MineExpeditionKind.entries.firstOrNull { it.name.equals(action,true) }
        if(kind==null) { editor?.command(player,action);return }
        val scene=world.allScenes().firstOrNull { it.kind==kind && it.ready && it.reserved && editor?.locked(it)!=true }
        if(scene==null) { player.sendMessage(locale?.renderPath("admin.expeditions.editor.idle-only",player) ?: Component.empty());return }
        val target=scene.station("entry")
        if(!target.block.isPassable || !target.clone().add(0.0,1.0,0.0).block.isPassable) return
        travel.enter(WorksiteExpeditionTravel.EntryRequest(player,scene.journalOwner,scene.journalSequence,
            "arcfarms.admin",player.location.clone(),target),onEntered={ editor?.command(player,null) },
            stillValid={ scene.ready && scene.reserved && editor?.locked(scene)!=true })
    }
    fun configureFactoryExperiments(zoneId: String, preset: String): Boolean =
        registry.byId(zoneId)?.let { world.configureFactoryExperiments(it.settings.id, preset) } ?: false
    fun stockStatus(): List<MineExpeditionStockStatus> = world.stockStatus()
    fun rebuildStock(kind: ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind?): Int = world.rebuildStock(kind)

    fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason) {
        actions.release(player); experiments.release(player); machinery.release(player)
        drivers.entries.removeIf { it.value.playerId == player.uniqueId }
        when (reason) {
            WorksitePlayerReleaseReason.QUIT, WorksitePlayerReleaseReason.DEATH,
            WorksitePlayerReleaseReason.RELOAD, WorksitePlayerReleaseReason.SHUTDOWN -> travel.quit(player)
            else -> travel.reconcile(player, inside = false)
        }
    }

    fun beforeReload() { cleanupVisuals(); world.beforeReload() }
    fun cleanup(shutdown: Boolean = false) { cleanupVisuals(); if (shutdown) { editor?.close(); world.close() } else world.beforeReload() }
    private fun cleanupVisuals() { factoryResults.clear(); factoryPresentation.cleanup(); descentPresentation.cleanup(); editor?.cancelPreviews(); actions.cleanup(); experiments.cleanup(); markers.cleanup(); machinery.cleanup(); drivers.clear(); projectedStage.clear() }
    private fun clearScene(scene: MineExpeditionScene) {
        markers.clear("result:${scene.journalSequence}")
        factoryPresentation.clear(scene)
        markers.clear(scope(scene)); descentPresentation.clear(scene); actions.clear(scope(scene)); experiments.clear(scope(scene)); machinery.clear(scene)
        drivers.remove(scope(scene)); projectedStage.remove(scope(scene))
    }
    private fun clearGateway(runtime: MineRuntime) = markers.clear(gatewayScope(runtime))
    private fun targets(scene: MineExpeditionScene, state: MineExpeditionState) =
        MineExpeditionObjectives.targets(scene.plan, state, machinery.localCenter(scene, state)).map { target ->
            if (target.interaction == MineExpeditionInteraction.BREAK || target.id=="drive") target
            else target.copy(position=editor?.position(scene,target.id,target.position) ?: target.position)
        }

    private fun modernDescent(scene: MineExpeditionScene): Boolean =
        scene.kind == MineExpeditionKind.LAST_DESCENT && scene.placement.geometryVersion >= 4
    private fun fixturePosition(scene: MineExpeditionScene, id: String, offset: Vector): Location? {
        val fixture = MineExpeditionFurnishings.fixtures(scene).firstOrNull { it.id == id } ?: return null
        val point = editor?.position(scene, id, fixture.at) ?: fixture.at
        val yaw = editor?.yaw(scene, id) ?: fixture.yaw
        val shifted = Quaternionf().rotateY(Math.toRadians(yaw.toDouble()).toFloat())
            .transform(Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()).mul(fixture.scale))
        return scene.at(point).add(shifted.x.toDouble(), shifted.y.toDouble(), shifted.z.toDouble())
            .also { it.yaw = yaw.toFloat() }
    }
    private fun participant(runtime: MineRuntime, scene: MineExpeditionScene, player: Player): Boolean =
        player.isOnline && !player.isDead && player.gameMode != GameMode.SPECTATOR && !access.isAdminEditing(player) &&
            access.hasAccess(player, runtime.settings.permission) && scene.contains(player.location) &&
            travel.record(player)?.let { it.zoneId == scene.zoneId && it.sequence == scene.sequence } == true
    private fun near(player: Player, location: Location, radius: Double): Boolean =
        player.world === location.world && player.location.distanceSquared(location) <= radius * radius
    private fun progressValues(state: MineExpeditionState, now: Long, heat: MineWorkshopHeat? = null): Map<String, Component> = mapOf(
        "count" to Component.text(state.completed.size),
        "total" to Component.text(MineExpeditionEngine.targetCount(state)),
        "seconds" to Component.text(((state.heatStartedAt + MineExpeditionEngine.HEAT_MILLIS - now).coerceAtLeast(0) + 999) / 1000),
        "temperature" to Component.text(((heat?.progress ?: 0.0) * 100).toInt()),
        "progress" to Component.text(((heat?.progress ?: 0.0) * 100).toInt()),
    )
    private fun render(key: String, player: Player? = null, values: Map<String, Component> = emptyMap()): Component =
        locale?.renderPath("mine.expedition.$key", player, values) ?: Component.text(key)
    private fun scope(scene: MineExpeditionScene) = "${scene.zoneId}:${scene.sequence}:${scene.objectiveNonce}"
    private fun gatewayScope(runtime: MineRuntime) = "gate:${runtime.settings.id}"

    private companion object {
        const val MOTION_STEP_MILLIS = 400L
        const val MIN_RETAIN_MILLIS = 60_000L
        const val MAX_RETAIN_MILLIS = 300_000L
        const val WARNING_MILLIS = 15_000L
    }
}
