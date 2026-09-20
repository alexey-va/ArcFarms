package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.*

/** Reusable full-size assemblies. Every part shares its assembly's editable anchor. */
internal object MineDisplayBlueprints {
    val kinds = setOf("pipe_valve", "sluice", "coal_bunker", "feed_hopper", "casting_bed", "casting_rack",
        "assembly_bench", "crane_console", "furnace_console", "furnace", "waterwheel", "pump", "crusher",
        "tank", "winch", "rack", "console", "valve", "finished_gear")
    data class Part(val material: Material, val center: Vector3f, val size: Vector3f,
        val angle: Float = 0f, val moving: Boolean = false, val pivot: Vector3f = Vector3f(), val motion: String = "rotate")
    fun model(kind: String): List<Part> = buildList {
        require(kind in kinds) { "Unknown display model: $kind" }
        fun box(m: Material, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float,
            angle: Float = 0f, moving: Boolean = false, pivot: Vector3f = Vector3f(), motion:String="rotate") {
            add(Part(m, Vector3f(x,y,z), Vector3f(w,h,d), angle,moving,pivot,motion))
        }
        fun wheel(cx:Float,cy:Float,cz:Float,r:Float, moving:Boolean=true) {
            val pivot=Vector3f(cx,cy,cz)
            val rimWidth=min(.18f,r*.16f)
            // Rectangular rim segments stop before the neighbouring segment's inner corner.
            val rimLength=2*(r-rimWidth/2)*tan(PI.toFloat()/16)-.008f
            repeat(16) { i ->
                val a=i*PI.toFloat()/8
                box(Material.EXPOSED_CUT_COPPER,cx+cos(a)*r,cy+sin(a)*r,cz,rimWidth,rimLength,.24f,a,moving,pivot)
                if(i%2==0) box(Material.IRON_BLOCK,cx+cos(a)*(r+rimWidth*.3f),cy+sin(a)*(r+rimWidth*.3f),cz,
                    min(.25f,r*.22f),min(.22f,r*.22f),.36f,a,moving,pivot)
            }
            val spokes=if(r>=3) 8 else 4
            val inner=if(r>=3) .6f else min(.2f,r*.35f)
            val outer=r-rimWidth/2
            repeat(spokes) { i -> val a=i*PI.toFloat()*2/spokes
                box(if(r>=3) Material.STRIPPED_DARK_OAK_LOG else Material.POLISHED_ANDESITE,
                    cx+cos(a)*(outer+inner)/2,cy+sin(a)*(outer+inner)/2,cz,
                    outer-inner,if(r>=3) .32f else .12f,if(r>=3) .32f else .16f,a,moving,pivot) }
            box(Material.IRON_BLOCK,cx,cy,cz,inner*2,inner*2,.55f)
        }
        fun frame(width:Float,depth:Float,height:Float) {
            box(Material.POLISHED_DEEPSLATE,0f,.15f,0f,width+.4f,.3f,depth+.4f)
            for(x in listOf(-width/2,width/2)) for(z in listOf(-depth/2,depth/2)) {
                box(Material.POLISHED_ANDESITE,x,height/2+.1f,z,.22f,height-.2f,.22f)
                box(Material.CUT_COPPER,x,.5f,z,.34f,.18f,.34f)
            }
        }
        when(kind) {
            "pipe_valve" -> {
                frame(4.2f,2.4f,4.2f)
                // A supported pipe run with bolted flanges; the handwheel belongs to the pipe.
                box(Material.WEATHERED_CUT_COPPER,0f,2.6f,-.5f,4.8f,1.05f,1.05f)
                for(x in listOf(-2f,0f,2f)) {
                    box(Material.POLISHED_ANDESITE,x,2.6f,-.5f,.22f,1.4f,1.4f)
                    for(y in listOf(2.05f,3.15f)) box(Material.IRON_BLOCK,x,y,.26f,.14f,.14f,.2f)
                }
                box(Material.EXPOSED_COPPER,0f,2.6f,.5f,.45f,.45f,1.6f)
                wheel(0f,2.6f,1.35f,1.35f)
                box(Material.WEATHERED_CUT_COPPER,2f,3.6f,-.5f,.65f,2f,.65f)
                box(Material.CYAN_STAINED_GLASS,2f,3.7f,-.13f,.25f,1.3f,.1f)
                box(Material.POLISHED_BLACKSTONE,2f,4.5f,-.5f,1f,.18f,1f)
            }
            "sluice" -> {
                frame(4.4f,2.2f,5.3f)
                for(x in listOf(-1.8f,1.8f)) box(Material.POLISHED_ANDESITE,x,2.8f,-.6f,.5f,5.4f,.7f)
                box(Material.WEATHERED_CUT_COPPER,0f,2.7f,-.6f,3.2f,4.4f,.35f)
                for(y in listOf(1f,2f,3f,4f)) box(Material.IRON_BLOCK,0f,y,-.3f,3.35f,.16f,.18f)
                box(Material.CUT_COPPER,0f,5.3f,-.6f,4.4f,.45f,1f)
                box(Material.IRON_BLOCK,0f,4.1f,.15f,.24f,2.8f,.24f)
                wheel(0f,2.5f,.75f,1.1f)
                for(x in listOf(-1.6f,1.6f)) box(Material.PRISMARINE_BRICKS,x,.47f,-2.1f,.6f,.7f,3.2f)
                box(Material.BLUE_STAINED_GLASS,0f,.2f,-2.1f,2.6f,.16f,3.2f)
            }
            "coal_bunker", "feed_hopper" -> {
                frame(4.2f,3f,3.4f)
                box(Material.POLISHED_BLACKSTONE,0f,.8f,0f,2f,1f,1.8f)
                for(x in listOf(-1.8f,1.8f)) box(Material.WEATHERED_CUT_COPPER,x,2.6f,0f,.3f,2.4f,3.4f)
                for(z in listOf(-1.5f,1.5f)) box(Material.WEATHERED_CUT_COPPER,0f,2.6f,z,3.3f,2.4f,.3f)
                box(Material.POLISHED_ANDESITE,0f,1.5f,0f,3.7f,.35f,3f)
                if(kind=="coal_bunker") {
                    for(x in -1..1) for(z in -1..1) box(Material.COAL_BLOCK,x*1.05f,2.55f+(x+z).mod(2)*.25f,z*.8f,1f,1.15f,.8f)
                } else {
                    for(z in -1..1) box(Material.IRON_BLOCK,0f,3.75f,z*.9f,3.8f,.14f,.13f)
                    box(Material.POLISHED_BLACKSTONE,0f,.5f,-2.5f,2.2f,.25f,2.4f)
                }
            }
            "casting_bed" -> {
                frame(5f,3.4f,1.5f)
                box(Material.POLISHED_BLACKSTONE,0f,1.45f,0f,4.5f,.4f,3.3f)
                for(x in listOf(-2f,2f)) box(Material.IRON_BLOCK,x,1.85f,0f,.3f,.8f,3.2f)
                for(z in listOf(-1.4f,1.4f)) box(Material.IRON_BLOCK,0f,1.85f,z,3.7f,.8f,.3f)
                box(Material.ORANGE_STAINED_GLASS,0f,1.69f,0f,3.4f,.1f,2.2f)
                for(x in listOf(-1.9f,1.9f)) box(Material.CUT_COPPER,x,2.8f,-1.3f,.25f,3.3f,.3f)
                box(Material.POLISHED_ANDESITE,0f,4.3f,-1.3f,4.4f,.35f,.6f)
                box(Material.IRON_BLOCK,0f,3.7f,-1.3f,.17f,1.2f,.17f)
                box(Material.POLISHED_BLACKSTONE,0f,3.1f,-1.3f,1.6f,1.2f,1.3f)
                wheel(-2.7f,1.8f,.2f,.8f)
            }
            "casting_rack" -> {
                frame(3.2f, 2.6f, 1.2f)
                box(Material.POLISHED_ANDESITE, 0f, 1.3f, 0f, 3.2f, .35f, 2.6f)
                for (x in listOf(-1.1f, 1.1f))
                    box(Material.IRON_BLOCK, x, 1.6f, 0f, .25f, .25f, 2.1f)
                for (x in listOf(-1.45f, 1.45f))
                    box(Material.YELLOW_TERRACOTTA, x, .88f, 1.2f, .26f, 1.6f, .26f)
            }
            "assembly_bench" -> {
                frame(4.2f,3.6f,4.8f)
                box(Material.POLISHED_ANDESITE,0f,1.3f,0f,4.4f,.4f,3.6f)
                for(x in listOf(-1.4f,1.4f)) box(Material.IRON_BLOCK,x,1.7f,0f,.35f,.4f,2.5f)
                box(Material.POLISHED_BLACKSTONE,0f,1.65f,0f,1.8f,.3f,1.7f)
                box(Material.WEATHERED_CUT_COPPER,0f,4.7f,0f,4.4f,.7f,2f)
                box(Material.IRON_BLOCK,0f,3.7f,0f,.7f,1.5f,.7f,moving=true,motion="press")
                box(Material.POLISHED_ANDESITE,0f,2.95f,0f,2f,.3f,1.8f,moving=true,motion="press")
                for(x in listOf(-1.8f,1.8f)) for(y in listOf(2.7f,3.8f)) box(Material.CUT_COPPER,x,y,.8f,.36f,.22f,.45f)
            }
            "crane_console", "furnace_console" -> {
                frame(3.8f,2.2f,.95f)
                box(Material.WEATHERED_CUT_COPPER,0f,1.05f,0f,3.8f,.55f,2.2f)
                box(Material.POLISHED_BLACKSTONE,0f,1.42f,-.65f,3.5f,.66f,.25f)
                for(x in listOf(-1.2f,0f,1.2f)) {
                    box(Material.CYAN_STAINED_GLASS,x,1.52f,-.42f,.78f,.36f,.09f)
                    box(Material.SEA_LANTERN,x,1.52f,-.495f,.65f,.28f,.05f)
                }
                for(x in listOf(-1.15f,1.15f)) {
                    box(Material.POLISHED_BLACKSTONE,x,1.37f,.5f,.65f,.12f,.65f)
                    box(Material.IRON_BLOCK,x,1.6f,.5f,.13f,.45f,.13f,x*.2f,moving=true,pivot=Vector3f(x,1.37f,.5f),motion="lever")
                    box(if(kind=="crane_console") Material.RED_CONCRETE else Material.ORANGE_CONCRETE,
                        x,1.83f,.5f,.42f,.16f,.3f,moving=true,pivot=Vector3f(x,1.37f,.5f),motion="lever")
                }
                box(Material.POLISHED_BASALT,-1.7f,2.8f,-.8f,.2f,2.4f,.2f)
                for(y in 0..2) box(listOf(Material.LIME_CONCRETE,Material.YELLOW_CONCRETE,Material.RED_CONCRETE)[y],
                    -1.7f,3.4f+y*.35f,-.8f,.35f,.3f,.35f)
            }
            "furnace" -> {
                frame(6.2f,5.8f,8.5f)
                box(Material.POLISHED_BLACKSTONE,0f,.55f,0f,7f,.9f,6.8f)
                box(Material.DEEPSLATE_BRICKS,0f,4.3f,-1.3f,5.6f,6.8f,3.4f)
                for(x in listOf(-2.5f,2.5f)) box(Material.DEEPSLATE_BRICKS,x,3.2f,1.3f,1.1f,4.8f,2.3f)
                box(Material.POLISHED_BLACKSTONE,0f,5.7f,1.7f,5.8f,1f,2.2f)
                box(Material.ORANGE_STAINED_GLASS,0f,2.8f,2f,3.8f,2.4f,.18f)
                for(x in -3..3) box(Material.IRON_BLOCK,x*.55f,2.7f,2.18f,.12f,2.7f,.18f)
                for(y in listOf(1f,4.8f,7.5f)) {
                    box(Material.EXPOSED_CUT_COPPER,0f,y,-.15f,6.2f,.32f,5.6f)
                }
                box(Material.WEATHERED_CUT_COPPER,0f,9.3f,-1f,2.6f,3.8f,2.6f)
                box(Material.POLISHED_ANDESITE,0f,11.3f,-1f,3.2f,.5f,3.2f)
                box(Material.POLISHED_BLACKSTONE,0f,.9f,4.3f,2.6f,.45f,3.8f)
                box(Material.ORANGE_STAINED_GLASS,0f,1.15f,4.3f,1.4f,.1f,3.6f)
                wheel(-3.2f,2.4f,2f,1f)
            }
            "waterwheel" -> {
                box(Material.POLISHED_DEEPSLATE,0f,.2f,0f,3f,.4f,6f)
                for(z in listOf(-2f,2f)) {
                    box(Material.STONE_BRICKS,0f,3.1f,z,1.4f,5.8f,1.4f)
                    box(Material.CUT_COPPER,0f,5.9f,z,1.7f,.5f,1.7f)
                }
                box(Material.STRIPPED_DARK_OAK_LOG,0f,6f,0f,.5f,.5f,6.5f)
                wheel(0f,6f,-.72f,5f)
                wheel(0f,6f,.72f,5f)
                val pivot=Vector3f(0f,6f,0f)
                repeat(16) { i -> val a=i*PI.toFloat()/8
                    box(Material.DARK_OAK_PLANKS,cos(a)*4.6f,6f+sin(a)*4.6f,0f,.22f,.85f,1.8f,a,true,pivot)
                }
            }
            "pump" -> {
                frame(2.5f,2f,2.5f)
                box(Material.WEATHERED_CUT_COPPER,0f,1.3f,0f,1.4f,1.6f,1.2f)
                box(Material.POLISHED_BASALT,0f,2.8f,0f,.42f,1.5f,.42f)
                box(Material.CUT_COPPER,.7f,3.4f,0f,1.8f,.4f,.4f)
                box(Material.IRON_BLOCK,1.5f,2.2f,0f,.3f,2.6f,.3f)
                wheel(-.1f,1.5f,1.05f,.95f)
                box(Material.SEA_LANTERN,.6f,2.2f,.64f,.25f,.3f,.1f)
            }
            "crusher" -> {
                frame(3.5f,2.5f,3.6f)
                for(x in listOf(-.75f,.75f)) {
                    box(Material.SMOOTH_STONE,x,2.1f,0f,1.25f,1.25f,2f)
                    wheel(x,2.1f,1.22f,.55f)
                    box(Material.IRON_BLOCK,x,2.1f,-1.35f,.28f,.28f,.6f)
                }
                for(x in listOf(-1.5f,1.5f)) box(Material.CUT_COPPER,x,3.2f,0f,.3f,1.2f,2.3f)
                box(Material.DEEPSLATE_TILES,0f,.65f,2f,2.6f,.3f,3.2f)
                repeat(7) { i -> box(Material.IRON_BLOCK,0f,.9f,1f+i*.4f,2.4f,.1f,.14f) }
            }
            "tank" -> {
                frame(2.3f,2.3f,1.2f)
                box(Material.WAXED_COPPER_BLOCK,0f,2.3f,0f,1.7f,3.3f,1.7f)
                for(y in listOf(.8f,2f,3.6f)) {
                    box(Material.POLISHED_ANDESITE,0f,y,0f,1.95f,.2f,1.95f)
                }
                box(Material.CYAN_STAINED_GLASS,0f,2.4f,.96f,.35f,2f,.08f)
                box(Material.SEA_LANTERN,0f,2.4f,.89f,.2f,1.8f,.05f)
                box(Material.EXPOSED_COPPER,1.3f,1.3f,0f,1.4f,.25f,.25f)
                wheel(1.9f,1.3f,.3f,.4f)
            }
            "winch" -> {
                frame(2.7f,1.8f,2f)
                box(Material.IRON_BLOCK,0f,1.35f,0f,2.9f,.22f,.22f)
                box(Material.STRIPPED_DARK_OAK_LOG,0f,1.35f,0f,1.6f,1.1f,1.1f)
                for(x in listOf(-.7f,-.35f,0f,.35f,.7f)) box(Material.POLISHED_BASALT,x,1.35f,0f,.13f,1.18f,1.18f)
                wheel(0f,1.35f,1.15f,1f)
                box(Material.IRON_BLOCK,0f,2.6f,-.65f,.1f,2.5f,.1f)
            }
            "rack" -> {
                frame(2.5f,1.5f,2.6f)
                for(y in listOf(.4f,1.4f,2.4f)) {
                    box(Material.SPRUCE_PLANKS,0f,y,0f,2.6f,.18f,1.6f)
                    for(x in listOf(-.8f,0f,.8f)) box(Material.RAW_IRON_BLOCK,x,y+.35f,0f,.6f,.5f,1f)
                }
            }
            "console" -> {
                frame(1.5f,1f,1.1f)
                box(Material.WEATHERED_CUT_COPPER,0f,1.25f,0f,1.8f,.65f,1.15f)
                for(x in listOf(-.55f,0f,.55f)) {
                    box(Material.POLISHED_BLACKSTONE,x,1.45f,.6f,.4f,.35f,.08f)
                    box(Material.SEA_LANTERN,x,1.43f,.65f,.18f,.2f,.04f)
                }
                box(Material.IRON_BLOCK,0f,1.85f,.45f,.12f,.8f,.12f,.25f)
                box(Material.RED_CONCRETE,-.1f,2.2f,.45f,.45f,.18f,.25f)
            }
            "finished_gear" -> wheel(0f,1f,0f,.85f,moving=false)
            else -> {
                frame(1.3f,1f,1.1f)
                box(Material.EXPOSED_COPPER,0f,1.2f,0f,.4f,1.6f,.4f)
                box(Material.WEATHERED_CUT_COPPER,0f,.6f,0f,2.1f,.35f,.35f)
                wheel(0f,1.35f,.5f,.65f)
            }
        }
    }
    private fun angle(part:Part,phase:Float):Float = when {
        !part.moving || part.motion=="press" -> 0f
        part.motion=="lever" -> sin(phase/2)*.5f
        else -> phase
    }
    fun rotation(part: Part, phase: Float): Quaternionf = Quaternionf().rotateZ(part.angle+angle(part,phase))
    fun center(part:Part,phase:Float):Vector3f = when {
        !part.moving -> Vector3f(part.center)
        part.motion=="press" -> Vector3f(part.center).add(0f,-(1-cos(phase))*.5f,0f)
        else -> Quaternionf().rotateZ(angle(part,phase)).transform(Vector3f(part.center).sub(part.pivot)).add(part.pivot)
    }
}
