package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Material
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Recognisable production assemblies, in block units: an open crusher, transport and service fittings. */
internal object MineFactoryModels {
    val kinds = setOf("factory_crusher", "factory_conveyor", "roller_table", "mounted_console", "furnace_air_console",
        "charge_bunker", "inlet_hopper", "charge_hopper", "loose_gear", "gear_socket", "factory_water_header")
    val moltenPath = listOf(Vector3f(-8f,1.25f,0f), Vector3f(-8f,3.05f,0f),
        Vector3f(-3.3f,3.05f,0f), Vector3f(-3.3f,3.05f,-2.15f),
        Vector3f(1.05f,3.05f,-2.15f), Vector3f(1.05f,3.05f,-.3f), Vector3f(1.05f,2.35f,-.3f))

    fun moltenConduit(): List<MineDisplayBlueprints.Part> = buildList {
        for((a,b) in moltenPath.zipWithNext()) {
            val delta=Vector3f(b).sub(a)
            val size=Vector3f(.32f)
            if(delta.x!=0f) size.x=kotlin.math.abs(delta.x)-.4f
            if(delta.y!=0f) size.y=kotlin.math.abs(delta.y)-.4f
            if(delta.z!=0f) size.z=kotlin.math.abs(delta.z)-.4f
            add(MineDisplayBlueprints.Part(Material.ORANGE_STAINED_GLASS,Vector3f(a).add(b).mul(.5f),size))
        }
        moltenPath.forEach { add(MineDisplayBlueprints.Part(Material.EXPOSED_CUT_COPPER,Vector3f(it),Vector3f(.45f))) }
    }

