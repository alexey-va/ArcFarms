package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.MineWorkshopHeat
import ru.ruscrafting.farms.domain.mine.expedition.*

/** Local, bounded feedback for commissioned machines and the current task. No extra timers or entities. */
internal class MineFactoryPresentation(private val plugin:Plugin,private val markers:MineExpeditionMarkers) {
    private data class Frame(var nextParticles:Long=0,var nextSound:Long=0,var nextFlow:Long=0,var heatSignal:Long=-1,
        var pressHit:Boolean=false, var cargoPhase:Double=Double.NaN, var cargoResetUntil:Long=0L,
        var generatorRunning:Boolean=false, var generatorBeat:Long=-1L,
        var nextGeneratorExhaust:Long=0L, var nextGeneratorRumble:Long=0L)
    private val frames=mutableMapOf<Long,Frame>()
    fun tick(scene:MineExpeditionScene,state:MineExpeditionState,scope:String,now:Long,angles:Map<String,Double>,processingCharge:Boolean=false,
        heat: MineWorkshopHeat? = null, dieselGeneratorEnabled: Boolean = true) {
        if(scene.kind!=MineExpeditionKind.DEAD_FACTORY || scene.placement.geometryVersion<3) return
        if(state.stage==MineExpeditionStage.COMPLETE) { clear(scene); return }
        val f=frames.getOrPut(scene.journalSequence) { Frame() }
        val decor="furnish:${scene.journalSequence}"
        fun at(id:String,x:Double=0.0,y:Double=0.0,z:Double=0.0)=
            markers.at(scope,id,x,y,z) ?: markers.at(decor,id,x,y,z)
        val connected=MineFactoryProgram.usesConnectedCrusherLine(scene.plan)
        val heating = state.stage == MineExpeditionStage.FACTORY_HEAT
        val activeHeat = if (connected && heating) heat ?: MineWorkshopHeat() else null
        val heatReady = if (activeHeat != null) activeHeat.ready else heating && MineExpeditionEngine.canFinishHeat(state, now)
        val light = if (heatReady) Material.LIME_CONCRETE else if (heating) Material.YELLOW_CONCRETE else Material.RED_CONCRETE
        markers.signal(scope, "furnace_control", light)
        markers.signal(decor, "furnace_control", light)
        val pausedCrusher = MineFactoryExperiments.pending(state).any {
            it == MineFactoryExperiment.ROCK_JAM || it == MineFactoryExperiment.COOLING
        }
        val commissioned=MineFactoryProgram.runningMachines(state,angles.filterValues { it>0.0 }.keys,scene.plan)
        val ready = MineFactoryGeneratorCycle.ready(state, now)
        val running=commissioned.filterNot { (pausedCrusher || !ready) && it.contains("crusher") }
        // A local jam stops the crusher, not the factory's electrical supply.
        val generatorRunning=dieselGeneratorEnabled && connected && "decor_crusher_left" in commissioned
        if (connected && dieselGeneratorEnabled) {
            val signal = if (!generatorRunning) Material.RED_CONCRETE else if (ready) Material.LIME_CONCRETE else Material.YELLOW_CONCRETE
            markers.signal(decor, "decor_diesel_generator", signal)
            if (generatorRunning) {
                val phase = MineFactoryGeneratorCycle.phase(state, now)
                markers.rotate(decor, "decor_diesel_generator", phase % (Math.PI * 4))
                generatorEffects(f, decor, now, phase, MineFactoryGeneratorCycle.speed(state, now))
            } else {
                angles["generator_flywheel"]?.let { markers.rotate(decor, "decor_diesel_generator", it) }
            }
        }
        if (!dieselGeneratorEnabled) {
            f.generatorBeat = -1L
            f.nextGeneratorExhaust = 0L
            f.nextGeneratorRumble = 0L
        }
        f.generatorRunning=generatorRunning
        val water=state.stage!=MineExpeditionStage.FACTORY_WATER ||
            if(connected) 1 in state.completed else state.completed.isNotEmpty()
        if(connected) {
            // The action owner supplies a one-shot 0..TAU charge angle.  A missing angle
            // means that no charge is travelling; the wall clock is reserved
            // for the continuous crusher rotor below.
            val chargePhase=angles["charge_transfer"] ?: 0.0
            val chargeMoving=chargePhase>0.0 && chargePhase<Math.PI*2
            val processing=state.stage==MineExpeditionStage.FACTORY_COAL && 0 in state.completed &&
                1 !in state.completed && processingCharge
            val processedStored=state.stage==MineExpeditionStage.FACTORY_COAL && 1 in state.completed &&
                2 !in state.completed
            val transfer=processedStored && chargeMoving
            val repaired=state.stage!=MineExpeditionStage.FACTORY_WATER || 0 in state.completed ||
                state.factoryExperiments?.let { MineFactoryExperiment.DRIVE_REPAIR !in it.selected } == true
            // The crusher consumes the loose load during its operation. The
            // only moving cart is the processed charge after checkpoint 1;
            // this keeps a one-shot transfer from being confused with the
            // six-second crusher cycle itself.
            val cargoVisible=transfer
            val cargoPhase=chargePhase
            val previousCargoPhase=f.cargoPhase
            val cargoReset=cargoVisible && !previousCargoPhase.isFinite()
            if (cargoReset) f.cargoResetUntil=now + CARGO_RESET_MILLIS
            else if (!cargoVisible) {
                f.cargoPhase=Double.NaN
                f.cargoResetUntil=0L
            }
            val cargoRelease=cargoVisible && !cargoReset && f.cargoResetUntil>0L && now>=f.cargoResetUntil
            for(owner in listOf(scope,decor)) {
                markers.motionVisible(owner,"decor_crusher_left","feed",processing)
                if (cargoVisible) {
                    if (cargoReset) {
                        markers.motionVisible(owner,"decor_conveyor_raw","cargo",false)
                        markers.rotate(owner,"decor_conveyor_raw",0.0)
                        markers.repositionMotion(owner,"decor_conveyor_raw","cargo")
                    } else {
                        markers.motionLoopBoundary(owner,"decor_conveyor_raw","cargo",
                            previousCargoPhase,cargoPhase,now,CARGO_RESET_MILLIS)
                    }
                    if (cargoRelease) markers.motionVisible(owner,"decor_conveyor_raw","cargo",true)
                } else {
                    markers.motionVisible(owner,"decor_conveyor_raw","cargo",false)
                }
                markers.motionVisible(owner,"crushed_output","processed",processedStored &&
                    MineFactoryExperiment.ROUTING !in MineFactoryExperiments.pending(state))
                markers.motionVisible(owner,"crusher_repair","installed_gear",repaired)
            }
            if (cargoVisible) {
                f.cargoPhase=cargoPhase
                if (cargoRelease) f.cargoResetUntil=0L
            }
            if("decor_crusher_left" in running) {
                val gearPhase=(now%6_000L).toDouble()/6_000*Math.PI*2
                markers.rotate(decor,"crusher_repair",gearPhase)
                markers.rotate(scope,"crusher_repair",gearPhase)
            }
            if (cargoVisible) markers.rotate(decor,"decor_conveyor_raw",chargePhase)
            val rollerPhase=angles["roller_transfer"] ?: 0.0
            if(state.stage==MineExpeditionStage.FACTORY_INSTALL && rollerPhase>0.0 && rollerPhase<Math.PI*2) {
                // The roller axes point along +Z. Positive Z rotation moves
                // their top surface toward -X, so derive the sign from the
                // actual casting-to-press order instead of assuming a side.
                val load = MineFactoryLine.effectiveStation(scene.plan, "crane_load")
                val press = scene.plan.stations.getValue("assembly_socket")
                val direction = if (press.x >= load.x) -1.0 else 1.0
                markers.rotate(decor,"decor_roller_table",rollerPhase * direction)
            }
            // The thermometer is now a normalized six-second progress gauge,
            // expressed as a percentage for the existing marker API.
            val thermometerTemperature = activeHeat?.progress?.times(100.0) ?: 0.0
            markers.thermometer(scope, "furnace_control", thermometerTemperature)
            markers.thermometer(decor, "furnace_control", thermometerTemperature)
        }
        val hot=state.stage in setOf(MineExpeditionStage.FACTORY_HEAT,MineExpeditionStage.FACTORY_POUR)
        val phase=(now%12_000L).toDouble()/12_000*Math.PI*2
        if(water) markers.rotate(decor,"decor_waterwheel",phase)
        // A steady clock drives both startup and production: finishing the startup cycle never freezes the rotor.
        val crusherPhase=(now%3_000L).toDouble()/3_000*Math.PI*2
        for(id in running) markers.rotate(decor,id,if(id.contains("crusher")) crusherPhase else phase)
        val press=angles["assembly_socket"] ?: 0.0
        if(state.stage==MineExpeditionStage.FACTORY_INSTALL) {
            // Connected INSTALL exposes no duplicate receiver target; its
            // press lives in the persistent furnishing scope. Legacy plans
            // keep the original objective marker in the player scope.
            markers.rotate(scope,"assembly_socket",press)
            if (connected) markers.rotate(decor,"assembly_socket",press)
        }
        if(press==0.0) f.pressHit=false
        if(press>=Math.PI && !f.pressHit) {
            f.pressHit=true
            sound(at("assembly_socket",y=1.5),Sound.BLOCK_ANVIL_LAND,.75f,.8f)
            particles(at("assembly_socket",y=1.8),Particle.CRIT,14,.65,.12,.6,.09)
            particles(at("assembly_socket",y=1.8),Particle.CLOUD,6,.5,.12,.4,.025)
        }
        val heatSignalKey=if (activeHeat != null) if (heatReady) 1L else 0L else state.heatStartedAt
        if(state.stage==MineExpeditionStage.FACTORY_HEAT && heatReady && f.heatSignal!=heatSignalKey) {
            f.heatSignal=heatSignalKey
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
    private fun generatorEffects(frame: Frame, decor: String, now: Long, phase: Double, speed: Double) {
        fun at(point: org.joml.Vector3f) = markers.at(decor, "decor_diesel_generator",
            point.x.toDouble(), point.y.toDouble(), point.z.toDouble())
        val body = markers.at(decor, "decor_diesel_generator", 0.0, 2.5, 0.0)
        if (!frame.generatorRunning) sound(body, Sound.BLOCK_PISTON_EXTEND, .2f, .6f)
        val position = phase / (Math.PI * 4) * MineDieselGeneratorMotion.CYCLE_MILLIS
        val beat = kotlin.math.floor(position / MineDieselGeneratorMotion.IGNITION_MILLIS + 1e-7).toLong()
        if (beat != frame.generatorBeat) {
            frame.generatorBeat=beat
            // Never replay missed beats after a lag spike, or flash a cylinder
            // whose piston has already left the compression TDC window.
            if (position - beat * MineDieselGeneratorMotion.IGNITION_MILLIS < MineDieselGeneratorMotion.IGNITION_WINDOW_MILLIS) {
                val cylinder=MineDieselGeneratorMotion.ignitionCylinder(beat * MineDieselGeneratorMotion.IGNITION_MILLIS)
                particles(at(MineDieselGeneratorModel.combustionPoints[cylinder]), Particle.SMALL_FLAME, 3,
                    .085, .025, .11, .002)
                sound(body, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, .28f, (.5 + .15 * speed).toFloat())
                sound(body, Sound.BLOCK_PISTON_CONTRACT, .1f, (.55 + .2 * speed).toFloat())
                if (beat % 2L == 0L) sound(body, Sound.BLOCK_CHAIN_STEP, .075f, (.7 + .2 * speed).toFloat())
            }
        }
        if (now >= frame.nextGeneratorExhaust) {
            frame.nextGeneratorExhaust=now+(MineDieselGeneratorMotion.EXHAUST_MILLIS / speed).toLong()
            val mouth=at(MineDieselGeneratorModel.exhaustMouth)
            particles(mouth, Particle.CAMPFIRE_COSY_SMOKE, 1, .035, .02, .035, .008)
            particles(mouth, Particle.SMOKE, 1, .05, .025, .05, .018)
        }
        if (now >= frame.nextGeneratorRumble) {
            frame.nextGeneratorRumble=now+3_000L
            sound(body, Sound.BLOCK_GRINDSTONE_USE, .09f, (.5 + .1 * speed).toFloat())
        }
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
    fun clear(scene:MineExpeditionScene) {
        frames.remove(scene.journalSequence)
        if (MineFactoryProgram.usesConnectedCrusherLine(scene.plan)) {
            markers.signal("furnish:${scene.journalSequence}", "decor_diesel_generator", Material.RED_CONCRETE)
        }
    }
    fun cleanup() { frames.clear() }

    private companion object {
        const val CARGO_RESET_MILLIS = 100L
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
