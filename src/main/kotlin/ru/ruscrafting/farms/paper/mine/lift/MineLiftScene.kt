package ru.ruscrafting.farms.paper.mine.lift

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.ceil
import kotlin.math.floor

/** Bounded vanilla display scene in the surveyed shaft. Native seats scale with the cabin footprint. */
internal class MineLiftScene(private val plugin: Plugin, private val settings: MineLiftSettings, private val world: World) : AutoCloseable {
    private data class Part(
        val display: BlockDisplay,
        val x: Double,
        val y: Double,
        val z: Double,
        val doorSide: MineLiftDoorSide? = null,
        val doorShiftX: Double = 0.0,
        val doorShiftY: Double = 0.0,
        val doorShiftZ: Double = 0.0,
        val doorTravel: Double? = null,
    )
    private val parts = mutableListOf<Part>()
    private data class LandingPart(val floorIndex: Int, val part: Part)
    private val landingDoors = mutableListOf<LandingPart>()
    private val entities = mutableListOf<Entity>()
    private val chain = mutableListOf<BlockDisplay>()
    val seats = mutableListOf<Entity>()
    private val seatOffsets = mutableListOf<Pair<Double, Double>>()
    // Interaction's native passenger attachment is its .01 height; displays have zero height.
    private val seatYOffset = .02 + if (settings.theme == MineLiftTheme.CASINO) .01f.toDouble() else 0.0
    val panels = mutableMapOf<UUID, Int>()
    private var cabinHitbox: Interaction? = null
    private val labels = mutableListOf<TextDisplay>()
    private val key = NamespacedKey(plugin, "mine_lift_${settings.id}")
    private val legacyKey = if (settings.id == "main") NamespacedKey(plugin, "mine_lift") else null
    private var doorOpening = 1.0
    private var openDoorSide: MineLiftDoorSide? = null
    private var previousY = Double.NaN

    fun owns(entity: Entity) = entity.persistentDataContainer.has(key, PersistentDataType.BYTE) ||
        (legacyKey != null && entity.persistentDataContainer.has(legacyKey, PersistentDataType.BYTE))
    fun cleanOrphans(chunk: org.bukkit.Chunk) {
        chunk.entities.forEach(::cleanOrphan)
    }

    fun cleanOrphan(entity: Entity) {
        if (owns(entity) && entity !in entities) entity.remove()
    }

    /** BlockDisplay is visual only; this padded interaction AABB is the click target for the visible cabin. */
    fun ownsCabinHitbox(entity: Entity): Boolean = entity.uniqueId == cabinHitbox?.uniqueId

    fun ownsCabinInteraction(entity: Entity): Boolean = ownsCabinHitbox(entity)

    fun isDoorOpenAt(index: Int): Boolean = index in settings.floors.indices &&
        doorOpening == 1.0 && openDoorSide == settings.openingSide(index)

    fun spawn(y: Double, floorText: (Int) -> Component): CompletableFuture<Unit> = spawn(y, floorText) { step ->
        while (!step()) Unit
        CompletableFuture.completedFuture(Unit)
    }

