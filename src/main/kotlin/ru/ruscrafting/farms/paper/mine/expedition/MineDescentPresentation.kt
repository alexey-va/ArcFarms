package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionEngine
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionMotion
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionStage
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionState

/** Presentation follows the saved machine phase; no independent task or entity lifecycle. */
internal class MineDescentPresentation(private val plugin: Plugin, private val markers: MineExpeditionMarkers) {
    private data class Frame(var nextSound: Long = 0, var nextParticles: Long = 0)
    private val frames = mutableMapOf<Long, Frame>()

    fun tick(scene: MineExpeditionScene, state: MineExpeditionState, scope: String, now: Long,
        angles: Map<String, Double>, moving: Boolean, pumpStarting: Boolean, cellClaimed: Boolean = false) {
        if (!MineExpeditionEngine.isModernLastDescent(state) || !scene.plan.stations.containsKey("descent_pump")) return
        val f = frames.getOrPut(scene.journalSequence) { Frame() }
        val decor = "furnish:${scene.journalSequence}"
        val powered = state.stage in setOf(MineExpeditionStage.DESCENT_BOTTOM,
            MineExpeditionStage.DESCENT_CORE_VALVES, MineExpeditionStage.DESCENT_ENGINE, MineExpeditionStage.COMPLETE)
        val pumping = state.stage in setOf(MineExpeditionStage.DESCENT_ENGINE, MineExpeditionStage.COMPLETE)
        val phase = now % 8_000L / 8_000.0 * Math.PI * 2
        val brakeReleased = state.stage !in setOf(MineExpeditionStage.DESCENT_MIDDLE, MineExpeditionStage.DESCENT_COUNTERWEIGHTS)
        for(owner in listOf(scope,decor)) {
            for(id in listOf("counterweight_1", "core_valve_0", "core_valve_1")) {
                markers.rotate(owner,id,angles[id] ?: 0.0)
            }
            markers.rotate(owner,"counterweight_2",if(brakeReleased) Math.PI else 0.0)
            markers.rotate(owner,"core_valve_2",if(pumpStarting || pumping) Math.PI else 0.0)
            markers.rotate(owner,"decor_descent_pump",when {
                pumping || pumpStarting -> phase
                state.stage == MineExpeditionStage.DESCENT_CORE_VALVES -> angles["core_valve_1"] ?: 0.0
                else -> 0.0
            })
            markers.rotate(owner,"decor_descent_winder",if(moving) phase else 0.0)
            markers.signal(owner,"power_socket",if(powered) Material.LIME_CONCRETE else Material.RED_CONCRETE)
            markers.signal(owner,"core_valve_2",if(pumping) Material.LIME_CONCRETE else if(pumpStarting) Material.YELLOW_CONCRETE else Material.RED_CONCRETE)
            markers.motionVisible(owner,"power_supply","descent_cell",!powered && !cellClaimed)
            markers.motionVisible(owner,"power_socket","descent_cell",powered)
        }
        if(now >= f.nextParticles && (pumping || pumpStarting)) {
            f.nextParticles = now + 500
            if(plugin.config.getBoolean("ui.particles",true)) {
                val at = markers.at(decor,"decor_descent_pump",0.0,3.0,0.0)
                    ?: scene.at(scene.plan.stations.getValue("descent_pump")).add(0.0,3.0,0.0)
                scene.world.spawnParticle(Particle.SPLASH,at,5,1.4,.15,.3,0.0)
            }
        }
        if(now >= f.nextSound && (moving || pumping || pumpStarting)) {
            f.nextSound = now + 1_600
            if(plugin.config.getBoolean("ui.sounds",true)) {
                val source = if(moving) scene.at(MineExpeditionMotion.position(scene.plan,state))
                    else scene.at(scene.plan.stations.getValue("descent_pump"))
                scene.world.players.filter { it.isOnline && scene.contains(it.location) &&
                    it.location.distanceSquared(source)<=32.0*32.0 }.forEach { player ->
                    player.playSound(source,
                        if(moving) Sound.BLOCK_CHAIN_STEP else Sound.BLOCK_PISTON_EXTEND,.35f,.65f)
                }
            }
        }
    }

    fun clear(scene: MineExpeditionScene) { frames.remove(scene.journalSequence) }
    fun cleanup() { frames.clear() }
}
