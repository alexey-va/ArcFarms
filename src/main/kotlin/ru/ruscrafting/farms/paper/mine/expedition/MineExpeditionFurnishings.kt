package ru.ruscrafting.farms.paper.mine.expedition

import net.kyori.adventure.text.Component
import org.bukkit.Material
import ru.ruscrafting.farms.domain.mine.expedition.ExpeditionPoint
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind

/** Permanent editable assemblies; gameplay adds glow only to the currently required controls. */
internal object MineExpeditionFurnishings {
    data class Fixture(val id:String,val model:String,val at:ExpeditionPoint,val scale:Float=1f,val yaw:Int=0)
    fun baseYaw(scene:MineExpeditionScene,id:String):Int = fixtures(scene).firstOrNull { it.id==id }?.yaw ?: 0
    fun parent(kind:MineExpeditionKind,id:String):String? = if(kind!=MineExpeditionKind.DEAD_FACTORY) null else when(id) {
        "furnace_input","furnace_control" -> "decor_furnace_left"
        "pour_control" -> "decor_furnace_right"
        "pour_console" -> "pour_control"
        "control_crusher_left", "control_crusher_right", "control_pump_left", "control_pump_right" -> ru.ruscrafting.farms.domain.mine.expedition.MineFactoryProgram.machine(id)
        "water_valve_1" -> "decor_waterwheel"
        else -> null
    }
    fun childOf(kind:MineExpeditionKind,id:String,ancestor:String):Boolean =
        generateSequence(parent(kind,id)) { parent(kind,it) }.any { it==ancestor }
    fun model(id:String,kind:MineExpeditionKind?=null):String = when {
        id.startsWith("control_") || id=="pour_console" -> "machine_console"
        id=="water_valve_1" -> "sluice"
        id.startsWith("water_valve_") -> "pipe_valve"
        id=="furnace_input" -> "feed_hopper"
        id=="furnace_control" -> "furnace_console"
        id=="pour_control" -> "casting_bed"
        id=="crane_control" -> "crane_console"
        id=="crane_load" -> "casting_rack"
        id=="assembly_socket" -> "assembly_bench"
        id=="fuel_supply" && kind==MineExpeditionKind.DEAD_FACTORY -> "coal_bunker"
        id.startsWith("counterweight") -> "winch"
        id.contains("supply") || id.startsWith("survey") -> "rack"
        id.contains("valve") -> "valve"
        else -> "console"
    }
    fun fixtures(scene:MineExpeditionScene):List<Fixture> = fixtures(scene.plan)
    fun fixtures(plan:ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlan):List<Fixture> {
        val stations=plan.stations.filterKeys { id ->
            id !in setOf("entry","exit") && !id.startsWith("lift_") && !id.startsWith("ark_") &&
                !id.startsWith("jam_") && !id.startsWith("branch_")
        }.map { (id,p) -> Fixture(id,model(id,plan.kind),p) }
        val decor=when(plan.kind) {
            MineExpeditionKind.DEAD_FACTORY -> listOf(
                Fixture("decor_waterwheel","waterwheel",ExpeditionPoint(0,5,-23)),
                Fixture("decor_crusher_left","crusher",ru.ruscrafting.farms.domain.mine.expedition.MineFactoryProgram.machines.getValue("decor_crusher_left"),2.2f,180),
                Fixture("decor_crusher_right","crusher",ru.ruscrafting.farms.domain.mine.expedition.MineFactoryProgram.machines.getValue("decor_crusher_right"),2.2f,180),
                Fixture("decor_furnace_left","furnace",ExpeditionPoint(-17,5,-13)),
                Fixture("decor_furnace_right","furnace",ExpeditionPoint(17,5,-13)),
                Fixture("decor_pump_left","pump",ru.ruscrafting.farms.domain.mine.expedition.MineFactoryProgram.machines.getValue("decor_pump_left"),1.8f),
                Fixture("decor_pump_right","pump",ru.ruscrafting.farms.domain.mine.expedition.MineFactoryProgram.machines.getValue("decor_pump_right"),1.8f),
                Fixture("decor_tank_left","tank",ExpeditionPoint(-27,5,17),1.8f),
                Fixture("decor_tank_right","tank",ExpeditionPoint(27,5,17),1.8f),
            )
            MineExpeditionKind.LAST_DESCENT -> listOf(
                Fixture("decor_top_winch","winch",ExpeditionPoint(-12,25,8)),
                Fixture("decor_top_rack","rack",ExpeditionPoint(12,25,8)),
                Fixture("decor_middle_pump","pump",ExpeditionPoint(12,15,-8)),
                Fixture("decor_core_tank","tank",ExpeditionPoint(12,5,-8)),
            )
            MineExpeditionKind.DRILLING_ARK -> listOf(
                Fixture("decor_service_pump","pump",ExpeditionPoint(24,5,-14)),
                Fixture("decor_fuel_tank","tank",ExpeditionPoint(-24,5,-14)),
                Fixture("decor_tool_rack","rack",ExpeditionPoint(24,5,10)),
                Fixture("decor_sample_rack","rack",ExpeditionPoint(-24,5,10)),
            )
        }
        val controls = if(plan.kind==MineExpeditionKind.DEAD_FACTORY) {
            ru.ruscrafting.farms.domain.mine.expedition.MineFactoryProgram.controls.map { (id,at) ->
                Fixture(id,"machine_console",at)
            } + Fixture("pour_console","machine_console",plan.stations.getValue("pour_control").offset(-4,0,1))
        } else emptyList()
        return stations+decor+controls
    }
    fun targets(scene:MineExpeditionScene,editor:MineFurnishingEditor?,active:Set<String>):List<MineExpeditionMarkers.Target> =
        fixtures(scene).filter { it.id !in active }.map { f ->
            val yaw=editor?.yaw(scene,f.id) ?: f.yaw
            val p=editor?.position(scene,f.id,f.at) ?: f.at
            MineExpeditionMarkers.Target(f.id,scene.at(p),Material.CUT_COPPER,Component.empty(),model=f.model,modelScale=f.scale,
                glowing=editor?.selected(scene.journalSequence,f.id)==true || active.any { !it.startsWith("control_") && it!="pour_console" && parent(scene.kind,it)==f.id },yaw=yaw,editKey="${scene.journalSequence}/${f.id}",editBase=f.at)
        }
}
