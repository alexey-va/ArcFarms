package ru.ruscrafting.farms.paper.mine.expedition

import net.kyori.adventure.text.Component
import org.bukkit.Material
import ru.ruscrafting.farms.domain.mine.expedition.ExpeditionPoint
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryLine
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind

/** Permanent editable assemblies; gameplay adds glow only to the currently required controls. */
internal object MineExpeditionFurnishings {
    data class Fixture(val id:String,val model:String,val at:ExpeditionPoint,val scale:Float=1f,val yaw:Int=0)
    fun baseYaw(scene:MineExpeditionScene,id:String):Int = fixtures(scene).firstOrNull { it.id==id }?.yaw ?: 0
    fun parent(kind:MineExpeditionKind,id:String):String? = if(kind!=MineExpeditionKind.DEAD_FACTORY) null else
        MineFactoryLine.owners[id] ?: when(id) {
            // Geometry v1/v2 compatibility: those plans still use the original
            // commissioning controls and mirrored utility assemblies.
            "furnace_input","furnace_control" -> "decor_furnace_left"
            "pour_console" -> "pour_control"
            "control_crusher_left", "control_crusher_right", "control_pump_left", "control_pump_right" ->
                ru.ruscrafting.farms.domain.mine.expedition.MineFactoryProgram.machine(id)
            "water_valve_1" -> "decor_waterwheel"
            else -> null
        }
    fun childOf(kind:MineExpeditionKind,id:String,ancestor:String):Boolean =
        generateSequence(parent(kind,id)) { parent(kind,it) }.any { it==ancestor }
    fun model(id:String,kind:MineExpeditionKind?=null):String = when {
        id=="fuel_supply" && kind==MineExpeditionKind.DEAD_FACTORY -> "charge_bunker"
        id=="crusher_feed" || id=="furnace_input" -> "inlet_hopper"
        id=="crushed_output" -> "charge_hopper"
        id=="control_crusher_left" || id=="furnace_control" || id=="pour_console" || id=="crane_control" -> "mounted_console"
        id=="water_valve_1" -> "pipe_valve"
        id.startsWith("water_valve_") -> "pipe_valve"
        id.startsWith("repair_supply_") -> "loose_gear"
        id=="crusher_repair" -> "gear_socket"
        id=="pour_control" -> "casting_bed"
        id=="crane_load" -> "casting_rack"
        id=="assembly_socket" -> "assembly_bench"
        id=="decor_conveyor_raw" -> "factory_conveyor"
        id=="decor_roller_table" -> "roller_table"
        id.startsWith("counterweight") -> "winch"
        id.contains("supply") || id.startsWith("survey") -> "rack"
        id.contains("valve") -> "valve"
        id.startsWith("control_") -> "machine_console"
        else -> "console"
    }
    fun fixtures(scene:MineExpeditionScene):List<Fixture> = fixtures(scene.plan)
    fun fixtures(plan:ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlan):List<Fixture> {
        val modernFactory = plan.kind==MineExpeditionKind.DEAD_FACTORY && plan.stations.containsKey("crusher_feed")
        val hiddenStations = if(modernFactory) setOf("water_valve_0","water_valve_2") else emptySet()
        val stations=plan.stations.filterKeys { id ->
            id !in setOf("entry","exit") && !id.startsWith("lift_") && !id.startsWith("ark_") &&
                !id.startsWith("jam_") && !id.startsWith("branch_") && id !in hiddenStations
        }.map { (id,p) -> Fixture(id,model(id,plan.kind),p) }
        val decor=when(plan.kind) {
            MineExpeditionKind.DEAD_FACTORY -> if(modernFactory) listOf(
                Fixture("decor_waterwheel","waterwheel",MineFactoryLine.machines.getValue("decor_waterwheel")),
                Fixture("decor_water_header","factory_water_header",ExpeditionPoint(0,5,-18)),
                Fixture("decor_crusher_left","factory_crusher",MineFactoryLine.machines.getValue("decor_crusher_left"),1f,0),
                Fixture("decor_conveyor_raw","factory_conveyor",MineFactoryLine.machines.getValue("decor_conveyor_raw")),
                Fixture("decor_furnace_left","furnace",MineFactoryLine.machines.getValue("decor_furnace_left"),1f,90),
                Fixture("decor_roller_table","roller_table",MineFactoryLine.machines.getValue("decor_roller_table")),
                Fixture("decor_pump_left","pump",MineFactoryLine.machines.getValue("decor_pump_left")),
                Fixture("decor_tank_left","tank",MineFactoryLine.machines.getValue("decor_tank_left")),
            ) else listOf(
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
        val controls = if(plan.kind==MineExpeditionKind.DEAD_FACTORY && !modernFactory) {
            ru.ruscrafting.farms.domain.mine.expedition.MineFactoryProgram.controls.map { (id,at) ->
                Fixture(id,"machine_console",at)
            } + Fixture("pour_console","machine_console",plan.stations.getValue("pour_control").offset(-4,0,1))
        } else emptyList()
        return stations+decor+controls
    }
    fun targets(scene:MineExpeditionScene,editor:MineFurnishingEditor?,active:Set<String>):List<MineExpeditionMarkers.Target> =
        fixtures(scene).filter { it.id !in active && (!it.id.startsWith("repair_supply_") || editor?.locked(scene)==true) }.map { f ->
            val yaw=editor?.yaw(scene,f.id) ?: f.yaw
            val p=editor?.position(scene,f.id,f.at) ?: f.at
            MineExpeditionMarkers.Target(f.id,scene.at(p),Material.CUT_COPPER,Component.empty(),model=f.model,modelScale=f.scale,
                glowing=editor?.selected(scene.journalSequence,f.id)==true,yaw=yaw,editKey="${scene.journalSequence}/${f.id}",editBase=f.at,
                interactive=editor?.locked(scene)==true)
        }
}