    fun spawn(
        y: Double,
        floorText: (Int) -> Component,
        capture: ((() -> Boolean) -> CompletableFuture<Unit>),
    ): CompletableFuture<Unit> {
        val halfX = settings.width / 2
        val halfZ = settings.depth / 2
        val theme = settings.theme
        val doorSides = settings.floors.indices.map(settings::openingSide).toSet()
        if (theme.enclosed) {
            casinoFloorAndCeiling()
            MineLiftDoorSide.entries.forEach { side ->
                casinoWall(side, side.takeIf { it in doorSides })
            }
            casinoDecoration(doorSides)
        } else {
            block(-halfX, -.22, -halfZ, settings.width, .22, settings.depth, theme.floorMaterial)
            block(-halfX, theme.cabinWallHeight, -halfZ, settings.width, .18, settings.depth, theme.ceilingMaterial)
            for (x in listOf(-halfX, halfX - .16)) for (z in listOf(-halfZ, halfZ - .16)) {
                block(x, 0.0, z, .16, theme.cabinWallHeight, .16, theme.cornerMaterial)
            }
            MineLiftDoorSide.entries.forEach { side ->
                val openingSide = side.takeIf { it in doorSides }
                when (side) {
                    MineLiftDoorSide.WEST -> guard(-halfX, -halfZ, settings.depth, vertical = true, openingSide)
                    MineLiftDoorSide.EAST -> guard(halfX - .08, -halfZ, settings.depth, vertical = true, openingSide)
                    MineLiftDoorSide.NORTH -> guard(-halfX, -halfZ, settings.width, vertical = false, openingSide)
                    MineLiftDoorSide.SOUTH -> guard(-halfX, halfZ - .08, settings.width, vertical = false, openingSide)
                }
            }
            block(-.2, 2.3, -.2, .4, .35, .4, theme.lightMaterial)
        }
        spawnCabinHitbox(y)
        parts.forEach { it.display.teleport(origin(y).add(it.x, it.y, it.z)) }
        for (x in seatAxis(settings.width)) for (z in seatAxis(settings.depth)) {
            seatOffsets += x to z
            val at = origin(y).add(x, seatYOffset, z)
            seats += if (theme == MineLiftTheme.CASINO) {
                // Interaction has no position interpolator: position-sync packets reset the
                // rider's camera history. Share the cabin's one-tick native interpolation.
                display(at, Material.AIR)
            } else {
                world.spawn(at, Interaction::class.java) {
                    mark(it); it.interactionWidth = .7f; it.interactionHeight = .01f; it.isResponsive = true
                }.also(entities::add)
            }
        }
        val anchorY = settings.floors.first().y + 3
        val chainSize = kotlin.math.ceil(anchorY - settings.floors.last().y).toInt()
        var chainIndex = 0
        return capture {
            if (chainIndex == chainSize) true else {
                val bottom = y + 2.83 + chainIndex++
                chain += display(Location(world, settings.x - .5, bottom, settings.z - .5), theme.chainMaterial).also { entity ->
                    entity.transformation = transform(0.0, 0.0, 0.0, 1.0, (anchorY - bottom).coerceIn(0.0, 1.0), 1.0)
                }
                chainIndex == chainSize
            }
        }.thenApply {
        settings.floors.forEachIndexed { index, floor ->
            if (theme == MineLiftTheme.CASINO) casinoLandingDoors(index)
            val panel = world.spawn(floor.panel.location(world), Interaction::class.java) {
                mark(it); it.interactionWidth = .85f; it.interactionHeight = 1.3f; it.isResponsive = true
            }.also(entities::add)
            panels[panel.uniqueId] = index
            if (theme.enclosed) {
                val horizontal = settings.openingSide(index) in setOf(MineLiftDoorSide.NORTH, MineLiftDoorSide.SOUTH)
                val sx = if (horizontal) .46 else .12
                val sz = if (horizontal) .12 else .46
                display(floor.panel.location(world).add(-sx / 2, .55, -sz / 2), theme.panelMaterial).apply {
                    transformation = transform(0.0, 0.0, 0.0, sx, .7, sz)
                }
            } else {
                display(floor.panel.location(world).add(-.3, .0, -.3), theme.panelMaterial).apply {
                    transformation = transform(0.0, 0.0, 0.0, .6, .75, .6)
                }
            }
            val labelAt = floor.panel.location(world).add(0.0, 1.6, 0.0)
            if (theme == MineLiftTheme.CASINO) {
                labelAt.yaw = when (settings.openingSide(index)) {
                    MineLiftDoorSide.NORTH -> 180f
                    MineLiftDoorSide.EAST -> -90f
                    MineLiftDoorSide.SOUTH -> 0f
                    MineLiftDoorSide.WEST -> 90f
                }
                // Keep the fixed text plane on the room side of the call panel.
                labelAt.add(labelAt.direction.multiply(.16))
            }
            labels += world.spawn(labelAt, TextDisplay::class.java) {
                mark(it); it.text(floorText(index))
                it.billboard = if (theme == MineLiftTheme.CASINO) Display.Billboard.FIXED else Display.Billboard.VERTICAL
                if (theme == MineLiftTheme.CASINO) {
                    it.transformation = transform(0.0, 0.0, 0.0, .8, .8, .8)
                    it.lineWidth = 128
                    it.brightness = Display.Brightness(15, 15)
                }
                it.isShadowed = true; it.viewRange = .4f
                it.backgroundColor = org.bukkit.Color.fromARGB(150, 25, 21, 17)
            }.also(entities::add)
        }
        previousY = y // Each chain link was initialized at this exact height in its capture step.
        move(y, open = true)
        Unit
        }
    }