    fun model(kind: String): List<MineDisplayBlueprints.Part> = buildList {
        fun box(m: Material, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float,
            angle: Float = 0f, motion: String = "fixed", pivot: Vector3f = Vector3f(), hidden: Boolean = false) {
            add(MineDisplayBlueprints.Part(m, Vector3f(x,y,z), Vector3f(w,h,d), angle,
                motion != "fixed" && motion != "signal" && motion != "thermometer", pivot, motion, hidden))
        }
        fun gear(x: Float, y: Float, z: Float, radius: Float, motion: String = "fixed", hidden: Boolean = false) {
            val pivot = Vector3f(x,y,z)
            repeat(12) { i ->
                val a = (i*PI/6).toFloat()
                box(Material.EXPOSED_CUT_COPPER,x+cos(a)*radius,y+sin(a)*radius,z,
                    .18f,.25f,.22f,a,motion,pivot,hidden)
            }
            for (i in 0..3) {
                val a = (i*PI/2).toFloat()
                box(Material.POLISHED_ANDESITE,x+cos(a)*radius*.5f,y+sin(a)*radius*.5f,z,
                    radius*.67f,.13f,.16f,a,motion,pivot,hidden)
            }
            box(Material.IRON_BLOCK,x,y,z,.26f,.26f,.28f,motion=motion,pivot=pivot,hidden=hidden)
        }
        fun legs(length: Float, width: Float, height: Float) {
            for(x in listOf(-length/2+.3f,length/2-.3f)) for(z in listOf(-width/2,width/2)) {
                box(Material.POLISHED_BASALT,x,height/2,z,.24f,height,.24f)
                // Leave a small reveal between the upright's floor plane and its foot plate.
                box(Material.CUT_COPPER,x,.11f,z,.45f,.18f,.45f)
            }
        }
        when(kind) {
            "factory_water_header" -> {
                // Visible intake, tank feed and pump discharge join the utility train.
                val runs=listOf(
                    listOf(Vector3f(0f,.55f,-5f),Vector3f(0f,2.2f,-5f),Vector3f(0f,2.2f,0f),Vector3f(-15.55f,2.2f,0f)),
                    listOf(Vector3f(-18.3f,1.3f,0f),Vector3f(-23.55f,1.3f,0f)),
                    listOf(Vector3f(-25f,1.3f,1.2f),Vector3f(-25f,1.3f,3f)))
                for(path in runs) {
                    for((a,b) in path.zipWithNext()) {
                        val d=Vector3f(b).sub(a);val size=Vector3f(.24f)
                        if(d.x!=0f) size.x=kotlin.math.abs(d.x)-.32f
                        if(d.y!=0f) size.y=kotlin.math.abs(d.y)-.32f
                        if(d.z!=0f) size.z=kotlin.math.abs(d.z)-.32f
                        val mid=Vector3f(a).add(b).mul(.5f)
                        box(Material.CYAN_STAINED_GLASS,mid.x,mid.y,mid.z,size.x,size.y,size.z)
                    }
                    path.forEach { box(Material.EXPOSED_CUT_COPPER,it.x,it.y,it.z,.34f,.34f,.34f) }
                }
                for(x in listOf(-4f,-10f)) box(Material.POLISHED_BASALT,x,1.02f,0f,.16f,1.88f,.16f)
            }
            "factory_crusher" -> {
                box(Material.POLISHED_DEEPSLATE,0f,.2f,0f,8f,.4f,6f)
                for(x in listOf(-3.55f,3.55f)) for(z in listOf(-2.5f,2.5f)) {
                    box(Material.POLISHED_BASALT,x,3.6f,z,.42f,6.8f,.42f)
                    for(y in listOf(.7f,3.6f,6.7f)) box(Material.CUT_COPPER,x,y,z,.6f,.25f,.6f)
                }
                // Open throat exposes two counter-rotating toothed rolls, not a solid grey box.
                for((index,x) in listOf(-1.2f,1.2f).withIndex()) {
                    val pivot=Vector3f(x,3.15f,0f)
                    val motion=if(index==0) "rotate" else "counter_rotate"
                    repeat(12) { i -> val a=(i*PI/6).toFloat()
                        // Stagger the axial ends so neighbouring moving segments never share a face.
                        val segmentZ=(i-5.5f)*.018f
                        box(if(i%2==0) Material.POLISHED_ANDESITE else Material.POLISHED_BLACKSTONE,
                            x+cos(a)*.93f,3.15f+sin(a)*.93f,segmentZ,.36f,.45f,3.55f,a,motion,pivot)
                        for(z in listOf(-1.35f,0f,1.35f))
                            box(Material.IRON_BLOCK,x+cos(a)*1.0f,3.15f+sin(a)*1.0f,z+segmentZ,.19f,.21f,.44f,a,motion,pivot)
                    }
                    box(Material.IRON_BLOCK,x,3.15f,0f,.34f,.34f,5.4f)
                    gear(x,3.15f,2.8f,.68f,motion)
                }
                for(z in listOf(-2.2f,2.2f)) {
                    box(Material.WEATHERED_CUT_COPPER,0f,4.9f,z,6.6f,1.5f,.25f)
                    box(Material.EXPOSED_CUT_COPPER,0f,5.75f,z,6.8f,.22f,.4f)
                }
                for(x in listOf(-3.2f,3.2f)) box(Material.WEATHERED_CUT_COPPER,x,4.9f,0f,.25f,1.5f,4.1f)
                box(Material.POLISHED_ANDESITE,0f,6.8f,-2.5f,7.1f,.35f,.45f)
                box(Material.POLISHED_BLACKSTONE,2.8f,1.28f,0f,3.6f,.25f,2.4f)
                for(z in listOf(-1.2f,1.2f)) box(Material.CUT_COPPER,2.8f,1.55f,z,3.4f,.34f,.15f)
                // Material falls from the hopper into the rolls only during the processing cycle.
                repeat(5) { i -> box(if(i%2==0) Material.RAW_IRON_BLOCK else Material.COAL_BLOCK,
                    (i%3-1)*.65f,5.35f,(i%2-.5f)*.75f,.48f,.42f,.5f,
                    i*.19f,"feed",hidden=true) }
                for(x in listOf(-2.8f,2.8f)) box(Material.YELLOW_TERRACOTTA,x,1.8f,2.6f,.6f,.8f,.17f)
            }
            "factory_conveyor" -> {
                legs(8f,2.25f,1.35f)
                for(z in listOf(-1.25f,1.25f)) {
                    box(Material.POLISHED_BASALT,0f,1.24f,z,8.2f,.6f,.22f)
                    box(Material.CUT_COPPER,0f,1.64f,z,8.2f,.17f,.2f)
                }
                // Keep the belt's lower return run above the central undercarriage.
                box(Material.POLISHED_BLACKSTONE,0f,.72f,0f,7.5f,.3f,2.2f)
                repeat(40) { i -> box(Material.DEEPSLATE_TILES,0f,0f,0f,.24f,.08f,2.15f,
                    (i*PI*2/40).toFloat(),"belt",Vector3f(0f,1.24f,0f)) }
                repeat(5) { i -> box(if(i%2==0) Material.RAW_IRON_BLOCK else Material.COAL_BLOCK,
                    0f,.62f,(i%2-.5f)*.7f,.49f,.38f,.52f,(i*PI*2/5).toFloat(),
                    "cargo",Vector3f(0f,1.24f,0f),true) }
            }
            "roller_table" -> {
                // Set the four legs inside the end rollers while retaining the ten-block table bed.
                legs(9.4f,2.5f,1.35f)
                for(z in listOf(-1.45f,1.45f)) box(Material.POLISHED_BASALT,0f,1.2f,z,10f,.45f,.28f)
                repeat(16) { i ->
                    val x=-4.65f+i*.62f
                    box(Material.IRON_BLOCK,x,1.4f,0f,.34f,.34f,2.7f,motion="rotate",pivot=Vector3f(x,1.4f,0f))
                    box(Material.CUT_COPPER,x,1.4f,1.5f,.18f,.18f,.2f)
                }
                for(x in listOf(-4f,4f)) box(Material.YELLOW_TERRACOTTA,x,2.1f,-1.4f,.25f,1.4f,.25f)
            }
            "mounted_console" -> {
                box(Material.POLISHED_BASALT,0f,1.15f,-.42f,.35f,.35f,.95f)
                box(Material.WEATHERED_CUT_COPPER,0f,1.3f,0f,1.3f,.95f,.4f)
                box(Material.POLISHED_BLACKSTONE,0f,1.35f,.225f,1.1f,.7f,.035f)
                box(Material.CYAN_STAINED_GLASS,.3f,1.5f,.26f,.35f,.25f,.035f)
                box(Material.LIME_CONCRETE,.3f,1.12f,.265f,.2f,.15f,.035f,motion="signal")
                box(Material.IRON_BLOCK,-.3f,1.34f,.4f,.12f,.5f,.12f,motion="lever",pivot=Vector3f(-.3f,1.1f,.4f))
                box(Material.RED_CONCRETE,-.3f,1.62f,.4f,.35f,.18f,.23f,motion="lever",pivot=Vector3f(-.3f,1.1f,.4f))
            }
            "furnace_air_console" -> {
                // Air control is deliberately a small, readable service panel: the
                // lever is the only clickable moving part and the thermometer is
                // a stack of passive lights driven by MineExpeditionMarkers.
                box(Material.POLISHED_BASALT,0f,1.15f,-.42f,.35f,.35f,.95f)
                box(Material.WEATHERED_CUT_COPPER,0f,1.3f,0f,1.5f,1.05f,.42f)
                box(Material.POLISHED_BLACKSTONE,0f,1.35f,.225f,1.25f,.75f,.035f)
                box(Material.IRON_BLOCK,-.34f,1.34f,.4f,.12f,.5f,.12f,motion="lever",pivot=Vector3f(-.34f,1.1f,.4f))
                box(Material.ORANGE_CONCRETE,-.34f,1.62f,.4f,.35f,.18f,.23f,motion="lever",pivot=Vector3f(-.34f,1.1f,.4f))
                for (index in 0..9) {
                    // The colours mark the fixed operating band (60..78)
                    // rather than pretending that every visible LED is safe.
                    // Above the band stays red even when the gauge is full.
                    val segmentTemperature = 18f + (index + .5f) * 8.2f
                    val material = when {
                        segmentTemperature in 60f..78f -> Material.LIME_CONCRETE
                        segmentTemperature > 78f -> Material.RED_CONCRETE
                        else -> Material.YELLOW_CONCRETE
                    }
                    box(material,.34f,1.0f + index*.08f,.265f,.16f,.06f,.045f,
                        motion="thermometer",hidden=true)
                }
            }
            "charge_bunker" -> {
                legs(4.5f,3.5f,1.2f)
                box(Material.POLISHED_DEEPSLATE,0f,.9f,0f,4.8f,.4f,3.8f)
                // Inset the walls from the base perimeter so their z faces do not stack on it.
                for(x in listOf(-2.2f,2.2f)) box(Material.WEATHERED_CUT_COPPER,x,2f,0f,.25f,2.2f,3.45f)
                box(Material.WEATHERED_CUT_COPPER,0f,2f,-1.62f,4.1f,2.2f,.25f)
                for(x in -1..1) for(z in -1..1) box(if((x+z)%2==0) Material.RAW_IRON_BLOCK else Material.COAL_BLOCK,
                    x*1.25f,1.45f+(x+z).mod(2)*.2f,z*1.05f,1.12f,.7f,.91f)
                box(Material.YELLOW_TERRACOTTA,0f,1.15f,1.9f,4.5f,.25f,.15f)
            }
            "inlet_hopper", "charge_hopper" -> {
                legs(2.2f,1.8f,1.4f)
                box(Material.POLISHED_BLACKSTONE,0f,1.1f,0f,1.6f,.3f,1.4f)
                for(x in listOf(-1f,1f)) box(Material.WEATHERED_CUT_COPPER,x,1.65f,0f,.19f,1f,2f)
                for(z in listOf(-.9f,.9f)) box(Material.EXPOSED_CUT_COPPER,0f,1.65f,z,1.8f,1f,.19f)
                box(Material.CUT_COPPER,0f,1.1f,-1.3f,1f,.25f,1.4f)
                if(kind=="charge_hopper") for(x in listOf(-.4f,.4f)) for(z in listOf(-.4f,.4f))
                    box(Material.RAW_IRON_BLOCK,x,1.55f,z,.63f,.55f,.58f,motion="processed",hidden=true)
            }
            // Keep the pickup on the service side of the column rather than
            // burying the actual gear model in the factory wall.
            "loose_gear" -> gear(.75f,.72f,-.75f,.62f)
            "gear_socket" -> {
                box(Material.POLISHED_BLACKSTONE,0f,1.35f,-.2f,1.75f,1.75f,.23f)
                for(x in listOf(-.75f,.75f)) for(y in listOf(.6f,2.1f))
                    box(Material.IRON_BLOCK,x,y,-.05f,.15f,.15f,.15f)
                box(Material.IRON_BLOCK,0f,1.35f,.14f,.28f,.28f,.55f)
                // The socket's west offset leaves clearance from the adjacent hopper.
                gear(.72f,1.35f,-.38f,.63f,"installed_gear",true)
            }
        }
    }
}
