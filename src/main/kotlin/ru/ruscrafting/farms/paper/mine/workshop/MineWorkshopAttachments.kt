package ru.ruscrafting.farms.paper.mine.workshop

import org.bukkit.Material
import org.joml.Vector3f
import ru.ruscrafting.farms.paper.mine.expedition.MineDisplayBlueprints
import ru.ruscrafting.farms.paper.mine.workshop.MineWorkshopMachines.Animation
import ru.ruscrafting.farms.paper.mine.workshop.MineWorkshopMachines.Visual

/** Purpose-built attachments: every panel has a bracket; the output is one continuous roller bed. */
internal object MineWorkshopAttachments {
    fun crusherPanel(): List<Visual> = buildList {
        // Bracket from the front-right crusher upright to the small drive housing.
        box(Material.POLISHED_BASALT, 1.95f, 1.48f, 1.55f, .60f, .18f, .22f)
        box(Material.WEATHERED_CUT_COPPER, 1.72f, 1.48f, 1.75f, .62f, .70f, .26f)
        lever(MineWorkshopMachines.DRIVE)
    }

    fun furnacePanel(): List<Visual> = buildList {
        // One panel fixed across the front uprights, with one start handle and one outlet handle.
        box(Material.POLISHED_BASALT, .65f, 1.45f, 1.48f, 1.65f, .20f, .26f)
        box(Material.WEATHERED_CUT_COPPER, .65f, 1.48f, 1.72f, 1.75f, .85f, .28f)
        lever(MineWorkshopMachines.AIR)
        lever(MineWorkshopMachines.TAP)
        // Small progress window stays inside its housing, not suspended over the aisle.
        box(Material.POLISHED_BLACKSTONE, .65f, 1.49f, 1.882f, .24f, .59f, .035f)
        box(Material.YELLOW_TERRACOTTA, .65f, 1.46f, 1.91f, .11f, .43f, .025f)
        box(Material.LIME_CONCRETE, .65f, 1.73f, 1.913f, .11f, .07f, .025f)
        box(Material.IRON_BLOCK, .65f, 1.245f, 1.94f, .20f, .045f, .025f, animation = Animation.NEEDLE)
        // Short outlet attached to the furnace's east face and aligned to the mould's trough.
        trough(.0f, 1.22f, 1.8f, 3.2f)
        for (x in listOf(1.95f, 3.05f)) leg(x, 0f, 1.12f)
    }

    fun casting(): List<Visual> = buildList {
        // Furnace connector ends at x=-.8 relative to this anchor. Continue into the open mould.
        trough(0f, 1.22f, -.8f, .25f)
        for (x in listOf(.16f, 1.84f)) {
            box(Material.POLISHED_BASALT, x, .91f, 1.05f, .20f, .26f, 3.90f)
            for (z in listOf(-.65f, 2.72f)) leg(x, z, .91f)
        }
        // Refractory mould: open west charging notch and open south roll-out edge.
        box(Material.POLISHED_BLACKSTONE, 1f, 1.085f, 0f, 1.50f, .12f, 1.50f)
        box(Material.SMOOTH_QUARTZ, 1f, 1.30f, -.76f, 1.70f, .34f, .12f)
        box(Material.SMOOTH_QUARTZ, 1.79f, 1.30f, 0f, .12f, .34f, 1.40f)
        for (z in listOf(-.51f, .51f)) box(Material.SMOOTH_QUARTZ, .21f, 1.30f, z, .12f, .34f, .38f)
        // All rollers share the same axis and carry the billet toward the aisle.
        listOf(.86f, 1.24f, 1.62f, 2f, 2.38f, 2.76f).forEach { roller(1f, it) }
        box(Material.ORANGE_STAINED_GLASS, -2.5f, 1.29f, 0f, .30f, .16f, .30f,
            hidden = true, animation = Animation.MOLTEN, path = MineWorkshopGeometry.FURNACE_TO_CASTING)
        box(Material.IRON_BLOCK, 1f, 1.4f, 0f, .78f, .38f, .80f,
            hidden = true, animation = Animation.COOLING, path = MineWorkshopGeometry.CASTING_TO_RACK)
    }

    fun rack(): List<Visual> = buildList {
        // This continuation starts at output z=3, without stacking another complete table over it.
        for (x in listOf(-.84f, .84f)) {
            box(Material.POLISHED_BASALT, x, .91f, 0f, .20f, .26f, 1.96f)
            leg(x, .70f, .91f)
        }
        listOf(-.85f, -.47f, -.09f, .29f, .67f).forEach { roller(0f, it) }
        box(Material.CUT_COPPER, 0f, 1.19f, .97f, 1.82f, .18f, .10f)
    }

    private fun MutableList<Visual>.lever(control: String) {
        val at = MineWorkshopGeometry.CONTROL_OFFSETS.getValue(control)
        val pivot = Vector3f(at.x, at.y - .17f, at.z)
        box(Material.POLISHED_BLACKSTONE, at.x, pivot.y, at.z - .025f, .25f, .22f, .12f)
        box(Material.IRON_BLOCK, at.x, at.y, at.z, .09f, .33f, .09f,
            control = control, motion = "lever", pivot = pivot)
        box(Material.RED_CONCRETE, at.x, at.y + .17f, at.z, .26f, .13f, .15f,
            control = control, motion = "lever", pivot = pivot)
    }

    private fun MutableList<Visual>.leg(x: Float, z: Float, height: Float) {
        box(Material.POLISHED_BASALT, x, (height + .12f) / 2f, z, .16f, height - .12f, .16f)
        box(Material.CUT_COPPER, x, .08f, z, .32f, .16f, .30f)
    }

    /** Open top metal channel. No glass sheet conceals the passing liquid. */
    private fun MutableList<Visual>.trough(z: Float, y: Float, fromX: Float, toX: Float) {
        val x = (fromX + toX) / 2f
        val length = toX - fromX
        box(Material.POLISHED_BLACKSTONE, x, y - .085f, z, length - .04f, .13f, .50f)
        for (side in listOf(-.29f, .29f))
            box(Material.EXPOSED_CUT_COPPER, x, y + .025f, z + side, length, .25f, .10f)
    }

    private fun MutableList<Visual>.roller(x: Float, z: Float) {
        box(Material.POLISHED_ANDESITE, x, 1.13f, z, 1.46f, .16f, .16f,
            motion = "axle", pivot = Vector3f(x, 1.13f, z), animation = Animation.COOLING_ROLLERS)
    }

    private fun MutableList<Visual>.box(
        material: Material, x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float,
        control: String? = null, motion: String = "fixed", pivot: Vector3f = Vector3f(),
        animation: Animation = Animation.STATIC, hidden: Boolean = false, path: List<Vector3f> = emptyList(),
    ) {
        add(Visual(MineDisplayBlueprints.Part(material, Vector3f(x, y, z), Vector3f(sx, sy, sz),
            moving = motion != "fixed", pivot = pivot, motion = motion, idleHidden = hidden),
            1f, Vector3f(), control = control, animation = animation, path = path))
    }
}
