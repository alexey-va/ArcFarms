package ru.ruscrafting.farms.paper.mine.expedition

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.FarmProcessingCrankState
import ru.ruscrafting.farms.domain.FarmProcessingCrankTracker
import ru.ruscrafting.farms.domain.MineWorkshopHeat
import ru.ruscrafting.farms.domain.mine.expedition.*
import ru.ruscrafting.farms.paper.worksite.WorksiteCrankTethers
import ru.ruscrafting.farms.paper.worksite.WorksiteCarriedDisplayRenderer
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs

/** Transient cargo leases, casting controls and walking cranks; durable checkpoints belong to the domain engine. */
internal class MineExpeditionActions(private val plugin: Plugin, private val locale: ArcFarmsLocale?,
    private val carts: MineFactoryCarts = MineFactoryCarts(plugin)) {
    private val tethers = WorksiteCrankTethers(NamespacedKey(plugin, "mine_expedition_crank_tether"))
    private var nextTetherWarning = 0L
    private data class Cargo(
        val scope: String,
        val stage: MineExpeditionStage,
        val target: Int,
        val material: String,
        val display: ItemDisplay? = null,
        val cart: MineFactoryCarts.Cart? = null,
    )
    private data class Crank(val scope: String, val stage: MineExpeditionStage, val objective: String,
        var sample: FarmProcessingCrankState? = null, var radians: Double = 0.0)
    private data class Operation(val scope:String,val objective:String,val target:Int,val cycle:MineFactoryOperation)
    private data class ChargeTransfer(var lastTick: Long, var elapsed: Long = 0L)
    private data class PressTransfer(var lastTick: Long, var elapsed: Long = 0L)
    private data class Valve(val scope: String, val stage: MineExpeditionStage, val objective: String,
        var clicks: Int = 0, var nextClickAt: Long = 0, var lastPlayer: UUID? = null)
    private data class Pour(val scope: String, val owner: UUID, val cycle: MineFactoryPour, var readySignalled: Boolean = false)
    private data class HeatSession(var state: MineWorkshopHeat, var lastTick: Long, var owner: UUID? = null)
    private data class Feedback(val owner: UUID, val key: String, val until: Long)
    private val pours = mutableMapOf<String, Pour>()
    private val heat = mutableMapOf<String, HeatSession>()
    private val feedback = mutableMapOf<String, Feedback>()
    private val chargeTransfers = mutableMapOf<String, ChargeTransfer>()
    private val pressTransfers = mutableMapOf<String, PressTransfer>()
    private val valves = mutableMapOf<Pair<String, String>, Valve>()
    private val operations=mutableMapOf<UUID,Operation>()
    private val renderer = WorksiteCarriedDisplayRenderer()
    private val cargo = mutableMapOf<UUID, Cargo>()
    private val cranks = mutableMapOf<UUID, Crank>()

    fun operationPhase(scope:String,id:String,now:Long):Double = operations.values
        .firstOrNull { it.scope==scope && it.objective==id }?.cycle?.progress(now)?.times(PI*2)
        ?: pressTransfers[scope]?.takeIf { id == "assembly_socket" }
            ?.elapsed?.let(MineFactoryPressCycle::strokePhase)
        ?: 0.0
    fun transferPhase(scope: String, id: String): Double = when (id) {
        "charge_transfer" -> chargeTransfers[scope]?.elapsed?.let {
            (it.toDouble() / CONNECTED_TRANSFER_MILLIS).coerceIn(0.0, 1.0) * PI * 2
        } ?: 0.0
        "roller_transfer" -> pressTransfers[scope]?.elapsed?.let(MineFactoryPressCycle::transferPhase) ?: 0.0
        else -> 0.0
    }
    fun owns(entity: Entity) = tethers.owns(entity) || carts.owns(entity)
    fun removeOrphans(entities: Iterable<Entity>) { tethers.removeOrphans(entities); carts.removeOrphans(entities) }
    fun pourLabel(scope: String, now: Long): String = pours[scope]?.let {
        if(it.cycle.ready(now)) "pour-close" else "pour-filling"
    } ?: "control.pour_console"
    fun pourReady(scope: String, now: Long): Boolean = pours[scope]?.cycle?.ready(now) == true
    fun factoryHeat(scope: String): MineWorkshopHeat? = heat[scope]?.state
    fun pourValues(scope: String, now: Long): Map<String, Component> {
        val level = pours[scope]?.cycle?.level(now) ?: 0.0
        return mapOf("percent" to Component.text((level*100).toInt()))
    }
    fun hint(scope: String, player: Player, now: Long): Component? {
        feedback[scope]?.let { notice ->
            if (now < notice.until && notice.owner == player.uniqueId) {
                return locale?.renderPath("mine.expedition.${notice.key}", player)
                    ?: text(notice.key, player)
            }
            if (now >= notice.until) feedback.remove(scope)
        }
        pours[scope]?.takeIf { it.owner==player.uniqueId }?.let {
            return locale?.renderPath("mine.expedition.${pourLabel(scope,now)}",player,pourValues(scope,now))
                ?: Component.text(pourLabel(scope,now))
        }
        valves.values.filter { it.scope == scope && it.lastPlayer == player.uniqueId }.maxByOrNull { it.nextClickAt }?.let {
            return text("valve-progress", player, mapOf("count" to it.clicks, "total" to VALVE_CLICKS))
        }
        heat[scope]?.takeIf { it.owner == null || it.owner == player.uniqueId }?.let { session ->
            return heatText(player, session.state)
        }
        operations[player.uniqueId]?.takeIf { it.scope == scope }?.let {
            return text("operation-progress", player, mapOf("percent" to (it.cycle.progress(now) * 100).toInt()))
        }
        cranks[player.uniqueId]?.takeIf { it.scope == scope }?.let {
            return text("turn-progress", player, mapOf("percent" to (it.radians / (PI * 1.5) * 100).toInt().coerceAtMost(100)))
        }
        return null
    }
    fun carrying(player: Player, scope: String): Boolean = cargo[player.uniqueId]?.scope == scope
    fun claimed(scope: String, stage: MineExpeditionStage, index: Int): Boolean =
        cargo.values.any { it.scope == scope && it.stage == stage && it.target == index }

    fun interact(scope: String, scene: MineExpeditionScene, state: MineExpeditionState, player: Player,
        target: MineExpeditionObjective, now: Long, complete: (MineExpeditionStep) -> Boolean,
        drive: () -> Unit) {
        // The connected line intentionally exposes one numbered checkpoint at
        // a time.  Keep the durable completed set authoritative even when a
        // stale marker or a direct interaction reaches this owner.
        if (!factoryTargetIsPermitted(scene, state, target)) return
        when (target.interaction) {
            MineExpeditionInteraction.POUR -> pour(scope,scene,state,player,target,now,complete)
            MineExpeditionInteraction.VALVE -> turnValve(scope, scene, state, player, target, now, complete)
            MineExpeditionInteraction.PICKUP -> pickup(scope, state, player, target)
            MineExpeditionInteraction.DELIVER -> {
                val held = cargo[player.uniqueId] ?: return
                if (held.scope != scope || held.stage != state.stage) return
                if (MineFactoryProgram.usesConnectedCrusherLine(scene.plan) && held.material != target.material) return
                if (!deliveryIsCurrent(scene, state, target, held.target)) return
                if(state.stage==MineExpeditionStage.FACTORY_INSTALL) {
                    startOperation(scope,state,player,target.id,held.target,now)
                    if(operations[player.uniqueId]?.objective==target.id) held.cart?.let { carts.unload(it) }
                } else if(complete(MineExpeditionEngine.completeTarget(state,held.target,now))) {
                    release(player)
                    if (state.stage == MineExpeditionStage.FACTORY_COAL) {
                        val at = scene.at(target.position).add(0.0, 2.6, 0.0)
                        if (sounds()) at.world.playSound(at, Sound.BLOCK_STONE_PLACE, .85f, .6f)
                        if (particles()) {
                            at.world.spawnParticle(Particle.BLOCK, at, 18, .6, .2, .6, Material.COAL_BLOCK.createBlockData())
                            at.world.spawnParticle(Particle.CLOUD, at, 5, .5, .2, .5, .025)
                        }
                        val loadedKey=if(MineFactoryProgram.usesConnectedCrusherLine(scene.plan))
                            if(target.id=="crusher_feed") "charge-loaded" else "mix-loaded" else "fuel-loaded"
                        player.sendActionBar(text(loadedKey, player, mapOf("count" to state.completed.size + 1, "total" to MineExpeditionEngine.targetCount(state))))
                    }
                }
            }
            MineExpeditionInteraction.CRANK -> {
                if (cargo.containsKey(player.uniqueId)) return
                if (cranks[player.uniqueId]?.let { it.scope == scope && it.objective == target.id } == true) return
                val center = scene.at(target.position)
                val dx = player.location.x - center.x
                val dz = player.location.z - center.z
                if (player.world !== center.world || abs(player.location.y - center.y) > 1.8 || dx * dx + dz * dz !in .64..9.0) {
                    player.sendActionBar(text("turn-distance", player)); return
                }
                releaseCrank(player.uniqueId)
                if (!attachTether(scope, player, center, now)) return
                cranks[player.uniqueId] = Crank(scope, state.stage, target.id)
                if (sounds()) player.playSound(center, Sound.BLOCK_CHAIN_PLACE, .7f, 1.1f)
                player.sendActionBar(text("turn", player))
            }
            MineExpeditionInteraction.OPERATE -> {
                if (state.stage == MineExpeditionStage.FACTORY_HEAT &&
                    MineFactoryProgram.usesConnectedCrusherLine(scene.plan)) {
                    operateFactoryHeat(scope, scene, state, player, target, now, complete)
                    return
                }
                if(state.stage in setOf(
                        MineExpeditionStage.FACTORY_WATER,
                        MineExpeditionStage.FACTORY_COAL,
                        MineExpeditionStage.FACTORY_CRANE,
                    )) {
                    startOperation(scope,state,player,target.id,target.target,now)
                    return
                }
                if (MineExpeditionEngine.isModernLastDescent(state) &&
                    state.stage == MineExpeditionStage.DESCENT_CORE_VALVES) {
                    startOperation(scope, state, player, target.id, target.target, now)
                    return
                }
                if (!complete(MineExpeditionEngine.completeTarget(state, target.target, now)) &&
                    state.stage == MineExpeditionStage.FACTORY_HEAT) player.sendActionBar(text("heat-wait", player))
            }
            MineExpeditionInteraction.BRANCH -> complete(MineExpeditionEngine.chooseBranch(state, target.target))
            MineExpeditionInteraction.MOTION -> drive()
            MineExpeditionInteraction.BREAK -> Unit
        }
    }

    fun tick(scope: String, scene: MineExpeditionScene, state: MineExpeditionState,
        targets: List<MineExpeditionObjective>, players: Collection<Player>, now: Long,
        complete: (Player, MineExpeditionStep) -> Boolean, animateCrank: (String, Double) -> Unit) {
        val participants = players.filter { it.isOnline && !it.isDead && scene.contains(it.location) }.associateBy(Player::getUniqueId)
        tickFactoryHeat(scope, scene, state, targets, participants.values, now)
        if (state.stage == MineExpeditionStage.FACTORY_HEAT && MineFactoryProgram.usesConnectedCrusherLine(scene.plan)) {
            animateCrank("furnace_control", if (heat[scope]?.state?.running == true) PI else 0.0)
        }
        tickConnectedChargeTransfer(scope, scene, state, participants.values, now, complete)
        tickConnectedPressTransfer(scope, scene, state, participants.values, now, complete)
        if(state.stage==MineExpeditionStage.FACTORY_POUR && scope !in pours) animateCrank("pour_console",0.0)
        pours[scope]?.let { pour ->
            val player=participants[pour.owner]
            if(player==null || state.stage!=MineExpeditionStage.FACTORY_POUR || pour.cycle.overflow(now)) {
                pours.remove(scope)
                animateCrank("pour_console",0.0)
                if(player!=null && state.stage==MineExpeditionStage.FACTORY_POUR) {
                    feedback[scope] = Feedback(player.uniqueId, "pour-overflow", now + FEEDBACK_MILLIS)
                    player.sendActionBar(text("pour-overflow",player))
                    if(sounds()) player.playSound(player.location,Sound.BLOCK_FIRE_EXTINGUISH,.65f,.8f)
                }
            } else {
                animateCrank("pour_console",pour.cycle.level(now)*PI*2)
                if(pour.cycle.ready(now) && !pour.readySignalled) {
                    pour.readySignalled=true
                    if(sounds()) player.playSound(player.location,Sound.BLOCK_NOTE_BLOCK_BELL,.8f,1.5f)
                }
            }
        }
        valves.entries.removeIf { (_, valve) -> valve.scope == scope &&
            (valve.stage != state.stage || targets.none { it.id == valve.objective && it.interaction == MineExpeditionInteraction.VALVE }) }
        valves.values.filter { it.scope == scope }.forEach { animateCrank(it.objective, it.clicks * PI * 2 / VALVE_CLICKS) }
        cargo.filterValues { it.scope == scope }.toMap().forEach { (id, held) ->
            val player = participants[id]
            if (player == null || held.stage != state.stage || held.target in state.completed || held.display?.isValid == false) {
                release(id)
            } else {
                val target=operations[id]?.let { op -> targets.firstOrNull { it.id==op.objective } }
                if(held.cart!=null) {
                    if(!scene.contains(held.cart.at) || !carts.move(held.cart,player,now)) release(id)
                    else if(!held.cart.unloaded) {
                        val receiver=targets.firstOrNull { it.interaction==MineExpeditionInteraction.DELIVER }
                        if(receiver!=null && player.location.distanceSquared(scene.at(receiver.position))<=2.8*2.8 &&
                            held.cart.at.distanceSquared(scene.at(receiver.position))<=4.5*4.5) {
                            interact(scope,scene,state,player,receiver,now,{ step -> complete(player,step) }) {}
                        }
                    }
                } else if(held.display!=null) {
                    if(target!=null && state.stage==MineExpeditionStage.FACTORY_INSTALL)
                        held.display.teleport(scene.at(target.position).add(0.0,1.9,0.0))
                    else renderer.move(held.display, player, 0.75, 0.85)
                }
            }
        }
        operations.filterValues { it.scope==scope }.toMap().forEach { (id,operation) ->
            val player=participants[id]
            val target=targets.firstOrNull { it.id==operation.objective }
            if(player==null || target==null || player.world !== scene.world || operation.cycle.stage!=state.stage ||
                (state.stage==MineExpeditionStage.FACTORY_INSTALL && cargo[id]?.stage!=state.stage)) {
                operations.remove(id);animateCrank(operation.objective,0.0)
                return@forEach
            }
            val progress=operation.cycle.progress(now)
            animateCrank(operation.objective,progress*PI*2)
            if(progress>=1 && complete(player,MineExpeditionEngine.completeTarget(state,operation.target,now))) release(id)
        }
        cranks.filterValues { it.scope == scope }.toMap().forEach { (id, crank) ->
            val player = participants[id]
            val target = targets.firstOrNull { it.id == crank.objective && it.interaction == MineExpeditionInteraction.CRANK }
            if (player == null || crank.stage != state.stage || target == null) {
                releaseCrank(id)
                return@forEach
            }
            val center = scene.at(target.position)
            if (player.world !== center.world || abs(player.location.y - center.y) > 1.8) {
                releaseCrank(id)
                return@forEach
            }
            val sample = FarmProcessingCrankTracker.sample(crank.sample, player.location.x, player.location.z,
                center.x, center.z, 0.8, 2.8, radiusTolerance = 0.25, maxStepDistance = 1.8)
            if (sample.state == null || !attachTether(scope, player, center, now)) {
                releaseCrank(id)
                return@forEach
            }
            crank.sample = sample.state
            if (sample.acceptedRadians <= 0) return@forEach
            crank.radians += sample.acceptedRadians
            animateCrank(target.id, crank.radians)
            if (particles()) center.world.spawnParticle(Particle.CRIT, center.clone().add(0.0, 0.9, 0.0), 2, 0.15, 0.1, 0.15, 0.0)
            if (crank.radians >= PI * 1.5 && complete(player,
                    MineExpeditionEngine.completeTarget(state, target.target, now))) {
                releaseCrank(id)
                if (sounds()) center.world.playSound(center, Sound.BLOCK_CHAIN_PLACE, 0.8f, 0.7f)
            }
        }
    }

    private fun startOperation(scope:String,state:MineExpeditionState,player:Player,id:String,target:Int,now:Long) {
        if(operations.containsKey(player.uniqueId) || operations.values.any { it.scope==scope && it.objective==id }) return
        operations[player.uniqueId]=Operation(scope,id,target,MineFactoryOperation(state.stage,now))
        player.sendActionBar(text("operating",player))
    }

    private fun operateFactoryHeat(
        scope: String,
        scene: MineExpeditionScene,
        state: MineExpeditionState,
        player: Player,
        target: MineExpeditionObjective,
        now: Long,
        complete: (MineExpeditionStep) -> Boolean,
    ) {
        if (target.target in state.completed || target.interaction != MineExpeditionInteraction.OPERATE) return
        val receiver = scene.at(target.position)
        if (player.world !== receiver.world || player.location.distanceSquared(receiver) > 25.0) return
        val session = heat[scope]
        if (session == null) {
            // The first click starts one automatic cycle. A repeated click
            // cannot reset it or switch it into a cooling mode.
            val started = MineWorkshopHeat().start()
            heat[scope] = HeatSession(started, now, player.uniqueId)
            player.sendActionBar(heatText(player, started))
            if (sounds()) player.playSound(receiver, Sound.BLOCK_LEVER_CLICK, .55f, 1.15f)
            return
        }
        advanceHeat(session, now)
        session.owner = player.uniqueId
        if (session.state.ready) {
            if (complete(MineExpeditionEngine.completeFactoryHeat(state, session.state, now))) heat.remove(scope)
            else player.sendActionBar(heatText(player, session.state))
            return
        }
        // Running heat is intentionally idempotent. Keep its original start
        // time; the next valid action is the ready tap.
        player.sendActionBar(heatText(player, session.state))
    }

    private fun tickFactoryHeat(
        scope: String,
        scene: MineExpeditionScene,
        state: MineExpeditionState,
        targets: List<MineExpeditionObjective>,
        participants: Collection<Player>,
        now: Long,
    ) {
        val session = heat[scope] ?: return
        if (state.stage != MineExpeditionStage.FACTORY_HEAT ||
            targets.none { it.id == "furnace_control" && it.interaction == MineExpeditionInteraction.OPERATE }) {
            heat.remove(scope)
            return
        }
        // The furnace advances only while the expedition still has a live
        // participant. A reload can recreate the session without granting a
        // checkpoint, while an idle world does not catch up unattended heat.
        if (participants.isNotEmpty()) advanceHeat(session, now) else session.lastTick = now
    }

    private fun advanceHeat(session: HeatSession, now: Long) {
        val elapsed = (now - session.lastTick).coerceAtLeast(0L)
        session.state = session.state.tick(elapsed)
        session.lastTick = now
    }

    /** The visible belt owns the final charge hand-off; it needs no player cargo lease. */
    private fun tickConnectedChargeTransfer(
        scope: String,
        scene: MineExpeditionScene,
        state: MineExpeditionState,
        participants: Collection<Player>,
        now: Long,
        complete: (Player, MineExpeditionStep) -> Boolean,
    ) {
        if (!MineFactoryProgram.usesConnectedCrusherLine(scene.plan) ||
            !MineFactoryProgram.chargeTransferPending(state)) {
            chargeTransfers.remove(scope)
            return
        }
        if (participants.isEmpty()) {
            chargeTransfers[scope]?.lastTick = now
            return
        }
        val transfer = chargeTransfers[scope] ?: ChargeTransfer(now).also { chargeTransfers[scope] = it }
        val elapsed = (now - transfer.lastTick).coerceIn(0L, TRANSFER_TICK_CAP_MILLIS)
        transfer.elapsed = (transfer.elapsed + elapsed).coerceAtMost(CONNECTED_TRANSFER_MILLIS)
        transfer.lastTick = now
        if (transfer.elapsed < CONNECTED_TRANSFER_MILLIS) return
        val driver = participants.first()
        if (complete(driver, MineExpeditionEngine.completeTarget(state, 2, now))) {
            chargeTransfers.remove(scope)
        }
    }

    /** The placed billet is pressed automatically once an active participant remains. */
    private fun tickConnectedPressTransfer(
        scope: String,
        scene: MineExpeditionScene,
        state: MineExpeditionState,
        participants: Collection<Player>,
        now: Long,
        complete: (Player, MineExpeditionStep) -> Boolean,
    ) {
        if (!MineFactoryProgram.pressTransferPending(scene.plan, state)) {
            pressTransfers.remove(scope)
            return
        }
        if (participants.isEmpty()) {
            pressTransfers[scope]?.lastTick = now
            return
        }
        val transfer = pressTransfers[scope] ?: PressTransfer(now).also { pressTransfers[scope] = it }
        val elapsed = (now - transfer.lastTick).coerceIn(0L, TRANSFER_TICK_CAP_MILLIS)
        transfer.elapsed = (transfer.elapsed + elapsed).coerceAtMost(MineFactoryPressCycle.TOTAL_MILLIS)
        transfer.lastTick = now
        if (transfer.elapsed < MineFactoryPressCycle.TOTAL_MILLIS) return
        val driver = participants.first()
        if (complete(driver, MineExpeditionEngine.completeTarget(state, 0, now))) {
            pressTransfers.remove(scope)
        }
    }

    private fun heatText(player: Player, state: MineWorkshopHeat): Component {
        val key = when {
            state.ready -> "factory-heat-ready"
            else -> "heat-progress"
        }
        val path = if (state.ready) "mine.expedition.$key" else "mine.working.workshop.$key"
        return locale?.renderPath(path, player, mapOf(
            "progress" to Component.text((state.progress * 100).toInt()),
            "temperature" to Component.text((state.progress * 100).toInt()),
            "seconds" to Component.text(((MineWorkshopHeat.REQUIRED_MILLIS - state.elapsedMillis + 999L) / 1000L).coerceAtLeast(0L)),
        )) ?: Component.text(key)
    }

    /**
     * Connected geometry uses a strict ordered factory line.  Legacy plans
     * keep their original marker set, including tests and journals that still
     * use a walking-crank substitute for a marker.
     */
    private fun factoryTargetIsPermitted(
        scene: MineExpeditionScene,
        state: MineExpeditionState,
        target: MineExpeditionObjective,
    ): Boolean {
        if (!MineFactoryProgram.usesConnectedCrusherLine(scene.plan)) return true
        if (state.stage !in setOf(
                MineExpeditionStage.FACTORY_WATER,
                MineExpeditionStage.FACTORY_COAL,
                MineExpeditionStage.FACTORY_HEAT,
                MineExpeditionStage.FACTORY_POUR,
                MineExpeditionStage.FACTORY_CRANE,
                MineExpeditionStage.FACTORY_INSTALL,
            )) return true
        return MineExpeditionObjectives.targets(scene.plan, state, null).any { expected ->
            expected.id == target.id && expected.interaction == target.interaction &&
                (expected.target < 0 || expected.target == target.target) && expected.material == target.material
        }
    }

    /** Delivery is accepted only at the receiving station for the live lease. */
    private fun deliveryIsCurrent(
        scene: MineExpeditionScene,
        state: MineExpeditionState,
        target: MineExpeditionObjective,
        heldTarget: Int,
    ): Boolean {
        val receiver = MineExpeditionObjectives.targets(scene.plan, state, null)
            .filter { it.interaction == MineExpeditionInteraction.DELIVER && it.id == target.id }
        return receiver.any { expected ->
            expected.target < 0 || expected.target == heldTarget
        }
    }
    private fun pour(scope: String, scene: MineExpeditionScene, state: MineExpeditionState, player: Player,
        target: MineExpeditionObjective, now: Long, complete: (MineExpeditionStep) -> Boolean) {
        if(state.stage!=MineExpeditionStage.FACTORY_POUR || target.target in state.completed || cargo.containsKey(player.uniqueId)) return
        val current=pours[scope]
        if(current==null) {
            // A retry starts a fresh scoped feedback window.
            feedback.remove(scope)
            pours[scope]=Pour(scope,player.uniqueId,MineFactoryPour(now))
            if(sounds()) player.playSound(scene.at(target.position),Sound.BLOCK_PISTON_EXTEND,.7f,.65f)
            player.sendActionBar(text("pour-started",player))
        } else {
            if(current.owner!=player.uniqueId) { player.sendActionBar(text("pour-busy",player));return }
            // Debounce the opening click; close only once, with an accepted checkpoint.
            if(now-current.cycle.startedAt<500) return
            if(current.cycle.ready(now)) {
                if(complete(MineExpeditionEngine.completeTarget(state,target.target,now))) {
                    pours.remove(scope)
                    if(sounds()) player.playSound(scene.at(target.position),Sound.BLOCK_FIRE_EXTINGUISH,.9f,.8f)
                    if(particles()) scene.world.spawnParticle(Particle.CLOUD,scene.at(target.position).add(0.0,1.7,0.0),12,.5,.3,.5,.03)
                }
            } else {
                pours.remove(scope)
                player.sendActionBar(text("pour-retry",player))
                if(sounds()) player.playSound(scene.at(target.position),Sound.BLOCK_LAVA_EXTINGUISH,.6f,.8f)
            }
        }
    }
    private fun turnValve(scope: String, scene: MineExpeditionScene, state: MineExpeditionState, player: Player,
        target: MineExpeditionObjective, now: Long, complete: (MineExpeditionStep) -> Boolean) {
        val center = scene.at(target.position)
        if (cargo.containsKey(player.uniqueId) || target.target in state.completed ||
            player.world !== center.world || player.location.distanceSquared(center) > 25.0) return
        val key = scope to target.id
        val valve = valves[key]?.takeIf { it.stage == state.stage }
            ?: Valve(scope, state.stage, target.id).also { valves[key] = it }
        if (now < valve.nextClickAt) return
        releaseCrank(player.uniqueId)
        valve.nextClickAt = now + 250
        valve.lastPlayer = player.uniqueId
        valve.clicks = (valve.clicks + 1).coerceAtMost(VALVE_CLICKS)
        val at = center.clone().add(0.0, 2.5, 0.0)
        if (sounds()) center.world.playSound(at, Sound.BLOCK_GRINDSTONE_USE, .35f, 1.05f + valve.clicks * .025f)
        if (particles()) center.world.spawnParticle(Particle.CRIT, at, 3, .2, .15, .2, .015)
        player.sendActionBar(text("valve-progress", player, mapOf("count" to valve.clicks, "total" to VALVE_CLICKS)))
        if (valve.clicks == VALVE_CLICKS && complete(MineExpeditionEngine.completeTarget(state, target.target, now))) {
            valves.remove(key)
            if (sounds()) center.world.playSound(at, Sound.BLOCK_CHAIN_PLACE, .7f, 1.35f)
            if (particles()) center.world.spawnParticle(Particle.HAPPY_VILLAGER, at, 6, .3, .2, .3, 0.0)
        }
    }
    fun release(player: Player) = release(player.uniqueId)
    private fun release(id: UUID) {
        cargo.remove(id)?.let { held -> held.display?.let(renderer::remove);held.cart?.let(carts::remove) }
        pours.entries.removeIf { it.value.owner==id }
        feedback.entries.removeIf { it.value.owner == id }
        releaseCrank(id)
        operations.remove(id)
    }
    fun clear(scope: String) {
        pours.remove(scope)
        heat.remove(scope)
        feedback.remove(scope)
        chargeTransfers.remove(scope)
        pressTransfers.remove(scope)
        valves.entries.removeIf { it.value.scope == scope }
        cargo.filterValues { it.scope == scope }.keys.toList().forEach(::release)
        cranks.filterValues { it.scope == scope }.keys.toList().forEach(::releaseCrank)
        tethers.clear(scope)
        operations.entries.removeIf { it.value.scope == scope }
    }
    fun cleanup() {
        pours.clear()
        heat.clear()
        feedback.clear()
        chargeTransfers.clear()
        pressTransfers.clear()
        valves.clear()
        cargo.keys.toList().forEach(::release)
        cranks.keys.toList().forEach(::releaseCrank)
        tethers.cleanup(Bukkit.getWorlds().flatMap { it.entities })
        operations.clear()
        carts.close()
    }

    private fun pickup(scope: String, state: MineExpeditionState, player: Player, target: MineExpeditionObjective) {
        if (cargo.containsKey(player.uniqueId)) return
        val index = if (target.target >= 0) target.target else (0 until MineExpeditionEngine.targetCount(state))
            .firstOrNull { it !in state.completed && !claimed(scope, state.stage, it) } ?: return
        if (index in state.completed || claimed(scope, state.stage, index)) return
        val repairGear = state.stage == MineExpeditionStage.FACTORY_WATER && target.id.startsWith("repair_supply_")
        val held = if (repairGear || state.stage in setOf(MineExpeditionStage.FACTORY_COAL,MineExpeditionStage.FACTORY_INSTALL)) {
            val cart=runCatching {
                if (repairGear) carts.spawnModel(scope, player, material(target.material), "loose_gear")
                else carts.spawn(scope,player,material(target.material))
            }.getOrElse { error ->
                plugin.logger.log(java.util.logging.Level.WARNING,"Factory cargo cart failed scope=$scope player=${player.uniqueId}",error)
                player.sendActionBar(text("cart-failed",player));return
            }
            player.sendActionBar(text("cart-attached",player))
            Cargo(scope, state.stage, index, target.material, cart = cart)
        } else {
            val display = renderer.spawn(player, ItemStack(material(target.material)), ItemDisplay.ItemDisplayTransform.FIXED,
                0.85f, 2f, 0.75, 0.85) { it.brightness = Display.Brightness(15, 15) }
            Cargo(scope, state.stage, index, target.material, display = display)
        }
        cargo[player.uniqueId] = held
        releaseCrank(player.uniqueId)
        if (sounds()) player.playSound(player.location, Sound.BLOCK_WOOD_PLACE, 0.65f, 0.8f)
    }

    private fun releaseCrank(id: UUID) {
        cranks.remove(id)?.let { tethers.release(it.scope, id) }
    }
    private fun attachTether(scope: String, player: Player, center: org.bukkit.Location, now: Long): Boolean {
        val result = tethers.attach(scope, player, center.clone().add(0.0, .9, 0.0))
        val failure = result.exceptionOrNull() ?: return true
        tethers.release(scope, player.uniqueId)
        if (now >= nextTetherWarning) {
            nextTetherWarning = now + 30_000
            plugin.logger.log(java.util.logging.Level.WARNING, "Mine crank leash failed scope=$scope player=${player.uniqueId}", failure)
        }
        player.sendActionBar(text("turn-failed", player))
        return false
    }
    private fun sounds() = plugin.config.getBoolean("ui.sounds", true)
    private fun particles() = plugin.config.getBoolean("ui.particles", true)
    private fun text(key: String, player: Player, values: Map<String, Any> = emptyMap()): Component =
        locale?.renderPath("mine.expedition.$key", player, values.mapValues { Component.text(it.value.toString()) }) ?: Component.text(key)

    companion object {
        private const val VALVE_CLICKS = 8
        private const val CONNECTED_TRANSFER_MILLIS = 6_000L
        private const val TRANSFER_TICK_CAP_MILLIS = 1_000L
        private const val FEEDBACK_MILLIS = 2_000L
        fun material(name: String): Material = Material.matchMaterial(name) ?: Material.IRON_INGOT
    }
}
