package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.*

/** Reusable full-size assemblies. Every part shares its assembly's editable anchor. */
internal object MineDisplayBlueprints {
    val kinds = setOf("pipe_valve", "sluice", "coal_bunker", "feed_hopper", "casting_bed", "casting_rack",
        "assembly_bench", "crane_console", "furnace_console", "furnace", "waterwheel", "pump", "crusher",
        "tank", "winch", "rack", "console", "valve", "finished_gear", "drive_rig", "machine_console", "cargo_cart_coal", "cargo_cart_iron", "cargo_cart_charge", "return_miner", "factory_diesel_generator") + ru.ruscrafting.farms.paper.mine.working.MineRailDriveModel.kinds + setOf("rail_drive_rig") + MineFactoryModels.kinds + MineFactoryExperimentModels.kinds + MineFactoryCraneModels.kinds + MineDescentModels.kinds
    data class Part(val material: Material, val center: Vector3f, val size: Vector3f,
        val angle: Float = 0f, val moving: Boolean = false, val pivot: Vector3f = Vector3f(), val motion: String = "rotate",
        val idleHidden: Boolean = false)
    fun model(kind: String): List<Part> = buildList {
        require(kind in kinds) { "Unknown display model: $kind" }
        if (kind == "factory_diesel_generator") { addAll(MineDieselGeneratorModel.model()); return@buildList }
        if(kind == "rail_drive_rig") { addAll(ru.ruscrafting.farms.paper.mine.working.MineRailDriveModel.parts); return@buildList }
        if(kind in ru.ruscrafting.farms.paper.mine.working.MineRailDriveModel.kinds) { addAll(ru.ruscrafting.farms.paper.mine.working.MineRailDriveModel.model(kind)); return@buildList }
        if(kind in MineFactoryCraneModels.kinds) { addAll(MineFactoryCraneModels.model(kind)); return@buildList }
        if(kind in MineFactoryExperimentModels.kinds) { addAll(MineFactoryExperimentModels.model(kind)); return@buildList }
        if(kind in MineFactoryModels.kinds) { addAll(MineFactoryModels.model(kind)); return@buildList }
        if(kind in MineDescentModels.kinds) { addAll(MineDescentModels.model(kind)); return@buildList }
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
            "return_miner" -> {
                box(Material.POLISHED_DEEPSLATE,0f,.16f,0f,1.4f,.32f,1.2f)
                box(Material.CUT_COPPER,0f,.7f,0f,.5f,.7f,.5f)
                box(Material.TERRACOTTA,0f,1.35f,0f,.9f,.9f,.8f)
                box(Material.YELLOW_TERRACOTTA,0f,1.87f,0f,1.08f,.18f,.96f)
                box(Material.YELLOW_TERRACOTTA,0f,1.72f,-.24f,.94f,.15f,.35f)
                for(x in listOf(-.22f,.22f)) box(Material.POLISHED_BLACKSTONE,x,1.42f,.415f,.16f,.13f,.03f)
                box(Material.DARK_OAK_PLANKS,0f,1.12f,.43f,.65f,.25f,.05f)
                box(Material.IRON_BLOCK,0f,1.83f,.52f,.38f,.29f,.13f)
                box(Material.SEA_LANTERN,0f,1.83f,.6f,.24f,.19f,.02f)
            }
            "machine_console" -> {
                box(Material.POLISHED_DEEPSLATE,0f,.12f,0f,1.7f,.24f,1.1f)
                box(Material.POLISHED_ANDESITE,0f,.65f,0f,.55f,.82f,.5f)
                box(Material.WEATHERED_CUT_COPPER,0f,1.16f,0f,1.6f,.34f,1f)
                box(Material.POLISHED_BLACKSTONE,0f,1.4f,-.34f,1.4f,.45f,.18f)
                box(Material.CYAN_STAINED_GLASS,-.3f,1.43f,-.21f,.6f,.25f,.03f)
                box(Material.SEA_LANTERN,-.3f,1.43f,-.25f,.48f,.17f,.02f)
                box(Material.LIME_CONCRETE,.48f,1.39f,-.22f,.2f,.18f,.06f,motion="signal")
                box(Material.POLISHED_BLACKSTONE,0f,1.36f,.24f,.55f,.05f,.45f)
                box(Material.IRON_BLOCK,0f,1.57f,.24f,.1f,.4f,.1f,moving=true,pivot=Vector3f(0f,1.39f,.24f),motion="lever")
                box(Material.RED_CONCRETE,0f,1.78f,.24f,.36f,.16f,.23f,moving=true,pivot=Vector3f(0f,1.39f,.24f),motion="lever")
            }
            "cargo_cart_coal", "cargo_cart_iron", "cargo_cart_charge" -> {
                box(Material.POLISHED_BLACKSTONE,0f,.58f,0f,1.5f,.2f,1.9f)
                box(Material.WEATHERED_CUT_COPPER,0f,.72f,0f,1.35f,.08f,1.65f)
                for(x in listOf(-.78f,.78f)) {
                    box(Material.CUT_COPPER,x,.89f,0f,.12f,.5f,1.8f)
                    box(Material.IRON_BLOCK,x,1.05f,1.12f,.09f,.09f,.55f)
                }
                box(Material.DARK_OAK_PLANKS,0f,1.05f,1.42f,1.75f,.13f,.15f)
                for(z in listOf(-.85f,.85f)) box(Material.CUT_COPPER,0f,.9f,z,1.3f,.3f,.1f)
                for(x in listOf(-.87f,.87f)) for(z in listOf(-.57f,.57f)) {
                    val pivot=Vector3f(x,.32f,z)
                    repeat(8) { i -> val a=i*PI.toFloat()/4
                        box(Material.POLISHED_BLACKSTONE,x,.32f+cos(a)*.24f,z+sin(a)*.24f,.18f,.1f,.145f,a,true,pivot,"axle")
                    }
                    box(Material.IRON_BLOCK,x,.32f,z,.22f,.16f,.16f)
                }
                if(kind in setOf("cargo_cart_coal","cargo_cart_charge")) for(x in listOf(-.33f,.33f)) for(z in listOf(-.45f,.15f,.58f))
                    box(if(kind=="cargo_cart_charge" && z!=.15f) Material.RAW_IRON_BLOCK else Material.COAL_BLOCK,x,.99f,z,.59f,.4f,.38f)
                else for(z in listOf(-.52f,0f,.52f)) {
                    box(Material.IRON_BLOCK,0f,.9f,z,1.15f,.25f,.43f)
                    box(Material.POLISHED_BASALT,0f,1.06f,z,.13f,.05f,.45f)
                }
            }
            "drive_rig" -> addAll(ru.ruscrafting.farms.paper.mine.working.MineDriveModel.parts)
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
                addAll(MineFactoryModels.moltenConduit())
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
                frame(5.8f,4.4f,6.8f)
                box(Material.POLISHED_ANDESITE,0f,1.3f,0f,4.4f,.4f,3.6f)
                for(x in listOf(-1.4f,1.4f)) box(Material.IRON_BLOCK,x,1.7f,0f,.35f,.4f,2.5f)
                box(Material.POLISHED_BLACKSTONE,0f,1.65f,0f,1.8f,.3f,1.7f)
                box(Material.WEATHERED_CUT_COPPER,0f,6.6f,0f,6.2f,.7f,3.1f)
                box(Material.POLISHED_BASALT,0f,5.7f,0f,1.3f,1.2f,1.3f)
                box(Material.IRON_BLOCK,0f,4.5f,0f,.7f,3.1f,.7f,moving=true,motion="press")
                box(Material.POLISHED_ANDESITE,0f,2.95f,0f,2f,.3f,1.8f,moving=true,motion="press")
                for(x in listOf(-1.8f,1.8f)) for(y in listOf(2.7f,3.8f)) box(Material.CUT_COPPER,x,y,.8f,.36f,.22f,.45f)
                // The press is operated from its own front upright.
                box(Material.WEATHERED_CUT_COPPER,2.8f,1.45f,2.25f,1f,.9f,.35f)
                box(Material.IRON_BLOCK,2.8f,1.55f,2.5f,.12f,.4f,.12f,moving=true,pivot=Vector3f(2.8f,1.35f,2.5f),motion="lever")
                box(Material.RED_CONCRETE,2.8f,1.8f,2.5f,.35f,.17f,.24f,moving=true,pivot=Vector3f(2.8f,1.35f,2.5f),motion="lever")
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
                // Glazed inspection door remains readable from the service aisle on a sideways line.
                box(Material.ORANGE_STAINED_GLASS,-2.835f,3.15f,-1.3f,.05f,2f,1.9f)
                for(z in listOf(-2.38f,-.22f)) box(Material.CUT_COPPER,-2.91f,3.15f,z,.2f,2.45f,.17f)
                for(y in listOf(1.83f,4.47f)) box(Material.CUT_COPPER,-2.91f,y,-1.3f,.2f,.17f,2.5f)
                for(z in listOf(-1.85f,-1.3f,-.75f)) box(Material.IRON_BLOCK,-2.925f,3.15f,z,.1f,2f,.1f)
                // Rear charging mouth meets the conveyor after the furnace is yawed into the line.
                box(Material.POLISHED_BLACKSTONE,0f,1.7f,-3.035f,2.5f,1.15f,.045f)
                for(x in listOf(-1.35f,1.35f)) box(Material.EXPOSED_CUT_COPPER,x,1.7f,-3.1f,.18f,1.5f,.24f)
                box(Material.EXPOSED_CUT_COPPER,0f,2.55f,-3.1f,2.9f,.16f,.24f)
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
        !part.moving || part.motion in setOf("press","feed","processed") -> 0f
        part.motion=="counter_rotate" -> -phase
        part.motion=="lever" -> sin(phase/2)*.5f
        else -> phase
    }
    fun rotation(part: Part, phase: Float): Quaternionf = if (part.motion in MineDieselGeneratorMotion.motions) {
        MineDieselGeneratorMotion.rotation(part, phase)
    } else when(part.motion) {
        "axle" -> Quaternionf().rotateX(part.angle+phase)
        "belt" -> Quaternionf().rotateZ(beltPose(part,phase).second)
        "cargo" -> Quaternionf()
        else -> Quaternionf().rotateZ(part.angle+angle(part,phase))
    }
    fun center(part:Part,phase:Float):Vector3f = when {
        !part.moving -> Vector3f(part.center)
        part.motion in MineDieselGeneratorMotion.motions -> MineDieselGeneratorMotion.center(part, phase)
        part.motion=="belt" -> beltPose(part,phase).first.add(part.center)
        part.motion=="cargo" -> topCargoPose(part,phase)
        part.motion=="feed" -> Vector3f(part.center).add(0f,-((phase/(PI.toFloat()*2)+part.angle).mod(1f))*2.8f,0f)
        part.motion=="processed" -> Vector3f(part.center)
        part.motion=="axle" -> Quaternionf().rotateX(phase).transform(Vector3f(part.center).sub(part.pivot)).add(part.pivot)
        part.motion=="press" -> Vector3f(part.center).add(0f,-(1-cos(phase))*.5f,0f)
        else -> Quaternionf().rotateZ(angle(part,phase)).transform(Vector3f(part.center).sub(part.pivot)).add(part.pivot)
    }

