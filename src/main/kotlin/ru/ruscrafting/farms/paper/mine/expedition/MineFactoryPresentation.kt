package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.mine.expedition.*

/** Local, bounded feedback for commissioned machines and the current task. No extra timers or entities. */
internal class MineFactoryPresentation(private val plugin:Plugin,private val markers:MineExpeditionMarkers) {
    private data class Frame(var nextParticles:Long=0,var nextSound:Long=0,var nextFlow:Long=0,var heatSignal:Long=-1,
        var pressHit:Boolean=false)
    private val frames=mutableMapOf<Long,Frame>()
    fun tick(scene:MineExpeditionScene,state:MineExpeditionState,scope:String,now:Long,angles:Map<String,Double>,processingCharge:Boolean=false) {
        if(scene.kind!=MineExpeditionKind.DEAD_FACTORY || scene.placement.geometryVersion<3) return
        if(state.stage==MineExpeditionStage.COMPLETE) { clear(scene); return }
        val f=frames.getOrPut(scene.journalSequence) { Frame() }
        val decor="furnish:${scene.journalSequence}"
        fun at(id:String,x:Double=0.0,y:Double=0.0,z:Double=0.0)=
            markers.at(scope,id,x,y,z) ?: markers.at(decor,id,x,y,z)
        val heating = state.stage == MineExpeditionStage.FACTORY_HEAT
        val heatReady = heating && MineExpeditionEngine.canFinishHeat(state, now)
        val light = if (heatReady) Material.LIME_CONCRETE else if (heating) Material.YELLOW_CONCRETE else Material.RED_CONCRETE
        markers.signal(scope, "furnace_control", light)
        markers.signal(decor, "furnace_control", light)
        val running=MineFactoryProgram.runningMachines(state,angles.filterValues { it>0.0 }.keys,scene.plan)
        val connected=MineFactoryProgram.usesConnectedCrusherLine(scene.plan)
        val water=state.stage!=MineExpeditionStage.FACTORY_WATER ||
            if(connected) 1 in state.completed else state.completed.isNotEmpty()
        if(connected) {
            val processing=state.stage==MineExpeditionStage.FACTORY_COAL && 0 in state.completed &&
                1 !in state.completed && processingCharge
            val processed=state.stage==MineExpeditionStage.FACTORY_COAL && 1 in state.completed && 2 !in state.completed
            val repaired=state.stage!=MineExpeditionStage.FACTORY_WATER || 0 in state.completed
            for(owner in listOf(scope,decor)) {
                markers.motionVisible(owner,"decor_crusher_left","feed",processing)
                markers.motionVisible(owner,"decor_conveyor_raw","cargo",processing)
                markers.motionVisible(owner,"crushed_output","processed",processed)
                markers.motionVisible(owner,"crusher_repair","installed_gear",repaired)
            }
            if("decor_crusher_left" in running) {
                val beltPhase=(now%6_000L).toDouble()/6_000*Math.PI*2
                markers.rotate(decor,"decor_conveyor_raw",beltPhase)
                markers.rotate(decor,"crusher_repair",beltPhase)
                markers.rotate(scope,"crusher_repair",beltPhase)
            }
            if(state.stage==MineExpeditionStage.FACTORY_CRANE && (angles["crane_control"] ?: 0.0)>0.0)
                markers.rotate(decor,"decor_roller_table",(now%2_000L).toDouble()/2_000*Math.PI*2)
        }
        val hot=state.stage in setOf(MineExpeditionStage.FACTORY_HEAT,MineExpeditionStage.FACTORY_POUR)
        val phase=(now%12_000L).toDouble()/12_000*Math.PI*2
        if(water) markers.rotate(decor,"decor_waterwheel",phase)
        // A steady clock drives both startup and production: finishing the startup cycle never freezes the rotor.
        val crusherPhase=(now%3_000L).toDouble()/3_000*Math.PI*2
        for(id in running) markers.rotate(decor,id,if(id.contains("crusher")) crusherPhase else phase)
        val press=angles["assembly_socket"] ?: 0.0
        if(state.stage==MineExpeditionStage.FACTORY_INSTALL) markers.rotate(scope,"assembly_socket",press)
        if(press==0.0) f.pressHit=false
        if(press>=Math.PI && !f.pressHit) {
            f.pressHit=true
            sound(at("assembly_socket",y=1.5),Sound.BLOCK_ANVIL_LAND,.75f,.8f)
            particles(at("assembly_socket",y=1.8),Particle.CRIT,14,.65,.12,.6,.09)
            particles(at("assembly_socket",y=1.8),Particle.CLOUD,6,.5,.12,.4,.025)
        }
        if(state.stage==MineExpeditionStage.FACTORY_HEAT && MineExpeditionEngine.canFinishHeat(state,now) && f.heatSignal!=state.heatStartedAt) {
            f.heatSignal=state.heatStartedAt
            sound(at("furnace_control",y=2.2),Sound.BLOCK_NOTE_BLOCK_BELL,.8f,1.3f)
            particles(at("furnace_control",y=3.1),Particle.HAPPY_VILLAGER,10,.8,.2,.2,0.0)
        }
        val particleTick=now>=f.nextParticles
        val soundTick=now>=f.nextSound
        if(particleTick) f.nextParticles=now+350
        if(soundTick) f.nextSound=now+1_400
        if(water) {
            if(particleTick) particles(at("decor_waterwheel",y=1.2,z=.9),Particle.SPLASH,7,2.6,.25,.3,.025)
            if(soundTick) sound(at("decor_waterwheel",y=2.0),Sound.BLOCK_WATER_AMBIENT,.5f,.8f)
        }
        if (heatReady && particleTick) particles(at("furnace_control",y=3.6),Particle.END_ROD,3,.4,.25,.3,.012)
        if(hot) {
            if(particleTick) {
                particles(at("decor_furnace_left",y=2.8,z=2.3),Particle.SMALL_FLAME,5,1.3,.45,.1,.005)
                if(connected) particles(at("decor_furnace_left",x=-3.0,y=3.0,z=-1.3),Particle.SMALL_FLAME,3,.4,.4,.07,.003)
                particles(at("decor_furnace_left",y=11.5,z=-1.0),Particle.CAMPFIRE_COSY_SMOKE,2,.5,.15,.5,.025)
            }
            if(soundTick) sound(at("decor_furnace_left",y=3.0,z=2.0),Sound.BLOCK_FURNACE_FIRE_CRACKLE,.65f,.85f)
        }
        for(id in running) {
            if(id.contains("crusher")) {
                if(particleTick) {
                    particles(at(id,y=2.2,z=.2),Particle.ASH,4,.55,.2,.5,.012)
                    particles(at(id,y=2.3,z=.2),Particle.CLOUD,2,.4,.12,.35,.006)
                }
                if(soundTick) {
                    sound(at(id,y=2.0),Sound.BLOCK_GRINDSTONE_USE,.55f,.65f)
                    sound(at(id,y=2.0),Sound.BLOCK_STONE_BREAK,.2f,.6f)
                }
            } else if(state.stage==MineExpeditionStage.FACTORY_WATER) {
                if(particleTick) particles(at(id,y=2.2),Particle.SPLASH,8,.5,.3,.5,.025)
                if(soundTick) sound(at(id,y=2.0),Sound.BLOCK_PISTON_EXTEND,.6f,.7f)
            }
        }
        val pouring=state.stage==MineExpeditionStage.FACTORY_POUR && (angles["pour_control"] ?: 0.0)>0
        if(pouring) {
            if(now>=f.nextFlow) {
                f.nextFlow=now+100
                // Three bright travelling pulses follow the glazed pipe, including its elbows.
                // Resolve each point through the editable assembly so rotation/movement cannot detach the effect.
                for(pulse in 0..2) for(tail in 0..2) {
                    val distance=((now%3_000L)/3_000.0*METAL_PATH_LENGTH+pulse*METAL_PATH_LENGTH/3-tail*.12+METAL_PATH_LENGTH)%METAL_PATH_LENGTH
                    val p=metalPoint(distance)
                    particles(at("pour_control",p.x,p.y,p.z),Particle.SMALL_FLAME,1,.025,.025,.025,0.0)
                }
            }
            if(particleTick) {
                particles(at("pour_control",x=1.05,y=2.30,z=-.3),Particle.FALLING_LAVA,3,.045,.025,.045,0.0)
                particles(at("pour_control",y=1.8),Particle.SMALL_FLAME,5,1.1,.1,.7,.01)
            }
            if(soundTick) sound(at("pour_control",y=2.0),Sound.BLOCK_LAVA_POP,.65f,.65f)
        }
        if(state.stage==MineExpeditionStage.FACTORY_CRANE && (angles["crane_control"] ?: 0.0)>0 && soundTick)
            sound(at("crane_control",y=2.0),Sound.BLOCK_CHAIN_STEP,.7f,.6f)
        if(state.stage==MineExpeditionStage.FACTORY_INSTALL && press>0 && press<Math.PI && soundTick)
            sound(at("assembly_socket",y=3.0),Sound.BLOCK_PISTON_EXTEND,.7f,.55f)
    }
    fun finished(at: Location, first: Boolean) {
        if (first) sound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 1f, .8f)
        particles(at.clone().add(0.0, 1.0, 0.0), Particle.END_ROD, 5, .7, .5, .5, .015)
    }
    private fun sound(at:Location?,sound:Sound,volume:Float,pitch:Float) {
        if(at==null || !plugin.config.getBoolean("ui.sounds",true)) return
        at.world.players.filter { it.location.distanceSquared(at)<=32*32 }.forEach { it.playSound(at,sound,volume,pitch) }
    }
    private fun particles(at:Location?,type:Particle,count:Int,x:Double,y:Double,z:Double,speed:Double) {
        if(at==null || !plugin.config.getBoolean("ui.particles",true)) return
        at.world.spawnParticle(type,at,count,x,y,z,speed)
    }
    fun clear(scene:MineExpeditionScene) { frames.remove(scene.journalSequence) }
    fun cleanup() { frames.clear() }

    private companion object {
        val METAL_PATH=MineFactoryModels.moltenPath.map {
            org.bukkit.util.Vector(it.x.toDouble(),it.y.toDouble(),it.z.toDouble())
        }
        val METAL_PATH_LENGTH=METAL_PATH.zipWithNext().sumOf { (a,b)->a.distance(b) }
        fun metalPoint(distance:Double):org.bukkit.util.Vector {
            var remaining=distance
            for((a,b) in METAL_PATH.zipWithNext()) {
                val length=a.distance(b)
                if(remaining<=length) return b.clone().subtract(a).multiply(remaining/length).add(a)
                remaining-=length
            }
            return METAL_PATH.last().clone()
        }
    }
}
