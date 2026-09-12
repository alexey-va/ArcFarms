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
import kotlin.math.floor

/** Bounded vanilla display scene in the surveyed shaft. Interaction seats scale with the cabin footprint. */
internal class MineLiftScene(private val plugin: Plugin, private val settings: MineLiftSettings, private val world: World) : AutoCloseable {
    private data class Part(
        val display: BlockDisplay,
        val x: Double,
        val y: Double,
        val z: Double,
        val doorSide: MineLiftDoorSide? = null,
    )
    private val parts = mutableListOf<Part>()
    private val entities = mutableListOf<Entity>()
    private val chain = mutableListOf<BlockDisplay>()
    val seats = mutableListOf<Interaction>()
    private val seatOffsets = mutableListOf<Pair<Double, Double>>()
    val panels = mutableMapOf<UUID, Int>()
    private val entranceHitboxes = mutableMapOf<UUID, MineLiftDoorSide>()
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

    fun ownsEntrance(entity: Entity): Boolean = entity.uniqueId in entranceHitboxes

    internal fun entranceSides(): Map<UUID, MineLiftDoorSide> = entranceHitboxes.toMap()

    fun spawn(y: Double, floorText: (Int) -> Component) {
        val halfX = settings.width / 2
        val halfZ = settings.depth / 2
        block(-halfX, -.22, -halfZ, settings.width, .22, settings.depth, Material.SPRUCE_PLANKS)
        block(-halfX, 2.65, -halfZ, settings.width, .18, settings.depth, Material.DARK_OAK_PLANKS)
        for (x in listOf(-halfX, halfX - .16)) for (z in listOf(-halfZ, halfZ - .16)) {
            block(x, 0.0, z, .16, 2.65, .16, Material.STRIPPED_SPRUCE_LOG)
        }
        val doorSides = settings.floors.indices.map(settings::openingSide).toSet()
        MineLiftDoorSide.entries.forEach { side ->
            if (side in doorSides) {
                when (side) {
                    MineLiftDoorSide.WEST -> block(-halfX, .45, -halfZ, .08, .85, settings.depth, Material.COPPER_GRATE, side)
                    MineLiftDoorSide.EAST -> block(halfX - .08, .45, -halfZ, .08, .85, settings.depth, Material.COPPER_GRATE, side)
                    MineLiftDoorSide.NORTH -> block(-halfX, .45, -halfZ, settings.width, .85, .08, Material.COPPER_GRATE, side)
                    MineLiftDoorSide.SOUTH -> block(-halfX, .45, halfZ - .08, settings.width, .85, .08, Material.COPPER_GRATE, side)
                }
                spawnEntrance(y, side, halfX, halfZ)
            } else {
                when (side) {
                    MineLiftDoorSide.WEST -> guard(-halfX, -halfZ, settings.depth, vertical = true)
                    MineLiftDoorSide.EAST -> guard(halfX - .08, -halfZ, settings.depth, vertical = true)
                    MineLiftDoorSide.NORTH -> guard(-halfX, -halfZ, settings.width, vertical = false)
                    MineLiftDoorSide.SOUTH -> guard(-halfX, halfZ - .08, settings.width, vertical = false)
                }
            }
        }
        block(-.2, 2.3, -.2, .4, .35, .4, Material.LANTERN)
        parts.forEach { it.display.teleport(origin(y).add(it.x, it.y, it.z)) }
        for (x in seatAxis(settings.width)) for (z in seatAxis(settings.depth)) {
            seatOffsets += x to z
            seats += world.spawn(origin(y).add(x, .02, z), Interaction::class.java) {
                mark(it); it.interactionWidth = .7f; it.interactionHeight = .01f; it.isResponsive = true
            }.also(entities::add)
        }
        val anchorY = settings.floors.first().y + 3
        repeat(kotlin.math.ceil(anchorY - settings.floors.last().y).toInt()) {
            chain += display(origin(anchorY), Material.IRON_CHAIN).also { entity ->
                entity.transformation = transform(.0, .0, .0, 1.0, .0, 1.0)
            }
        }
        settings.floors.forEachIndexed { index, floor ->
            val panel = world.spawn(floor.panel.location(world), Interaction::class.java) {
                mark(it); it.interactionWidth = .85f; it.interactionHeight = 1.3f; it.isResponsive = true
            }.also(entities::add)
            panels[panel.uniqueId] = index
            display(floor.panel.location(world).add(-.3, .0, -.3), Material.CHISELED_COPPER).apply {
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
            val doorOffset = if (part.doorSide == nextOpenDoorSide) doorOpening * 1.6 else 0.0
            moved = part.display.teleport(origin(y).add(part.x, part.y + doorOffset, part.z)) && moved
        }
        if (!heightChanged) {
            openDoorSide = nextOpenDoorSide
            return moved
        }
        seats.forEachIndexed { index, seat ->
            val (x, z) = seatOffsets[index]
            moved = seat.teleport(origin(y).add(x, .02, z)) && moved
        }
        entranceHitboxes.forEach { (id, side) ->
            val entrance = world.getEntity(id) as? Interaction ?: return@forEach
            moved = entrance.teleport(entranceLocation(y, side)) && moved
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
    private fun guard(x: Double, z: Double, span: Double, vertical: Boolean) {
        if (vertical) {
            block(x, .45, z, .08, .12, span, Material.IRON_BLOCK)
            block(x, 1.15, z, .08, .12, span, Material.IRON_BLOCK)
        } else {
            block(x, .45, z, span, .12, .08, Material.IRON_BLOCK)
            block(x, 1.15, z, span, .12, .08, Material.IRON_BLOCK)
        }
    }

    private fun spawnEntrance(y: Double, side: MineLiftDoorSide, halfX: Double, halfZ: Double) {
        val span = if (side == MineLiftDoorSide.WEST || side == MineLiftDoorSide.EAST) settings.depth else settings.width
        val interaction = world.spawn(entranceLocation(y, side, halfX, halfZ), Interaction::class.java) {
            mark(it)
            it.interactionWidth = span.coerceIn(1.4, 5.8).toFloat()
            it.interactionHeight = 1.8f
            it.isResponsive = true
        }.also(entities::add)
        entranceHitboxes[interaction.uniqueId] = side
    }

    private fun entranceLocation(y: Double, side: MineLiftDoorSide): Location =
        entranceLocation(y, side, settings.width / 2, settings.depth / 2)

    private fun entranceLocation(y: Double, side: MineLiftDoorSide, halfX: Double, halfZ: Double): Location = when (side) {
        MineLiftDoorSide.WEST -> origin(y).add(-halfX - .12, .9, 0.0)
        MineLiftDoorSide.EAST -> origin(y).add(halfX + .12, .9, 0.0)
        MineLiftDoorSide.NORTH -> origin(y).add(0.0, .9, -halfZ - .12)
        MineLiftDoorSide.SOUTH -> origin(y).add(0.0, .9, halfZ + .12)
    }

    private fun block(x: Double, y: Double, z: Double, sx: Double, sy: Double, sz: Double, material: Material, doorSide: MineLiftDoorSide? = null) {
        val entity = display(origin(settings.floors.first().y).add(x, y, z), material)
        entity.isGlowing = true
        entity.glowColorOverride = org.bukkit.Color.fromRGB(0x75, 0xe6, 0xff)
        entity.transformation = transform(0.0, 0.0, 0.0, sx, sy, sz)
        parts += Part(entity, x, y, z, doorSide)
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
        entities.clear(); parts.clear(); seats.clear(); seatOffsets.clear(); chain.clear(); panels.clear(); entranceHitboxes.clear(); labels.clear()
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
}