    fun move(y: Double, open: Boolean): Boolean {
        if (entities.any { !it.isValid }) return false
        val previousDoor = doorOpening
        doorOpening = (doorOpening + if (open) .1 else -.1).coerceIn(0.0, 1.0)
        val heightChanged = y != previousY
        val nextOpenDoorSide = if (open) {
            settings.openingSide(settings.floors.indices.minBy { kotlin.math.abs(settings.floors[it].y - y) })
        } else openDoorSide?.takeIf { doorOpening > 0.0 }
        val doorSideChanged = nextOpenDoorSide != openDoorSide
        var moved = true
        parts.filter { heightChanged || (it.doorSide != null && (previousDoor != doorOpening || doorSideChanged)) }.forEach { part ->
            val doorOffset = if (part.doorSide == nextOpenDoorSide) {
                doorOpening * (part.doorTravel ?: settings.theme.doorTravel)
            } else 0.0
            moved = part.display.teleport(origin(y).add(
                part.x + part.doorShiftX * doorOffset,
                part.y + part.doorShiftY * doorOffset,
                part.z + part.doorShiftZ * doorOffset,
            )) && moved
        }
        if (heightChanged || previousDoor != doorOpening || doorSideChanged) {
            landingDoors.forEach { (index, part) ->
                // A shared opening side does not make the other floors open.
                val opening = if (kotlin.math.abs(settings.floors[index].y - y) <= .02) doorOpening else 0.0
                val offset = opening * (part.doorTravel ?: 0.0)
                moved = part.display.teleport(origin(settings.floors[index].y).add(
                    part.x + part.doorShiftX * offset, part.y, part.z + part.doorShiftZ * offset,
                )) && moved
            }
        }
        if (!heightChanged) {
            openDoorSide = nextOpenDoorSide
            return moved
        }
        seats.forEachIndexed { index, seat ->
            val (x, z) = seatOffsets[index]
            moved = seat.teleport(origin(y).add(x, seatYOffset, z)) && moved
        }
        cabinHitbox?.let {
            moved = it.teleport(origin(y).add(0.0, settings.cabinHitboxBottomOffset(), 0.0)) && moved
        }
        val top = settings.floors.first().y + 3
        chain.forEachIndexed { index, link ->
            val bottom = y + 2.83 + index
            val length = (top - bottom).coerceIn(0.0, 1.0)
            moved = link.teleport(Location(world, settings.x - .5, bottom, settings.z - .5)) && moved
            link.transformation = transform(0.0, 0.0, 0.0, 1.0, length, 1.0)
        }
        previousY = y
        openDoorSide = nextOpenDoorSide
        return moved
    }

    fun label(index: Int, text: Component) { labels[index].text(text) }
    private fun origin(y: Double) = Location(world, settings.x, y, settings.z)
    private fun guard(x: Double, z: Double, span: Double, vertical: Boolean, openingSide: MineLiftDoorSide? = null) {
        val material = settings.theme.railMaterial
        if (vertical) {
            block(x, .45, z, .08, .12, span, material, openingSide, doorShiftY = 1.0)
            block(x, 1.15, z, .08, .12, span, material, openingSide, doorShiftY = 1.0)
        } else {
            block(x, .45, z, span, .12, .08, material, openingSide, doorShiftY = 1.0)
            block(x, 1.15, z, span, .12, .08, material, openingSide, doorShiftY = 1.0)
        }
    }

