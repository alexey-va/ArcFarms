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
    private data class Cargo(val scope: String, val stage: MineExpeditionStage, val target: Int, val display: ItemDisplay? = null, val cart: MineFactoryCarts.Cart? = null)
    private data class Crank(val scope: String, val stage: MineExpeditionStage, val objective: String,
        var sample: FarmProcessingCrankState? = null, var radians: Double = 0.0)
    private data class Operation(val scope:String,val objective:String,val target:Int,val cycle:MineFactoryOperation)
    private data class Valve(val scope: String, val stage: MineExpeditionStage, val objective: String,
        var clicks: Int = 0, var nextClickAt: Long = 0, var lastPlayer: UUID? = null)
    private data class Pour(val scope: String, val owner: UUID, val cycle: MineFactoryPour, var readySignalled: Boolean = false)
    private val pours = mutableMapOf<String, Pour>()
    private val valves = mutableMapOf<Pair<String, String>, Valve>()
    private val operations=mutableMapOf<UUID,Operation>()
    private val renderer = WorksiteCarriedDisplayRenderer()
    private val cargo = mutableMapOf<UUID, Cargo>()
    private val cranks = mutableMapOf<UUID, Crank>()

    fun operationPhase(scope:String,id:String,now:Long):Double = operations.values
        .firstOrNull { it.scope==scope && it.objective==id }?.cycle?.progress(now)?.times(PI*2) ?: 0.0
    fun owns(entity: Entity) = tethers.owns(entity) || carts.owns(entity)
    fun removeOrphans(entities: Iterable<Entity>) { tethers.removeOrphans(entities); carts.removeOrphans(entities) }
    fun pourLabel(scope: String, now: Long): String = pours[scope]?.let {
        if(it.cycle.ready(now)) "pour-close" else "pour-filling"
    } ?: "control.pour_console"
    fun pourValues(scope: String, now: Long): Map<String, Component> {
        val level = pours[scope]?.cycle?.level(now) ?: 0.0
        val fill = (level*20).toInt()
        val meter = (0 until 20).fold(Component.empty()) { line,index -> line.append(Component.text(if(index<fill) "▰" else "▱",
            if(index in 13..17) net.kyori.adventure.text.format.NamedTextColor.GREEN
            else net.kyori.adventure.text.format.NamedTextColor.GRAY)) }
        return mapOf("percent" to Component.text((level*100).toInt()), "meter" to meter)
    }
    fun hint(scope: String, player: Player, now: Long): Component? {
        pours[scope]?.takeIf { it.owner==player.uniqueId }?.let {
            return locale?.renderPath("mine.expedition.${pourLabel(scope,now)}",player,pourValues(scope,now))
                ?: Component.text(pourLabel(scope,now))
        }
        valves.values.filter { it.scope == scope && it.lastPlayer == player.uniqueId }.maxByOrNull { it.nextClickAt }?.let {
            return text("valve-progress", player, mapOf("count" to it.clicks, "total" to VALVE_CLICKS))
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
        when (target.interaction) {
            MineExpeditionInteraction.POUR -> pour(scope,scene,state,player,target,now,complete)
            MineExpeditionInteraction.VALVE -> turnValve(scope, scene, state, player, target, now, complete)
            MineExpeditionInteraction.PICKUP -> pickup(scope, state, player, target)
            MineExpeditionInteraction.DELIVER -> {
                val held = cargo[player.uniqueId] ?: return
                if (held.scope != scope || held.stage != state.stage) return
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
                        player.sendActionBar(text("fuel-loaded", player, mapOf("count" to state.completed.size + 1, "total" to MineExpeditionEngine.targetCount(state))))
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
                if(state.stage in setOf(MineExpeditionStage.FACTORY_CRANE, MineExpeditionStage.FACTORY_WATER)) {
                    startOperation(scope,state,player,target.id,target.target,now)
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
        if(state.stage==MineExpeditionStage.FACTORY_POUR && scope !in pours) animateCrank("pour_console",0.0)
        pours[scope]?.let { pour ->
            val player=participants[pour.owner]
            if(player==null || state.stage!=MineExpeditionStage.FACTORY_POUR || pour.cycle.overflow(now)) {
                pours.remove(scope)
                animateCrank("pour_console",0.0)
                if(player!=null && state.stage==MineExpeditionStage.FACTORY_POUR) {
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
    private fun pour(scope: String, scene: MineExpeditionScene, state: MineExpeditionState, player: Player,
        target: MineExpeditionObjective, now: Long, complete: (MineExpeditionStep) -> Boolean) {
        if(state.stage!=MineExpeditionStage.FACTORY_POUR || target.target in state.completed || cargo.containsKey(player.uniqueId)) return
        val current=pours[scope]
        if(current==null) {
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
        releaseCrank(id)
        operations.remove(id)
    }
    fun clear(scope: String) {
        pours.remove(scope)
        valves.entries.removeIf { it.value.scope == scope }
        cargo.filterValues { it.scope == scope }.keys.toList().forEach(::release)
        cranks.filterValues { it.scope == scope }.keys.toList().forEach(::releaseCrank)
        tethers.clear(scope)
        operations.entries.removeIf { it.value.scope == scope }
    }
    fun cleanup() {
        pours.clear()
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
        val held = if(state.stage in setOf(MineExpeditionStage.FACTORY_COAL,MineExpeditionStage.FACTORY_INSTALL)) {
            val cart=runCatching { carts.spawn(scope,player,material(target.material)) }.getOrElse { error ->
                plugin.logger.log(java.util.logging.Level.WARNING,"Factory cargo cart failed scope=$scope player=${player.uniqueId}",error)
                player.sendActionBar(text("cart-failed",player));return
            }
            player.sendActionBar(text("cart-attached",player))
            Cargo(scope,state.stage,index,cart=cart)
        } else {
            val display = renderer.spawn(player, ItemStack(material(target.material)), ItemDisplay.ItemDisplayTransform.FIXED,
                0.85f, 2f, 0.75, 0.85) { it.brightness = Display.Brightness(15, 15) }
            Cargo(scope, state.stage, index, display)
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
        fun material(name: String): Material = Material.matchMaterial(name) ?: Material.IRON_INGOT
    }
}
