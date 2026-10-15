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
import kotlin.math.ceil
import kotlin.math.floor

/** Bounded vanilla display scene in the surveyed shaft. Interaction seats scale with the cabin footprint. */
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
    private val entities = mutableListOf<Entity>()
    private val chain = mutableListOf<BlockDisplay>()
    val seats = mutableListOf<Interaction>()
    private val seatOffsets = mutableListOf<Pair<Double, Double>>()
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
        chunk.entities.filter { owns(it) && it !in entities }.forEach(Entity::remove)
    }

    /** BlockDisplay is visual only; this padded interaction AABB is the click target for the visible cabin. */
    fun ownsCabinHitbox(entity: Entity): Boolean = entity.uniqueId == cabinHitbox?.uniqueId

    fun ownsCabinInteraction(entity: Entity): Boolean = ownsCabinHitbox(entity)

    fun isDoorOpenAt(index: Int): Boolean = index in settings.floors.indices &&
        doorOpening == 1.0 && openDoorSide == settings.openingSide(index)

    fun spawn(y: Double, floorText: (Int) -> Component) {
        val halfX = settings.width / 2
        val halfZ = settings.depth / 2
        val theme = settings.theme
        block(-halfX, -.22, -halfZ, settings.width, .22, settings.depth, theme.floorMaterial)
        block(-halfX, theme.cabinWallHeight, -halfZ, settings.width, .18, settings.depth, theme.ceilingMaterial)
        for (x in listOf(-halfX, halfX - .16)) for (z in listOf(-halfZ, halfZ - .16)) {
            block(x, 0.0, z, .16, theme.cabinWallHeight, .16, theme.cornerMaterial)
        }
        val doorSides = settings.floors.indices.map(settings::openingSide).toSet()
        if (theme.enclosed) {
            MineLiftDoorSide.entries.forEach { side ->
                casinoWall(side, side.takeIf { it in doorSides })
            }
        } else {
            MineLiftDoorSide.entries.forEach { side ->
                val openingSide = side.takeIf { it in doorSides }
                when (side) {
                    MineLiftDoorSide.WEST -> guard(-halfX, -halfZ, settings.depth, vertical = true, openingSide)
                    MineLiftDoorSide.EAST -> guard(halfX - .08, -halfZ, settings.depth, vertical = true, openingSide)
                    MineLiftDoorSide.NORTH -> guard(-halfX, -halfZ, settings.width, vertical = false, openingSide)
                    MineLiftDoorSide.SOUTH -> guard(-halfX, halfZ - .08, settings.width, vertical = false, openingSide)
                }
            }
        }
        spawnCabinHitbox(y)
        block(-.2, 2.3, -.2, .4, .35, .4, theme.lightMaterial)
        parts.forEach { it.display.teleport(origin(y).add(it.x, it.y, it.z)) }
        for (x in seatAxis(settings.width)) for (z in seatAxis(settings.depth)) {
            seatOffsets += x to z
            seats += world.spawn(origin(y).add(x, .02, z), Interaction::class.java) {
                mark(it); it.interactionWidth = .7f; it.interactionHeight = .01f; it.isResponsive = true
            }.also(entities::add)
        }
        val anchorY = settings.floors.first().y + 3
        repeat(kotlin.math.ceil(anchorY - settings.floors.last().y).toInt()) {
            chain += display(origin(anchorY), theme.chainMaterial).also { entity ->
                entity.transformation = transform(.0, .0, .0, 1.0, .0, 1.0)
            }
        }
        settings.floors.forEachIndexed { index, floor ->
            val panel = world.spawn(floor.panel.location(world), Interaction::class.java) {
                mark(it); it.interactionWidth = .85f; it.interactionHeight = 1.3f; it.isResponsive = true
            }.also(entities::add)
            panels[panel.uniqueId] = index
            display(floor.panel.location(world).add(-.3, .0, -.3), theme.panelMaterial).apply {
                transformation = transform(0.0, 0.0, 0.0, .6, .75, .6)
            }
            labels += world.spawn(floor.panel.location(world).add(0.0, 1.6, 0.0), TextDisplay::class.java) {
                mark(it); it.text(floorText(index)); it.billboard = Display.Billboard.VERTICAL
                it.isShadowed = true; it.viewRange = .4f
                it.backgroundColor = org.bukkit.Color.fromARGB(150, 25, 21, 17)
            }.also(entities::add)
        }
        move(y, open = true)
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
        if (!heightChanged) {
            openDoorSide = nextOpenDoorSide
            return moved
        }
        seats.forEachIndexed { index, seat ->
            val (x, z) = seatOffsets[index]
            moved = seat.teleport(origin(y).add(x, .02, z)) && moved
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

    private fun casinoWall(side: MineLiftDoorSide, openingSide: MineLiftDoorSide?) {
        if (openingSide != null) {
            casinoDoorWall(side)
            return
        }

        val halfX = settings.width / 2
        val halfZ = settings.depth / 2
        val wallMaterial = requireNotNull(settings.theme.wallMaterial)
        if (side == MineLiftDoorSide.EAST || side == MineLiftDoorSide.WEST) {
            casinoSideWindowWall(side, wallMaterial, requireNotNull(settings.theme.windowMaterial))
        } else {
            when (side) {
                MineLiftDoorSide.WEST -> block(-halfX, 0.0, -halfZ, .08, settings.theme.cabinWallHeight, settings.depth, wallMaterial)
                MineLiftDoorSide.EAST -> block(halfX - .08, 0.0, -halfZ, .08, settings.theme.cabinWallHeight, settings.depth, wallMaterial)
                MineLiftDoorSide.NORTH -> block(-halfX, 0.0, -halfZ, settings.width, settings.theme.cabinWallHeight, .08, wallMaterial)
                MineLiftDoorSide.SOUTH -> block(-halfX, 0.0, halfZ - .08, settings.width, settings.theme.cabinWallHeight, .08, wallMaterial)
            }
        }
    }

    private fun casinoDoorWall(side: MineLiftDoorSide) {
        val span = if (side == MineLiftDoorSide.NORTH || side == MineLiftDoorSide.SOUTH) settings.width else settings.depth
        val halfSpan = span / 2
        val leafWidth = minOf(settings.theme.doorTravel, span / 2)
        val sideWidth = (span - 2 * leafWidth) / 2
        val sideTiles = ceil(sideWidth / MAX_CASINO_PANEL_WIDTH).toInt().coerceAtLeast(1)
        val sideTileWidth = sideWidth / sideTiles

        if (sideWidth > 0.0) repeat(sideTiles) { index ->
            val tileStart = -halfSpan + index * sideTileWidth
            casinoDoorPanel(side, tileStart, sideTileWidth)
        }
        casinoDoorPanel(side, -leafWidth, leafWidth, openingSide = side, travelSign = -1.0, doorTravel = leafWidth)
        casinoDoorPanel(side, 0.0, leafWidth, openingSide = side, travelSign = 1.0, doorTravel = leafWidth)
        if (sideWidth > 0.0) repeat(sideTiles) { index ->
            val tileStart = leafWidth + index * sideTileWidth
            casinoDoorPanel(side, tileStart, sideTileWidth)
        }
    }

    private fun casinoDoorPanel(
        side: MineLiftDoorSide,
        alongStart: Double,
        alongWidth: Double,
        openingSide: MineLiftDoorSide? = null,
        travelSign: Double = 0.0,
        doorTravel: Double? = null,
    ) {
        val halfX = settings.width / 2
        val halfZ = settings.depth / 2
        val horizontal = side == MineLiftDoorSide.NORTH || side == MineLiftDoorSide.SOUTH
        val normalX = when (side) {
            MineLiftDoorSide.WEST -> -halfX
            MineLiftDoorSide.EAST -> halfX - WALL_THICKNESS
            else -> 0.0
        }
        val normalZ = when (side) {
            MineLiftDoorSide.NORTH -> -halfZ
            MineLiftDoorSide.SOUTH -> halfZ - WALL_THICKNESS
            else -> 0.0
        }
        val outwardX = when (side) {
            MineLiftDoorSide.WEST -> -DOOR_FACE_OFFSET
            MineLiftDoorSide.EAST -> DOOR_FACE_OFFSET
            else -> 0.0
        }
        val outwardZ = when (side) {
            MineLiftDoorSide.NORTH -> -DOOR_FACE_OFFSET
            MineLiftDoorSide.SOUTH -> DOOR_FACE_OFFSET
            else -> 0.0
        }
        val x = if (horizontal) alongStart else normalX + outwardX
        val z = if (horizontal) normalZ + outwardZ else alongStart
        val sx = if (horizontal) alongWidth else WALL_THICKNESS
        val sz = if (horizontal) WALL_THICKNESS else alongWidth
        val moveX = if (horizontal) travelSign else 0.0
        val moveZ = if (horizontal) 0.0 else travelSign
        val doorSide = openingSide
        val upperMaterial = requireNotNull(settings.theme.wallMaterial)
        val lowerMaterial = settings.theme.ceilingMaterial
        val brassMaterial = settings.theme.railMaterial
        val lowerHeight = .62
        val railHeight = .12

        fun panel(y: Double, height: Double, material: Material) = block(
            x, y, z, sx, height, sz, material, doorSide,
            doorShiftX = moveX, doorShiftZ = moveZ, doorTravel = doorTravel,
        )

        panel(0.0, lowerHeight, lowerMaterial)
        panel(lowerHeight, railHeight, brassMaterial)
        panel(lowerHeight + railHeight, settings.theme.cabinWallHeight - lowerHeight - railHeight, upperMaterial)
        if (openingSide != null) {
            val handleWidth = .06
            val handleStart = alongStart + (alongWidth - handleWidth) / 2
            val handleX = if (horizontal) handleStart else x
            val handleZ = if (horizontal) z else handleStart
            val handleSizeX = if (horizontal) handleWidth else WALL_THICKNESS
            val handleSizeZ = if (horizontal) WALL_THICKNESS else handleWidth
            block(
                handleX, 1.2, handleZ, handleSizeX, .18, handleSizeZ,
                settings.theme.doorMaterial, doorSide, doorShiftX = moveX, doorShiftZ = moveZ, doorTravel = doorTravel,
            )
        }
    }

    private fun casinoSideWindowWall(side: MineLiftDoorSide, wall: Material, glass: Material) {
        val halfX = settings.width / 2
        val halfZ = settings.depth / 2
        val wallX = if (side == MineLiftDoorSide.WEST) -halfX else halfX - WALL_THICKNESS
        val span = settings.depth
        val windowWidth = minOf(MAX_CASINO_PANEL_WIDTH, span)
        val windowStart = -windowWidth / 2
        val sideWidth = (span - windowWidth) / 2
        val windowBottom = .82
        val windowTop = 2.12
        val sidePanels = listOf(-halfZ to sideWidth, (windowStart + windowWidth) to sideWidth)
        sidePanels.filter { it.second > 0.0 }.forEach { (z, width) ->
            block(wallX, 0.0, z, WALL_THICKNESS, settings.theme.cabinWallHeight, width, wall)
        }
        block(wallX, 0.0, windowStart, WALL_THICKNESS, windowBottom, windowWidth, wall)
        block(wallX, windowTop, windowStart, WALL_THICKNESS, settings.theme.cabinWallHeight - windowTop, windowWidth, wall)
        block(wallX, windowBottom, windowStart, WALL_THICKNESS, windowTop - windowBottom, windowWidth, glass)
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
        entities.clear(); parts.clear(); seats.clear(); seatOffsets.clear(); chain.clear(); panels.clear(); cabinHitbox = null; labels.clear()
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
        const val DOOR_FACE_OFFSET = .01
        const val MAX_CASINO_PANEL_WIDTH = 1.1
    }
}
