package ru.ruscrafting.farms.paper.lumber.scene

import org.joml.Vector3f
import ru.ruscrafting.farms.domain.lumber.LumberPhase
import ru.ruscrafting.farms.domain.lumber.LumberPose
import ru.ruscrafting.farms.domain.lumber.LumberTiming
import ru.ruscrafting.farms.domain.lumber.LumberVisualView

/** Roof-mounted bridge below the captured solid ceiling at Y79; no hole, terrain edit or new model. */
internal object LumberCraneGeometry {
    private const val STOCK_ANCHOR_ABOVE_BASE = (.175 + .28) * 1.7
    private const val LIFT_BASE = 77.12
    private const val CROSS_Z = 176.4

    fun stock(warehouse: LumberPose, progress: Double): LumberPose {
        val t = progress.coerceIn(0.0, 1.0) * 14.0
        val start = LumberSceneGeometry.outputPickup()
        val lifted = start.copy(y = LIFT_BASE + STOCK_ANCHOR_ABOVE_BASE)
        val beforeCross = lifted.copy(z = CROSS_Z)
        val afterCross = beforeCross.copy(x = warehouse.x)
        val aboveStore = afterCross.copy(z = warehouse.z)
        return when {
            t <= 1 -> start
            t < 3 -> LumberSceneGeometry.blend(start, lifted, (t - 1) / 2)
            t < 5 -> LumberSceneGeometry.blend(lifted, beforeCross, (t - 3) / 2)
            t < 8 -> LumberSceneGeometry.blend(beforeCross, afterCross, (t - 5) / 3)
            t < 12 -> LumberSceneGeometry.blend(afterCross, aboveStore, (t - 8) / 4)
            else -> LumberSceneGeometry.blend(aboveStore, LumberSceneGeometry.warehouseStock(warehouse), (t - 12) / 2)
        }
    }

    fun sample(view: LumberVisualView, warehouse: LumberPose, returnProgress: Double): List<LumberSceneGeometry.Part> = buildList {
        fun part(id: String, x: Double, y: Double, z: Double, sx: Float, sy: Float, sz: Float, block: String) {
            add(LumberSceneGeometry.Part("crane-$id", Vector3f(x.toFloat(), y.toFloat(), z.toFloat()),
                Vector3f(sx, sy, sz), block = block))
        }
        val transferring = view.state.phase == LumberPhase.CRANE_TRANSFER
        val progress = view.phaseAgeTicks.toDouble() / LumberTiming.CRANE_TRANSFER_TICKS
        val cargo = if (transferring) stock(warehouse, progress) else LumberSceneGeometry.outputPickup()
        val returning = !transferring && view.state.completedRounds > 0 && returnProgress < 1.0
        val trolley = if (returning) LumberSceneGeometry.blend(warehouse, cargo, (returnProgress - .25) / .75) else cargo
        for (x in listOf(185.0, 194.8)) {
            part("rail-$x", x, 78.88, 177.25, .16f, .12f, 18.5f, "minecraft:polished_deepslate")
            for (z in listOf(169.3, 185.9)) part("roof-mount-$x-$z", x, 78.94, z,
                .5f, .12f, .5f, "minecraft:dark_oak_planks")
        }
        part("bridge", 189.9, 78.73, trolley.z, 10.1f, .18f, .26f, "minecraft:stripped_spruce_log[axis=x]")
        part("hoist", trolley.x, 78.62, trolley.z, .42f, .18f, .48f, "minecraft:iron_block")
        part("spreader", trolley.x, 78.60, trolley.z, .20f, .10f, 5.3f, "minecraft:polished_deepslate")
        if (returning) {
            val bottom = 73.135 + (78.45 - 73.135) * (returnProgress * 4).coerceIn(0.0, 1.0)
            for (z in listOf(-2.5, 2.5)) for (side in listOf(-1, 1)) part("return-rope-$z-$side",
                trolley.x + side * .73, (bottom + 78.55) / 2, trolley.z + z, .20f,
                (78.55 - bottom).toFloat(), .20f, "minecraft:iron_chain[axis=y]")
            return@buildList
        }
        val base = cargo.y - STOCK_ANCHOR_ABOVE_BASE
        for (z in listOf(-2.5, 2.5)) {
            // The under-slung bars rest in the receiving deck before the timber arrives.
            part("sling-$z", cargo.x, base - .0425, cargo.z + z, 2.08f, .085f, .09f, "minecraft:iron_block")
            if (!transferring) continue
            val attached = (progress * 14).coerceIn(0.0, 1.0)
            val bottom = 78.45 + (base - .0425 - 78.45) * attached
            val length = (78.55 - bottom).coerceAtLeast(.01)
            for (side in listOf(-1, 1)) part("rope-$z-$side", cargo.x + side * .73,
                bottom + length / 2, cargo.z + z, .20f, length.toFloat(), .20f, "minecraft:iron_chain[axis=y]")
        }
    }
}
