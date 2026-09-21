package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Material
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small, scene-local fixtures used by the factory experiments.
 *
 * Coordinates are local to the fixture anchor.  The parent scene can therefore
 * move a fixture without changing its interaction geometry: the origin is the
 * centre of the target machine for [factory_jam_stone], the centre of the low
 * stand for moulds, and the centre of the pad for the remaining fixtures.
 * Detail is kept on supported frames and plates so that every silhouette reads
 * at the scale of a BlockDisplay assembly.
 */
internal object MineFactoryExperimentModels {
    val kinds = setOf(
        "factory_jam_stone",
        "factory_mould_gear",
        "factory_mould_plate",
        "factory_mould_rod",
        "factory_mould_socket",
        "factory_mould_stand",
        "factory_route_gate",
        "factory_route_bin",
        "factory_hose_reel",
        "factory_hose_nozzle",
        "factory_hose_stand",
        "factory_hot_bearing",
        "factory_crane_landing",
        "factory_product_plate",
        "factory_product_rod",
    )

    fun model(kind: String): List<MineDisplayBlueprints.Part> = when (kind) {
        "factory_jam_stone" -> jamStone()
        "factory_mould_gear" -> mouldGear()
        "factory_mould_plate" -> mouldPlate()
        "factory_mould_rod" -> mouldRod()
        "factory_mould_socket" -> mouldSocket()
        "factory_mould_stand" -> mouldStand()
        "factory_route_gate" -> routeGate()
        "factory_route_bin" -> routeBin()
        "factory_hose_reel" -> hoseReel()
        "factory_hose_nozzle" -> hoseNozzle()
        "factory_hose_stand" -> hoseNozzle().take(2)
        "factory_hot_bearing" -> hotBearing()
        "factory_crane_landing" -> craneLanding()
        "factory_product_plate" -> productPlate()
        "factory_product_rod" -> productRod()
        else -> error("Unknown factory experiment display model: $kind")
    }

    private fun part(
        material: Material,
        x: Float,
        y: Float,
        z: Float,
        width: Float,
        height: Float,
        depth: Float,
        angle: Float = 0f,
        moving: Boolean = false,
        pivot: Vector3f = Vector3f(),
        motion: String = "fixed",
        idleHidden: Boolean = false,
    ) = MineDisplayBlueprints.Part(
        material = material,
        center = Vector3f(x, y, z),
        size = Vector3f(width, height, depth),
        angle = angle,
        moving = moving,
        pivot = Vector3f(pivot),
        motion = motion,
        idleHidden = idleHidden,
    )

    /** A deliberately uneven .64 x .55 x .38 chunk centred in the roller throat. */
    private fun jamStone() = listOf(
        part(Material.TUFF, -.12f, .31f, -.02f, .34f, .34f, .30f),
        part(Material.ANDESITE, .15f, .37f, .03f, .30f, .40f, .32f),
        part(Material.TUFF, -.02f, .57f, .02f, .24f, .22f, .26f),
        part(Material.ANDESITE, .27f, .22f, -.10f, .16f, .18f, .18f),
    )

    /** Four-piece stand shared by all moulds: 1.8 x 1.5 footprint, 1.17 high. */
    private fun mouldStand() = listOf(
        part(Material.POLISHED_DEEPSLATE, 0f, .08f, 0f, 1.8f, .16f, 1.5f),
        part(Material.POLISHED_ANDESITE, -.72f, .65f, 0f, .14f, 1.0f, .14f),
        part(Material.POLISHED_ANDESITE, .72f, .65f, 0f, .14f, 1.0f, .14f),
        // A shallow depth keeps the rail's front/back planes clear of the uprights.
        part(Material.CUT_COPPER, 0f, 1.10f, 0f, 1.55f, .14f, .12f),
    )

    /** Gear silhouette: hub, four separated spokes, and three readable teeth. */
    private fun mouldGear() = mouldStand() + listOf(
        part(Material.IRON_BLOCK, 0f, .61f, 0f, .26f, .26f, .26f),
        part(Material.EXPOSED_CUT_COPPER, -.30f, .60f, 0f, .46f, .12f, .14f),
        part(Material.EXPOSED_CUT_COPPER, .30f, .60f, 0f, .46f, .12f, .14f),
        part(Material.EXPOSED_CUT_COPPER, 0f, .86f, 0f, .14f, .32f, .14f),
        part(Material.EXPOSED_CUT_COPPER, 0f, .34f, 0f, .14f, .32f, .14f),
        part(Material.POLISHED_ANDESITE, -.59f, .60f, 0f, .10f, .20f, .18f),
        part(Material.POLISHED_ANDESITE, .59f, .60f, 0f, .10f, .20f, .18f),
        part(Material.POLISHED_ANDESITE, 0f, .13f, 0f, .18f, .10f, .18f),
    )