    private fun casinoFloorAndCeiling() {
        val halfX = settings.width / 2
        val halfZ = settings.depth / 2
        val columns = ceil(settings.width / MAX_CASINO_PANEL_WIDTH).toInt()
        val rows = ceil(settings.depth / MAX_CASINO_PANEL_WIDTH).toInt()
        val tileX = settings.width / columns
        val tileZ = settings.depth / rows
        val inlay = columns > 2 && rows > 2
        val borderWidth = .04
        // At the lowest stop the native support ends at cabin Y. The finished
        // floor stands 2 cm above it, matching the seats, without lifting walls.
        // Its outer edge meets the inner wall faces so tile seams stay clear of wall seams.
        val floorThickness = .24
        repeat(columns) { column -> repeat(rows) { row ->
            val x = -halfX + column * tileX
            val z = -halfZ + row * tileZ
            val center = column in 1 until columns - 1 && row in 1 until rows - 1
            val left = if (column == 0) WALL_THICKNESS else if (center && column == 1) borderWidth else 0.0
            val right = if (column == columns - 1) WALL_THICKNESS else if (center && column == columns - 2) borderWidth else 0.0
            val front = if (row == 0) WALL_THICKNESS else if (center && row == 1) borderWidth else 0.0
            val back = if (row == rows - 1) WALL_THICKNESS else if (center && row == rows - 2) borderWidth else 0.0
            block(x + left, -.22, z + front, tileX - left - right, floorThickness, tileZ - front - back,
                if (!center) Material.DARK_OAK_PLANKS
                else if ((column + row) % 2 == 0) settings.theme.floorMaterial else Material.RED_CONCRETE)
            block(x, settings.theme.cabinWallHeight, z, tileX, .18, tileZ,
                if (center) Material.DARK_PRISMARINE else settings.theme.ceilingMaterial)
        } }
        if (inlay) {
            val innerX = halfX - tileX
            val innerZ = halfZ - tileZ
            for (x in listOf(-innerX, innerX - borderWidth))
                block(x, -.22, -innerZ, borderWidth, floorThickness, innerZ * 2, Material.GOLD_BLOCK)
            for (z in listOf(-innerZ, innerZ - borderWidth))
                block(-innerX + borderWidth, -.22, z, innerX * 2 - 2 * borderWidth, floorThickness, borderWidth, Material.GOLD_BLOCK)
            // The ceiling echoes the floor border, with the teal field recessed
            // above a shallow brass coffer instead of four unrelated light pads.
            val rim = .055
            for (x in listOf(-innerX, innerX - rim))
                block(x, 2.58, -innerZ, rim, .07, innerZ * 2, Material.GOLD_BLOCK)
            for (z in listOf(-innerZ, innerZ - rim))
                block(-innerX + rim, 2.58, z, innerX * 2 - 2 * rim, .07, rim, Material.GOLD_BLOCK)
        }
        for (x in listOf(-halfX, halfX - WALL_THICKNESS)) for (z in listOf(-halfZ, halfZ - WALL_THICKNESS)) {
            block(x, 0.0, z, WALL_THICKNESS, settings.theme.cabinWallHeight, WALL_THICKNESS, Material.GOLD_BLOCK)
        }
        block(-.36, 2.58, -.36, .72, .07, .72, Material.GOLD_BLOCK)
        block(-.26, 2.52, -.26, .52, .06, .52, Material.OCHRE_FROGLIGHT)
    }

    private fun casinoDecoration(doorSides: Set<MineLiftDoorSide>) {
        val opposite = MineLiftDoorSide.entries[(settings.openingSide(0).ordinal + 2) % MineLiftDoorSide.entries.size]
        val back = opposite.takeIf { it !in doorSides }
            ?: MineLiftDoorSide.entries.firstOrNull { it !in doorSides } ?: return
        val span = if (back == MineLiftDoorSide.NORTH || back == MineLiftDoorSide.SOUTH) settings.width else settings.depth
        val scale = minOf(1.0, (span - .3) / 3.2)
        fun detail(along: Double, y: Double, width: Double, height: Double, depth: Double, material: Material, inset: Double) =
            casinoSurface(back, along * scale, y, width * scale, height, depth, material, inset)

        // A raised, stepped crest is the focal point seen through the doorway.
        // Each layer has real depth; the gold silhouette never shares a face
        // with its teal backing or the burgundy central stone.
        detail(-.85, 1.2, 1.7, 1.2, .04, Material.GOLD_BLOCK, WALL_THICKNESS)
        detail(-.78, 1.27, 1.56, 1.06, .02, Material.DARK_PRISMARINE, .12)
        detail(-.64, 1.36, 1.28, .05, .03, Material.GOLD_BLOCK, .14)
        listOf(.28, .44, .60, .77, .60, .44, .28).forEachIndexed { index, height ->
            detail(-.62 + index * .19, 1.41, .10, height, .03, Material.GOLD_BLOCK, .14)
        }
        detail(-.09, 1.36, .18, .13, .025, Material.RED_CONCRETE, .17)

        // Paired ivory sconces frame the crest; no floating text or dead glass.
        for (along in listOf(-1.47, 1.47)) {
            detail(along - .14, 1.42, .28, .82, .045, Material.GOLD_BLOCK, WALL_THICKNESS)
            detail(along - .08, 1.52, .16, .62, .055, Material.OCHRE_FROGLIGHT, .125)
        }
        MineLiftDoorSide.entries.filter { it != back && it !in doorSides }.forEach { side ->
            val wallSpan = if (side == MineLiftDoorSide.NORTH || side == MineLiftDoorSide.SOUTH) settings.width else settings.depth
            for (along in listOf(-wallSpan / 4, wallSpan / 4)) {
                casinoSurface(side, along - .015, .77, .03, settings.theme.cabinWallHeight - .77, .025,
                    Material.GOLD_BLOCK, inset = WALL_THICKNESS)
            }
        }
    }

