package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.mine.expedition.*

/** Local, bounded feedback for the active production stage. No timers or persistent entities. */
internal class MineFactoryPresentation(private val plugin:Plugin,private val markers:MineExpeditionMarkers) {
    private data class Frame(var nextParticles:Long=0,var nextSound:Long=0,var heatSignal:Long=-1,
        var pressHit:Boolean=false)
    private val frames=mutableMapOf<Long,Frame>()
    fun tick(scene:MineExpeditionScene,state:MineExpeditionState,scope:String,now:Long,angles:Map<String,Double>) {
        if(scene.kind!=MineExpeditionKind.DEAD_FACTORY || scene.placement.geometryVersion<3) return
        val f=frames.getOrPut(scene.journalSequence) { Frame() }
        val decor="furnish:${scene.journalSequence}"
        fun at(id:String,x:Double=0.0,y:Double=0.0,z:Double=0.0)=
            markers.at(scope,id,x,y,z) ?: markers.at(decor,id,x,y,z)
        val heating = state.stage == MineExpeditionStage.FACTORY_HEAT
        val heatReady = heating && MineExpeditionEngine.canFinishHeat(state, now)
        val light = if (heatReady) Material.LIME_CONCRETE else if (heating) Material.YELLOW_CONCRETE else Material.RED_CONCRETE
        markers.signal(scope, "furnace_control", light)
        markers.signal(decor, "furnace_control", light)
        if (state.stage == MineExpeditionStage.FACTORY_WATER) {
            MineFactoryProgram.targets(scene.plan,state).filter { it.interaction == MineExpeditionInteraction.OPERATE }.forEach { target ->
                val angle=angles[target.id] ?: 0.0
                if(angle>0.0) {
                    markers.rotate(scope,target.id,angle*3)
                    if(now>=f.nextSound) sound(at(target.id,y=2.0),if(target.id.contains("pump")) Sound.BLOCK_PISTON_EXTEND else Sound.BLOCK_GRINDSTONE_USE,.6f,.7f)
                    if(now>=f.nextParticles) particles(at(target.id,y=2.2),if(target.id.contains("pump")) Particle.SPLASH else Particle.ASH,8,.5,.3,.5,.025)
                }
            }
        }
        val water=state.stage!=MineExpeditionStage.FACTORY_WATER || state.completed.isNotEmpty()
        val hot=state.stage in setOf(MineExpeditionStage.FACTORY_HEAT,MineExpeditionStage.FACTORY_POUR)
        val phase=(now%12_000L).toDouble()/12_000*Math.PI*2
        for(id in listOf("decor_waterwheel","decor_pump_left","decor_pump_right","decor_crusher_left","decor_crusher_right")) {
            val working=water && (!id.contains("crusher") || state.stage in setOf(MineExpeditionStage.FACTORY_COAL,MineExpeditionStage.FACTORY_HEAT))
            if(working) markers.rotate(decor,id,phase)
        }
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
                particles(at("decor_furnace_left",y=11.5,z=-1.0),Particle.CAMPFIRE_COSY_SMOKE,2,.5,.15,.5,.025)
            }
            if(soundTick) sound(at("decor_furnace_left",y=3.0,z=2.0),Sound.BLOCK_FURNACE_FIRE_CRACKLE,.65f,.85f)
        }
        if(state.stage in setOf(MineExpeditionStage.FACTORY_COAL,MineExpeditionStage.FACTORY_HEAT)) {
            if(particleTick) for(id in listOf("decor_crusher_left","decor_crusher_right"))
                particles(at(id,y=2.2,z=.2),Particle.ASH,3,.45,.2,.5,.012)
            if(soundTick) sound(at("decor_crusher_left",y=2.0),Sound.BLOCK_GRINDSTONE_USE,.4f,.65f)
        }
        val pouring=state.stage==MineExpeditionStage.FACTORY_POUR && (angles["pour_control"] ?: 0.0)>0
        if(pouring) {
            if(particleTick) {
                particles(at("pour_control",y=2.6,z=-1.0),Particle.FALLING_LAVA,4,.3,.3,.15,0.0)
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
}