    /** Broad plate silhouette with a recessed stripe and four corner fasteners. */
    private fun mouldPlate() = mouldStand() + listOf(
        part(Material.POLISHED_BASALT, 0f, .59f, 0f, 1.02f, .16f, .70f),
        part(Material.POLISHED_ANDESITE, 0f, .705f, 0f, .68f, .04f, .14f),
        part(Material.CUT_COPPER, -.38f, .75f, -.25f, .12f, .10f, .12f),
        part(Material.CUT_COPPER, .38f, .75f, -.25f, .12f, .10f, .12f),
        part(Material.CUT_COPPER, -.38f, .75f, .25f, .12f, .10f, .12f),
        part(Material.CUT_COPPER, .38f, .75f, .25f, .12f, .10f, .12f),
    )

    /** Upright rod silhouette with separated lower/upper collars. */
    private fun mouldRod() = mouldStand() + listOf(
        part(Material.POLISHED_ANDESITE, 0f, .22f, 0f, .32f, .08f, .32f),
        part(Material.IRON_BLOCK, 0f, .62f, 0f, .20f, .70f, .20f),
        part(Material.CUT_COPPER, 0f, .25f, 0f, .34f, .10f, .34f),
        part(Material.CUT_COPPER, 0f, .98f, 0f, .34f, .08f, .34f),
    )

    /** Low socket with a clear open front and four mould locating guides. */
    private fun mouldSocket() = listOf(
        part(Material.POLISHED_DEEPSLATE, 0f, .08f, 0f, 1.8f, .16f, 1.5f),
        part(Material.POLISHED_BASALT, 0f, .24f, .08f, 1.20f, .10f, 1.0f),
        part(Material.POLISHED_ANDESITE, -.58f, .43f, -.42f, .16f, .42f, .16f),
        part(Material.POLISHED_ANDESITE, .58f, .43f, -.42f, .16f, .42f, .16f),
        part(Material.POLISHED_ANDESITE, -.58f, .43f, .42f, .16f, .42f, .16f),
        part(Material.POLISHED_ANDESITE, .58f, .43f, .42f, .16f, .42f, .16f),
        part(Material.CUT_COPPER, 0f, .43f, -.50f, 1.15f, .40f, .14f),
    )

    /** Pivoting guide gate: the arm rotates from the left hinge over the deck. */
    private fun routeGate() = listOf(
        part(Material.POLISHED_DEEPSLATE, 0f, .08f, 0f, 1.9f, .16f, 1.25f),
        part(Material.POLISHED_BASALT, 0f, .24f, 0f, 1.50f, .08f, .72f),
        part(Material.POLISHED_ANDESITE, -.72f, .55f, 0f, .14f, .74f, .14f),
        part(Material.POLISHED_ANDESITE, .72f, .55f, 0f, .14f, .74f, .14f),
        part(Material.IRON_BLOCK, -.72f, .82f, 0f, .22f, .22f, .22f),
        part(Material.CUT_COPPER, -.08f, .82f, 0f, 1.30f, .12f, .12f,
            moving = true, pivot = Vector3f(-.72f, .82f, 0f), motion = "lever"),
    )

    /** Low three-sided accumulator; the open front is the receiving interaction side. */
    private fun routeBin() = listOf(
        part(Material.POLISHED_DEEPSLATE, 0f, .08f, 0f, 1.85f, .16f, 1.45f),
        part(Material.POLISHED_BASALT, 0f, .25f, .08f, 1.42f, .10f, 1.08f),
        part(Material.WEATHERED_CUT_COPPER, -.82f, .57f, .08f, .16f, .70f, 1.28f),
        part(Material.WEATHERED_CUT_COPPER, .82f, .57f, .08f, .16f, .70f, 1.28f),
        part(Material.WEATHERED_CUT_COPPER, 0f, .57f, -.55f, 1.48f, .70f, .16f),
    )

    /** Supported reel, approximately 1.4 blocks wide; ring segments turn around the hub. */
    private fun hoseReel(): List<MineDisplayBlueprints.Part> = buildList {
        add(part(Material.POLISHED_DEEPSLATE, 0f, .08f, 0f, 1.4f, .16f, 1.05f))
        add(part(Material.POLISHED_BASALT, 0f, .72f, -.22f, .16f, 1.15f, .16f))
        val pivot = Vector3f(0f, .79f, .18f)
        repeat(8) { index ->
            val angle = (index * PI / 4.0).toFloat()
            add(part(
                Material.WEATHERED_CUT_COPPER,
                cos(angle) * .42f,
                .79f + sin(angle) * .42f,
                .18f,
                .16f,
                .20f,
                .12f,
                angle = angle,
                moving = true,
                pivot = pivot,
                motion = "rotate",
            ))
        }
        add(part(Material.IRON_BLOCK, 0f, .79f, .18f, .22f, .22f, .24f))
    }