    private fun casinoWall(side: MineLiftDoorSide, openingSide: MineLiftDoorSide?) {
        val span = if (side == MineLiftDoorSide.NORTH || side == MineLiftDoorSide.SOUTH) settings.width else settings.depth
        if (openingSide != null) {
            val leafWidth = minOf(settings.theme.doorTravel, span / 2 - WALL_THICKNESS)
            val sideWidth = span / 2 - leafWidth - WALL_THICKNESS
            if (sideWidth > 0.0) {
                casinoPanels(side, -span / 2 + WALL_THICKNESS, sideWidth)
                casinoPanels(side, leafWidth, sideWidth)
            }
            casinoPanels(side, -leafWidth, leafWidth, -1.0, leafWidth)
            casinoPanels(side, 0.0, leafWidth, 1.0, leafWidth)
        } else {
            casinoPanels(side, -span / 2 + WALL_THICKNESS, span - 2 * WALL_THICKNESS)
            // A real projecting handrail has its own depth, clear of the panel faces.
            casinoSurface(side, -span / 2 + .32, 1.05, span - .64, .055, .055,
                Material.GOLD_BLOCK, inset = WALL_THICKNESS + .04)
        }
    }

    /** Fixed landing leaves live outside the cabin sweep and reuse its wood/brass panels. */
    private fun casinoLandingDoors(index: Int) {
        val side = settings.openingSide(index)
        val horizontal = side == MineLiftDoorSide.NORTH || side == MineLiftDoorSide.SOUTH
        val span = if (horizontal) settings.width else settings.depth
        val leafWidth = minOf(1.5, span / 2 - WALL_THICKNESS)
        val first = parts.size
        casinoPanels(side, -leafWidth, leafWidth, -1.0, leafWidth)
        casinoPanels(side, 0.0, leafWidth, 1.0, leafWidth)
        val created = parts.subList(first, parts.size).toList()
        parts.subList(first, parts.size).clear()
        val outward = if (side == MineLiftDoorSide.NORTH || side == MineLiftDoorSide.WEST) -.26 else .26
        created.forEach { part ->
            val landing = part.copy(x = part.x + if(horizontal) 0.0 else outward,
                z = part.z + if(horizontal) outward else 0.0)
            landing.display.teleport(origin(settings.floors[index].y).add(landing.x, landing.y, landing.z))
            landingDoors += LandingPart(index, landing)
        }
    }

    private fun casinoPanels(
        side: MineLiftDoorSide,
        start: Double,
        width: Double,
        travelSign: Double = 0.0,
        travel: Double? = null,
    ) {
        val doorSide = side.takeIf { travel != null }
        // Leaves retract BEHIND the fixed wall: the full .08 wall thickness plus
        // .06 clearance separates their faces at every intermediate/open pose.
        val inset = if (doorSide == null) 0.0 else -CASINO_DOOR_TRACK_OFFSET
        val columns = ceil(width / MAX_CASINO_PANEL_WIDTH).toInt().coerceAtLeast(1)
        val tileWidth = width / columns
        repeat(columns) { column ->
            val along = start + column * tileWidth
            casinoSurface(side, along, 0.0, tileWidth, .72, WALL_THICKNESS,
                Material.DARK_OAK_PLANKS, inset, doorSide, travelSign, travel)
            casinoSurface(side, along, .72, tileWidth, .05, WALL_THICKNESS,
                Material.GOLD_BLOCK, inset, doorSide, travelSign, travel)
            val upperHeight = (settings.theme.cabinWallHeight - .77) / 2
            repeat(2) { row ->
                casinoSurface(side, along, .77 + row * upperHeight, tileWidth, upperHeight, WALL_THICKNESS,
                    Material.STRIPPED_DARK_OAK_WOOD, inset, doorSide, travelSign, travel)
            }
        }
        if (doorSide != null) {
            val handle = if (travelSign < 0) start + width - .14 else start + .08
            for (handleInset in listOf(inset - .035, inset + WALL_THICKNESS)) {
                casinoSurface(side, handle, 1.05, .06, .36, .035,
                    Material.GOLD_BLOCK, handleInset, doorSide, travelSign, travel)
            }
        }
    }

