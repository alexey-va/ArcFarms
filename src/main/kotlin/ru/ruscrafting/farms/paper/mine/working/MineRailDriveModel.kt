package ru.ruscrafting.farms.paper.mine.working

import org.bukkit.Material
import org.joml.Vector3f
import ru.ruscrafting.farms.paper.mine.expedition.MineDisplayBlueprints.Part
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rail-laying presentation for the RAIL_EXTENSION carrier.
 *
 * The drilling chassis remains the same machine: the rail layer is added at
 * the back of [MineDriveModel.parts], where it cannot hide the driver's open
 * centre cab or change the carrier's collision footprint.  Forward is +Z;
 * therefore the rail magazines, rollers and sleeper press all live behind the
 * cutter in negative Z.
 */
internal object MineRailDriveModel {
    const val RESERVE_SLOT_LABEL = "reserve_slot"
    const val FEEDER_LABEL = "feeder"

    /** Service visuals are owned by the runtime and placed at these local anchors. */
    val servicePoints: Map<String, Vector3f> = mapOf(
        RESERVE_SLOT_LABEL to Vector3f(-1.02f, .70f, -.75f),
        FEEDER_LABEL to Vector3f(1.02f, .70f, -.35f),
    )

    /** Models used for the occasional maintenance service objects. */
    val kinds: Set<String> = setOf("rail_drive_cassette", "rail_drive_jam", "rail_drive_feeder")

    private val railLayer: List<Part> = buildList {
        fun box(
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
        ) {
            add(
                Part(
                    material = material,
                    center = Vector3f(x, y, z),
                    size = Vector3f(width, height, depth),
                    angle = angle,
                    moving = moving,
                    pivot = Vector3f(pivot),
                    motion = motion,
                    idleHidden = idleHidden,
                ),
            )
        }

        // Two yellow framed magazines sit above the rear deck.  Their open
        // faces leave the iron rails visible instead of turning the rail kit
        // into two opaque blocks.
        for (x in listOf(-.82f, .82f)) {
            val outer = if (x < 0f) -.99f else .99f
            val inner = if (x < 0f) -.68f else .68f
            box(Material.YELLOW_TERRACOTTA, outer, 1.045f, -.55f, .10f, .63f, .82f)
            box(Material.YELLOW_TERRACOTTA, inner, 1.045f, -.55f, .10f, .63f, .82f)
            // Recess the shelves by .02 in Z so their faces do not sit on
            // the same plane as the upright faces in the display validator.
            box(Material.YELLOW_TERRACOTTA, x, .69f, -.55f, .40f, .10f, .78f)
            box(Material.YELLOW_TERRACOTTA, x, 1.36f, -.55f, .40f, .10f, .78f)

            // A vertical pair of steel rails is readable through the frame;
            // the short wood bars show that this is a mixed rail/sleeper store.
            box(Material.IRON_BLOCK, x, 1.04f, -.55f, .07f, .40f, .66f)
            for (y in listOf(.84f, .98f, 1.12f)) {
                box(Material.DARK_OAK_PLANKS, x, y, -.55f, .28f, .07f, .60f)
            }
        }

        // Two longitudinal steel rails are staged on the rear upper deck.
        // Their bottom faces meet the sleepers at a small, intentional reveal.
        for (x in listOf(-.62f, .62f)) {
            box(Material.IRON_BLOCK, x, 1.80f, -1.28f, .08f, .10f, .50f)
        }
        // Keep the central cab's upper sightline open; the sleepers remain
        // behind its rear housing rather than becoming a roof over the seat.
        for (z in listOf(-1.29f, -1.10f)) {
            box(Material.DARK_OAK_PLANKS, 0f, 1.70f, z, 1.30f, .10f, .11f)
        }

        // The front sleeper is the visible feed stroke.  Existing display
        // press semantics move a part down by one block at phase PI and
        // return it at 2*PI; its high starting point keeps the stroke just
        // above the chassis instead of burying it in the floor.
        box(
            Material.DARK_OAK_PLANKS, 0f, 1.70f, -1.48f, 1.30f, .10f, .11f,
            moving = true, pivot = Vector3f(0f, 1.70f, -1.48f), motion = "press",
        )
        // A guided crosshead keeps the laying stroke mechanically attached
        // throughout the full cycle, with fixed uprights rooted on the rear deck.
        box(Material.EXPOSED_CUT_COPPER, 0f, 2.37f, -1.48f, 1.72f, .10f, .16f)
        for (x in listOf(-.80f, .80f)) {
            box(Material.IRON_BLOCK, x, 1.48f, -1.48f, .12f, 1.68f, .12f)
        }
        box(Material.COPPER_BLOCK, 0f, 1.82f, -1.48f, 1.50f, .16f, .16f,
            moving = true, pivot = Vector3f(0f, 1.82f, -1.48f), motion = "press")

        // Paired rear rollers use the existing axle motion.  The discs are
        // deliberately separate from the cutter's Z-plane rotation, so a
        // running machine reads as a rail feed rather than a second cutter.
        fun roller(z: Float, radius: Float = .045f) {
            // The rollers sit on the two side rails, leaving the full-width
            // sleeper free to pass between them during the press stroke.
            for (x in listOf(-.88f, .88f)) {
                box(Material.IRON_BLOCK, x, .76f, z, .16f, .10f, .10f)
                val pivot = Vector3f(x, .76f, z)
                // The small hub stops just inside the rotating rim, leaving a
                // visible seam and avoiding coplanar X faces at the axle.
                box(Material.POLISHED_ANDESITE, x, .76f, z, .05f, .05f, .05f)
                repeat(8) { index ->
                    val angle = index * PI.toFloat() / 4f
                    box(
                        if (index % 2 == 0) Material.POLISHED_ANDESITE else Material.IRON_BLOCK,
                        x,
                        .76f + cos(angle) * radius,
                        z + sin(angle) * radius,
                        .06f,
                        .03f,
                        .03f,
                        moving = true,
                        pivot = pivot,
                        motion = "axle",
                    )
                }
            }
        }
        roller(-1.40f)
        roller(-1.58f)

        // Small colour-coded couplers point at the separately rendered
        // reserve and feeder service objects without extending past ±1.18.
        box(Material.YELLOW_TERRACOTTA, -1.10f, .76f, -.75f, .12f, .16f, .28f)
        box(Material.YELLOW_TERRACOTTA, 1.10f, .76f, -.35f, .12f, .16f, .30f)
        box(Material.IRON_BLOCK, 1.10f, .86f, -.35f, .06f, .12f, .18f)
    }

