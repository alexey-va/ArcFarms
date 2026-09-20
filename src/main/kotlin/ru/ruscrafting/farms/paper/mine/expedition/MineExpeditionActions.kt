package ru.ruscrafting.farms.paper.mine.expedition

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
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
import ru.ruscrafting.farms.paper.worksite.WorksiteCarriedDisplayRenderer
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs

/** Transient cargo leases and walking cranks; durable checkpoints belong to the domain engine. */
internal class MineExpeditionActions(private val locale: ArcFarmsLocale?) {
    private data class Cargo(val scope: String, val stage: MineExpeditionStage, val target: Int, val display: ItemDisplay)
    private data class Crank(val scope: String, val stage: MineExpeditionStage, val objective: String,
        var sample: FarmProcessingCrankState? = null, var radians: Double = 0.0)
    private val renderer = WorksiteCarriedDisplayRenderer()
    private val cargo = mutableMapOf<UUID, Cargo>()
    private val cranks = mutableMapOf<UUID, Crank>()

    fun carrying(player: Player, scope: String): Boolean = cargo[player.uniqueId]?.scope == scope
    fun claimed(scope: String, stage: MineExpeditionStage, index: Int): Boolean =
        cargo.values.any { it.scope == scope && it.stage == stage && it.target == index }

    fun interact(scope: String, scene: MineExpeditionScene, state: MineExpeditionState, player: Player,
        target: MineExpeditionObjective, now: Long, complete: (MineExpeditionStep) -> Boolean,
        drive: () -> Unit) {
        when (target.interaction) {
            MineExpeditionInteraction.PICKUP -> pickup(scope, state, player, target)
            MineExpeditionInteraction.DELIVER -> {
                val held = cargo[player.uniqueId] ?: return
                if (held.scope == scope && held.stage == state.stage &&
                    complete(MineExpeditionEngine.completeTarget(state, held.target, now))) release(player)
            }
            MineExpeditionInteraction.CRANK -> {
                if (cargo.containsKey(player.uniqueId)) return
                cranks[player.uniqueId] = Crank(scope, state.stage, target.id)
                player.sendActionBar(text("turn", player))
            }
            MineExpeditionInteraction.OPERATE -> {
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
        val participants = players.associateBy(Player::getUniqueId)
        cargo.filterValues { it.scope == scope }.toMap().forEach { (id, held) ->
            val player = participants[id]
            if (player == null || held.stage != state.stage || held.target in state.completed || !held.display.isValid) {
                release(id)
            } else renderer.move(held.display, player, 0.75, 0.85)
        }
        cranks.filterValues { it.scope == scope }.toMap().forEach { (id, crank) ->
            val player = participants[id]
            val target = targets.firstOrNull { it.id == crank.objective && it.interaction == MineExpeditionInteraction.CRANK }
            if (player == null || crank.stage != state.stage || target == null) {
                cranks.remove(id)
                return@forEach
            }
            val center = scene.at(target.position)
            if (player.world !== center.world || abs(player.location.y - center.y) > 1.8) {
                cranks.remove(id)
                return@forEach
            }
            val sample = FarmProcessingCrankTracker.sample(crank.sample, player.location.x, player.location.z,
                center.x, center.z, 0.8, 2.8, radiusTolerance = 0.25, maxStepDistance = 1.8)
            crank.sample = sample.state
            if (sample.acceptedRadians <= 0) return@forEach
            crank.radians += sample.acceptedRadians
            animateCrank(target.id, crank.radians)
            center.world.spawnParticle(Particle.CRIT, center.clone().add(0.0, 0.9, 0.0), 2, 0.15, 0.1, 0.15, 0.0)
            if (crank.radians >= PI * 1.5 && complete(player,
                    MineExpeditionEngine.completeTarget(state, target.target, now))) {
                cranks.remove(id)
                center.world.playSound(center, Sound.BLOCK_CHAIN_PLACE, 0.8f, 0.7f)
            }
        }
    }

    fun release(player: Player) = release(player.uniqueId)
    private fun release(id: UUID) {
        cargo.remove(id)?.display?.let(renderer::remove)
        cranks.remove(id)
    }
    fun clear(scope: String) {
        cargo.filterValues { it.scope == scope }.keys.toList().forEach(::release)
        cranks.entries.removeIf { it.value.scope == scope }
    }
    fun cleanup() {
        cargo.keys.toList().forEach(::release)
        cranks.clear()
    }

    private fun pickup(scope: String, state: MineExpeditionState, player: Player, target: MineExpeditionObjective) {
        if (cargo.containsKey(player.uniqueId)) return
        val index = if (target.target >= 0) target.target else (0 until MineExpeditionEngine.targetCount(state))
            .firstOrNull { it !in state.completed && !claimed(scope, state.stage, it) } ?: return
        if (index in state.completed || claimed(scope, state.stage, index)) return
        val display = renderer.spawn(player, ItemStack(material(target.material)), ItemDisplay.ItemDisplayTransform.FIXED,
            0.85f, 2f, 0.75, 0.85) { it.brightness = Display.Brightness(15, 15) }
        cargo[player.uniqueId] = Cargo(scope, state.stage, index, display)
        cranks.remove(player.uniqueId)
        player.playSound(player.location, Sound.BLOCK_WOOD_PLACE, 0.65f, 0.8f)
    }

    private fun text(key: String, player: Player): Component =
        locale?.renderPath("mine.expedition.$key", player) ?: Component.text(key)

    companion object {
        fun material(name: String): Material = Material.matchMaterial(name) ?: Material.IRON_INGOT
    }
}