    /** Compact nozzle: its barrel and tip extend in +Z from a reachable grip. */
    private fun hoseNozzle() = listOf(
        part(Material.POLISHED_BASALT, 0f, .08f, 0f, .55f, .16f, .45f),
        part(Material.POLISHED_BASALT, 0f, .20f, 0f, .14f, .10f, .14f),
        part(Material.CUT_COPPER, 0f, .40f, 0f, .22f, .36f, .26f),
        part(Material.WEATHERED_CUT_COPPER, 0f, .47f, .35f, .16f, .16f, .40f),
        part(Material.CUT_COPPER, 0f, .47f, .58f, .22f, .20f, .10f),
        part(Material.POLISHED_ANDESITE, 0f, .47f, .70f, .14f, .14f, .12f),
    )

    /** A small hot bearing node attached to a rear plate, with the core visible through its frame. */
    private fun hotBearing() = listOf(
        part(Material.POLISHED_BASALT, 0f, .43f, -.27f, .64f, .58f, .12f),
        part(Material.CUT_COPPER, -.25f, .43f, -.02f, .12f, .50f, .32f),
        part(Material.CUT_COPPER, .25f, .43f, -.02f, .12f, .50f, .32f),
        part(Material.CUT_COPPER, 0f, .69f, -.02f, .34f, .10f, .32f),
        part(Material.CUT_COPPER, 0f, .17f, -.02f, .34f, .10f, .32f),
        part(Material.ORANGE_STAINED_GLASS, 0f, .43f, .08f, .22f, .22f, .24f),
    )

    /** Flat landing pad with low corner guide rails and load chocks. */
    private fun craneLanding() = buildList {
        // The upstream pad extends beyond the first roller, so it has its own
        // legs down to the floor instead of cantilevering in empty space.
        for (x in listOf(-1.14f, 1.14f)) for (z in listOf(-1.14f, 1.14f)) {
            add(part(Material.POLISHED_BASALT, x, -.94f, z, .16f, 1.88f, .16f))
            add(part(Material.CUT_COPPER, x, -1.90f, z, .35f, .08f, .35f))
        }
        addAll(listOf(
        part(Material.POLISHED_DEEPSLATE, 0f, .08f, 0f, 2.8f, .16f, 2.8f),
        // Keep these rails horizontal: Part.angle rotates in the X/Y plane,
        // which would otherwise tilt a supposed floor guide into the pad.
        part(Material.YELLOW_TERRACOTTA, -.68f, .25f, -.68f, .82f, .10f, .12f),
        part(Material.YELLOW_TERRACOTTA, .68f, .25f, -.68f, .82f, .10f, .12f),
        part(Material.YELLOW_TERRACOTTA, -.68f, .25f, .68f, .82f, .10f, .12f),
        part(Material.YELLOW_TERRACOTTA, .68f, .25f, .68f, .82f, .10f, .12f),
        part(Material.POLISHED_ANDESITE, -.88f, .34f, -.88f, .24f, .18f, .24f),
        part(Material.POLISHED_ANDESITE, .88f, .34f, -.88f, .24f, .18f, .24f),
        part(Material.POLISHED_ANDESITE, -.88f, .34f, .88f, .24f, .18f, .24f),
        part(Material.POLISHED_ANDESITE, .88f, .34f, .88f, .24f, .18f, .24f),
        ))
    }

    /**
     * Finished plate stack for the result bench.  The local floor is y=0: the
     * parent can place the complete product at bench-top +1.5 without legs.
     */
    private fun productPlate() = listOf(
        part(Material.IRON_BLOCK, 0f, .08f, 0f, 1.0f, .12f, .72f),
        part(Material.IRON_BLOCK, -.02f, .21f, 0f, .86f, .10f, .58f),
        part(Material.IRON_BLOCK, .02f, .35f, .03f, .78f, .10f, .52f),
        part(Material.IRON_BLOCK, -.01f, .49f, -.01f, .68f, .10f, .46f),
        part(Material.IRON_BLOCK, 0f, .59f, 0f, .36f, .08f, .12f),
    )

    /**
     * Finished bundle of three parallel iron rods with two raised binding
     * bands.  The bundle rests directly on a shallow bench support.
     */
    private fun productRod() = listOf(
        part(Material.IRON_BLOCK, 0f, .08f, 0f, .80f, .12f, .62f),
        part(Material.IRON_BLOCK, -.24f, .25f, 0f, .14f, .14f, .72f),
        part(Material.IRON_BLOCK, 0f, .25f, .02f, .14f, .14f, .72f),
        part(Material.IRON_BLOCK, .24f, .25f, -.01f, .14f, .14f, .72f),
        part(Material.IRON_BLOCK, 0f, .30f, -.25f, .72f, .16f, .08f),
        part(Material.IRON_BLOCK, 0f, .30f, .25f, .72f, .16f, .08f),
    )
}
