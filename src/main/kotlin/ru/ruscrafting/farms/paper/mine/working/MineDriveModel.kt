package ru.ruscrafting.farms.paper.mine.working

import org.bukkit.Material
import org.joml.Vector3f
import ru.ruscrafting.farms.paper.mine.expedition.MineDisplayBlueprints.Part
import kotlin.math.*

/** Low open cab, twin crawler tracks and a rotating toothed cutter. Forward is +Z. */
internal object MineDriveModel {
    val parts: List<Part> = buildList {
        fun box(m: Material, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float,
            angle: Float = 0f, moving: Boolean = false, pivot: Vector3f = Vector3f()) {
            add(Part(m, Vector3f(x,y,z), Vector3f(w,h,d), angle, moving, pivot))
        }
        for (x in listOf(-.93f, .93f)) {
            box(Material.POLISHED_BLACKSTONE, x, .23f, -.15f, .42f, .46f, 2.7f)
            for (z in listOf(-1.15f,-.55f,.05f,.65f)) {
                box(Material.POLISHED_ANDESITE, x * 1.04f, .24f, z, .48f, .25f, .37f)
            }
            box(Material.YELLOW_TERRACOTTA, x, .56f, -.15f, .46f, .16f, 2.8f)
            box(Material.COPPER_BLOCK, x * .81f, 1.04f, -1.15f, .16f, .8f, .16f)
            box(Material.SEA_LANTERN, x * .76f, .85f, .94f, .23f, .23f, .18f)
        }
        box(Material.WEATHERED_CUT_COPPER, 0f, .42f, -.15f, 1.42f, .4f, 2.5f)
        box(Material.EXPOSED_CUT_COPPER, 0f, .9f, -1.08f, 1.25f, .55f, .55f)
        box(Material.POLISHED_BLACKSTONE, .48f, 1.36f, -1.16f, .18f, .45f, .18f)
        box(Material.IRON_BLOCK, 0f, .9f, 1.15f, .34f, .34f, 1.25f)
        val pivot = Vector3f(0f,.9f,1.86f)
        box(Material.IRON_BLOCK, 0f,.9f,1.86f,.38f,.38f,.55f)
        repeat(12) { i ->
            val a = i * PI.toFloat() / 6
            box(Material.POLISHED_ANDESITE, cos(a)*.62f,.9f+sin(a)*.62f,1.86f,
                .25f,.26f,.42f,a,true,pivot)
            box(Material.IRON_BLOCK, cos(a)*.77f,.9f+sin(a)*.77f,2.02f,
                .17f,.14f,.3f,a+.12f,true,pivot)
            if (i % 3 == 0) box(Material.CUT_COPPER, cos(a)*.39f,.9f+sin(a)*.39f,1.86f,
                .37f,.12f,.22f,a,true,pivot)
        }
    }
}