    /** A wall-local panel; inset is measured inward from the cabin's outer face. */
    private fun casinoSurface(
        side: MineLiftDoorSide,
        along: Double,
        y: Double,
        width: Double,
        height: Double,
        thickness: Double,
        material: Material,
        inset: Double = 0.0,
        doorSide: MineLiftDoorSide? = null,
        travelSign: Double = 0.0,
        travel: Double? = null,
    ) {
        val horizontal = side == MineLiftDoorSide.NORTH || side == MineLiftDoorSide.SOUTH
        val halfNormal = if (horizontal) settings.depth / 2 else settings.width / 2
        val normal = if (side == MineLiftDoorSide.NORTH || side == MineLiftDoorSide.WEST) {
            -halfNormal + inset
        } else halfNormal - inset - thickness
        block(
            if (horizontal) along else normal, y, if (horizontal) normal else along,
            if (horizontal) width else thickness, height, if (horizontal) thickness else width,
            material, doorSide, doorShiftX = if (horizontal) travelSign else 0.0,
            doorShiftZ = if (horizontal) 0.0 else travelSign, doorTravel = travel,
        )
    }

    private fun spawnCabinHitbox(y: Double) {
        cabinHitbox = world.spawn(origin(y).add(0.0, settings.cabinHitboxBottomOffset(), 0.0), Interaction::class.java) {
            mark(it)
            it.interactionWidth = settings.cabinHitboxWidth().toFloat()
            it.interactionHeight = settings.cabinHitboxHeight().toFloat()
            it.isResponsive = true
        }.also(entities::add)
    }

    private fun block(
        x: Double,
        y: Double,
        z: Double,
        sx: Double,
        sy: Double,
        sz: Double,
        material: Material,
        doorSide: MineLiftDoorSide? = null,
        doorShiftX: Double = 0.0,
        doorShiftY: Double = 0.0,
        doorShiftZ: Double = 0.0,
        doorTravel: Double? = null,
    ) {
        val entity = display(origin(settings.floors.first().y).add(x, y, z), material)
        entity.transformation = transform(0.0, 0.0, 0.0, sx, sy, sz)
        parts += Part(entity, x, y, z, doorSide, doorShiftX, doorShiftY, doorShiftZ, doorTravel)
    }

    private fun display(at: Location, material: Material): BlockDisplay = world.spawn(at, BlockDisplay::class.java) {
        mark(it); it.block = material.createBlockData(); it.teleportDuration = 1
        it.interpolationDuration = 1; it.viewRange = 1.5f
    }.also(entities::add)

    private fun mark(entity: Entity) {
        entity.isPersistent = false
        entity.isInvulnerable = true
        entity.setGravity(false)
        entity.persistentDataContainer.set(key, PersistentDataType.BYTE, 1)
    }

    override fun close() {
        entities.asReversed().forEach(Entity::remove)
        entities.clear(); parts.clear(); landingDoors.clear(); seats.clear(); seatOffsets.clear(); chain.clear(); panels.clear(); cabinHitbox = null; labels.clear()
        openDoorSide = null
    }

    private fun seatAxis(span: Double): List<Double> {
        val count = floor((span - 1.0) / 1.1).toInt().coerceIn(2, 5)
        val extent = (span / 2 - .65).coerceAtLeast(.45)
        return List(count) { index -> -extent + extent * 2 * index / (count - 1) }
    }

    private fun transform(x: Double, y: Double, z: Double, sx: Double, sy: Double, sz: Double) = Transformation(
        Vector3f(x.toFloat(), y.toFloat(), z.toFloat()), Quaternionf(), Vector3f(sx.toFloat(), sy.toFloat(), sz.toFloat()), Quaternionf(),
    )

    private companion object {
        const val WALL_THICKNESS = .08
        const val CASINO_DOOR_TRACK_OFFSET = .14
        const val MAX_CASINO_PANEL_WIDTH = 1.1
    }
}
