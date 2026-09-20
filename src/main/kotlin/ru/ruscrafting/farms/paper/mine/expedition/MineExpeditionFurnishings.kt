package ru.ruscrafting.farms.paper.mine.expedition

import net.kyori.adventure.text.Component
import org.bukkit.Material
import ru.ruscrafting.farms.domain.mine.expedition.ExpeditionPoint
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind

/** Permanent editable assemblies; gameplay adds glow only to the currently required controls. */
internal object MineExpeditionFurnishings {
    data class Fixture(val id:String,val model:String,val at:ExpeditionPoint,val scale:Float=1f)
    fun parent(kind:MineExpeditionKind,id:String):String? = if(kind!=MineExpeditionKind.DEAD_FACTORY) null else when(id) {
        "furnace_input","furnace_control" -> "decor_furnace_left"
        "pour_control" -> "decor_furnace_right"
        "water_valve_1" -> "decor_waterwheel"
        else -> null
    }
    fun model(id:String,kind:MineExpeditionKind?=null):String = when {
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
                Fixture("decor_crusher_left","crusher",ExpeditionPoint(-17,5,11),2.2f),
                Fixture("decor_crusher_right","crusher",ExpeditionPoint(17,5,11),2.2f),
                Fixture("decor_furnace_left","furnace",ExpeditionPoint(-17,5,-13)),
                Fixture("decor_furnace_right","furnace",ExpeditionPoint(17,5,-13)),
                Fixture("decor_pump_left","pump",ExpeditionPoint(-27,5,-17),1.8f),
                Fixture("decor_pump_right","pump",ExpeditionPoint(27,5,-17),1.8f),
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
        return stations+decor
    }
    fun targets(scene:MineExpeditionScene,editor:MineFurnishingEditor?,active:Set<String>):List<MineExpeditionMarkers.Target> =
        fixtures(scene).filter { it.id !in active }.map { f ->
            val yaw=editor?.yaw(scene,f.id) ?: 0
            val p=editor?.position(scene,f.id,f.at) ?: f.at
            MineExpeditionMarkers.Target(f.id,scene.at(p),Material.CUT_COPPER,Component.empty(),model=f.model,modelScale=f.scale,
                glowing=editor?.selected(scene.journalSequence,f.id)==true || active.any { parent(scene.kind,it)==f.id },yaw=yaw,editKey="${scene.journalSequence}/${f.id}",editBase=f.at)
        }
}