    /** Closed belt path: slats roll around the end drums instead of teleporting back across the deck. */
    private fun beltPose(part:Part,phase:Float):Pair<Vector3f,Float> {
        val straight=7.6f;val radius=.28f;val arc=PI.toFloat()*radius
        var distance=((phase+part.angle).mod(PI.toFloat()*2)/(PI.toFloat()*2))*(straight*2+arc*2)
        val center=Vector3f(part.pivot)
        if(distance<straight) return center.add(-straight/2+distance,radius,0f) to 0f
        distance-=straight
        if(distance<arc) {
            val a=distance/radius
            return center.add(straight/2+sin(a)*radius,cos(a)*radius,0f) to -a
        }
        distance-=arc
        if(distance<straight) return center.add(straight/2-distance,-radius,0f) to -PI.toFloat()
        val a=(distance-straight)/radius
        return center.add(-straight/2-sin(a)*radius,-cos(a)*radius,0f) to (-PI.toFloat()-a)
    }

    /**
     * One-way charge motion. Cargo is deliberately kept on the top deck; the
     * closed return path belongs to belt slats only. Presentation hides and
     * repositions this part between cycles so a client never sees a backwards
     * or underside jump.
     */
    private fun topCargoPose(part: Part, phase: Float): Vector3f {
        val straight = 7.6f
        val cycle = ((phase + part.angle).mod(PI.toFloat() * 2f) / (PI.toFloat() * 2f))
        return Vector3f(part.pivot.x - straight / 2f + cycle * straight + part.center.x,
            part.pivot.y + .58f, part.pivot.z + part.center.z)
    }
}