    /** The complete body used by [MineDriveRig] for a rail extension. */
    val parts: List<Part> = MineDriveModel.parts + railLayer

    fun model(kind: String): List<Part> = when (kind) {
        "rail_drive_cassette" -> cassette()
        "rail_drive_jam" -> jam()
        "rail_drive_feeder" -> feeder()
        else -> error("Unknown rail drive model: $kind")
    }

    /** A compact two-rail cassette with cross sleepers for the reserve slot. */
    private fun cassette(): List<Part> = buildList {
        fun box(material: Material, x: Float, y: Float, z: Float, width: Float, height: Float, depth: Float) {
            add(Part(material, Vector3f(x, y, z), Vector3f(width, height, depth), motion = "fixed"))
        }
        for (x in listOf(-.26f, .26f)) {
            box(Material.IRON_BLOCK, x, .43f, 0f, .08f, .08f, .86f)
        }
        for (z in listOf(-.30f, 0f, .30f)) {
            box(Material.DARK_OAK_PLANKS, 0f, .34f, z, .72f, .10f, .12f)
        }
    }

    /** A bent, protruding wooden sleeper used by the jam service point. */
    private fun jam(): List<Part> = listOf(
        Part(Material.DARK_OAK_PLANKS, Vector3f(0f, .16f, 0f), Vector3f(1.02f, .12f, .14f), motion = "fixed"),
        Part(
            Material.SPRUCE_PLANKS,
            Vector3f(.43f, .30f, .02f),
            Vector3f(.54f, .12f, .14f),
            angle = .40f,
            motion = "fixed",
        ),
        Part(Material.IRON_BLOCK, Vector3f(-.32f, .25f, 0f), Vector3f(.10f, .12f, .18f), motion = "fixed"),
    )

    /** An empty receiver that visibly meets the drive's right yellow coupler. */
    private fun feeder(): List<Part> = listOf(
        Part(Material.YELLOW_TERRACOTTA, Vector3f(.12f, .06f, 0f), Vector3f(.44f, .10f, .46f), motion = "fixed"),
        Part(Material.YELLOW_TERRACOTTA, Vector3f(.12f, .22f, -.18f), Vector3f(.40f, .28f, .07f), motion = "fixed"),
        Part(Material.YELLOW_TERRACOTTA, Vector3f(.12f, .22f, .18f), Vector3f(.40f, .28f, .07f), motion = "fixed"),
        Part(Material.IRON_BLOCK, Vector3f(.28f, .20f, 0f), Vector3f(.06f, .22f, .24f), motion = "fixed"),
    )
}
