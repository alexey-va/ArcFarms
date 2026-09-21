package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Color
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays

/** Client-only presentation for transient factory experiment objects. */
internal interface MineFactoryExperimentVisuals {
    fun render(
        scope: String,
        id: String,
        at: Location,
        model: String,
        scale: Float = 1f,
        yaw: Float = 0f,
        pitch: Float = 0f,
        glowing: Boolean = false,
    )

    fun remove(scope: String, id: String)
    fun clear(scope: String)
    fun cleanup()
}

/** Packet displays are deliberately scoped so transient objects cannot leak between scenes. */
internal class PacketMineFactoryExperimentVisuals(private val plugin: Plugin) : MineFactoryExperimentVisuals {
    private data class Key(val scope: String, val id: String)
    private data class Entry(val model: String, val parts: List<MineDisplayBlueprints.Part>, val displays: List<PacketBlockDisplay>)

    private val entries = linkedMapOf<Key, Entry>()
    private var renderer: PaperPacketDisplays? = null

    override fun render(
        scope: String,
        id: String,
        at: Location,
        model: String,
        scale: Float,
        yaw: Float,
        pitch: Float,
        glowing: Boolean,
    ) {
        require(scope.isNotBlank()) { "Visual scope is required" }
        require(id.isNotBlank()) { "Visual id is required" }
        require(scale.isFinite() && scale > 0f) { "Visual scale is invalid" }
        require(yaw.isFinite() && pitch.isFinite()) { "Visual rotation is invalid" }
        requireNotNull(at.world) { "A factory visual must belong to a world" }

        val anchor = at.clone().also { it.yaw = 0f; it.pitch = 0f }
        val key = Key(scope, id)
        val existing = entries[key]
        val entry = if (existing == null || existing.model != model) {
            entries.remove(key)?.displays?.forEach(PacketBlockDisplay::remove)
            spawn(anchor, model, MineFactoryExperimentVisualGeometry.parts(model)).also { entries[key] = it }
        } else {
            existing
        }
        val worldRotation = MineFactoryExperimentVisualGeometry.orientation(yaw, pitch)
        entry.displays.zip(entry.parts).forEach { (display, part) ->
            val desired = MineFactoryExperimentVisualGeometry.transformation(part, scale, worldRotation)
            if (display.transformation != desired) {
                display.interpolationDelay = 0
                display.transformation = desired
            }
            display.isGlowing = glowing
            display.glowColorOverride = if (glowing) GLOW_COLOR else null
            if (display.location != anchor) display.teleport(anchor)
        }
    }

    override fun remove(scope: String, id: String) {
        entries.remove(Key(scope, id))?.displays?.forEach(PacketBlockDisplay::remove)
    }

    override fun clear(scope: String) {
        entries.keys.filter { it.scope == scope }.toList().forEach { key ->
            entries.remove(key)?.displays?.forEach(PacketBlockDisplay::remove)
        }
    }

    override fun cleanup() {
        entries.values.toList().flatMap { it.displays }.forEach(PacketBlockDisplay::remove)
        entries.clear()
        renderer?.close()
        renderer = null
    }

    private fun spawn(at: Location, model: String, parts: List<MineDisplayBlueprints.Part>): Entry {
        val owner = renderer ?: PaperPacketDisplays(plugin).also { renderer = it }
        val displays = mutableListOf<PacketBlockDisplay>()
        return try {
            parts.forEach { part ->
                displays += owner.spawnBlock(at, part.material.createBlockData()).apply {
                    brightness = MineDisplayLighting.brightness(part.material)
                    viewRange = 2f
                    interpolationDuration = 2
                    teleportDuration = 2
                }
            }
            Entry(model, parts, displays)
        } catch (failure: Throwable) {
            displays.forEach(PacketBlockDisplay::remove)
            throw failure
        }
    }

    private companion object {
        val GLOW_COLOR: Color = Color.fromRGB(255, 187, 77)
    }
}

/** Pure shape selection and transform math used by packet rendering and tests. */
internal object MineFactoryExperimentVisualGeometry {
    private const val MOULD_STAND_PARTS = 4
    private const val NOZZLE_PEDESTAL_PARTS = 2

    fun parts(model: String): List<MineDisplayBlueprints.Part> = when (model) {
        "ore_piece" -> listOf(
            MineDisplayBlueprints.Part(Material.RAW_IRON_BLOCK, Vector3f(), Vector3f(.3f)),
        )
        "mould_gear_piece" -> carriedMould("factory_mould_gear")
        "mould_plate_piece" -> carriedMould("factory_mould_plate")
        "mould_rod_piece" -> carriedMould("factory_mould_rod")
        "mould_gear_sample" -> carriedMould("factory_mould_gear").map { it.copy(material = Material.CYAN_STAINED_GLASS) }
        "mould_plate_sample" -> carriedMould("factory_mould_plate").map { it.copy(material = Material.CYAN_STAINED_GLASS) }
        "mould_rod_sample" -> carriedMould("factory_mould_rod").map { it.copy(material = Material.CYAN_STAINED_GLASS) }
        "hose_nozzle_held" -> MineFactoryExperimentModels.model("factory_hose_nozzle")
            .drop(NOZZLE_PEDESTAL_PARTS).map { it.copy(center = Vector3f(it.center).add(0f, -.4f, 0f)) }
        else -> MineDisplayBlueprints.model(model)
    }

    fun orientation(yaw: Float, pitch: Float): Quaternionf {
        require(yaw.isFinite() && pitch.isFinite()) { "Visual rotation is invalid" }
        return Quaternionf()
            .rotateY(-Math.toRadians(yaw.toDouble()).toFloat())
            .rotateX(Math.toRadians(pitch.toDouble()).toFloat())
    }

    fun transformation(
        part: MineDisplayBlueprints.Part,
        scale: Float,
        worldRotation: Quaternionf,
    ): Transformation {
        require(scale.isFinite() && scale > 0f) { "Visual scale is invalid" }
        val rotation = Quaternionf(worldRotation).mul(MineDisplayBlueprints.rotation(part, 0f))
        val size = Vector3f(part.size).mul(scale)
        val center = worldRotation.transform(Vector3f(MineDisplayBlueprints.center(part, 0f)).mul(scale))
        val corner = rotation.transform(Vector3f(size).mul(-.5f)).add(center)
        return Transformation(corner, rotation, size, Quaternionf())
    }

    private fun carriedMould(model: String): List<MineDisplayBlueprints.Part> {
        val source = MineFactoryExperimentModels.model(model).drop(MOULD_STAND_PARTS)
        if (source.isEmpty()) return source
        val minY = source.minOf { it.center.y - it.size.y / 2f }
        val maxY = source.maxOf { it.center.y + it.size.y / 2f }
        val centerY = (minY + maxY) / 2f
        return source.map { part ->
            part.copy(center = Vector3f(part.center).add(0f, -centerY, 0f))
        }
    }
}
