package ru.ruscrafting.farms.paper.farm.incident.greenhouse

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.Ageable
import org.bukkit.Particle
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayStyle
import java.util.UUID

internal enum class HellGreenhouseRole { SCENE, ENTRANCE, EXIT, VALVE, CROP }
internal data class HellGreenhouseIdentity(val zone: String, val sequence: Long, val placement: Long, val role: HellGreenhouseRole, val index: Int)

/** Plantation content only. The common underground expedition owns the surface entrance. */
internal class FarmHellGreenhouseScene(
    plugin: Plugin,
    private val locale: ArcFarmsLocale,
    private val text: FarmTextDisplayRenderer,
) {
    private val zoneKey = NamespacedKey(plugin, "hell_greenhouse_zone")
    private val sequenceKey = NamespacedKey(plugin, "hell_greenhouse_sequence")
    private val placementKey = NamespacedKey(plugin, "hell_greenhouse_placement")
    private val roleKey = NamespacedKey(plugin, "hell_greenhouse_role")
    private val indexKey = NamespacedKey(plugin, "hell_greenhouse_index")
    private data class PlotScene(val crops: List<UUID>, val valve: UUID, val label: UUID, val channel: List<UUID>)
    private data class Scene(val identity: HellGreenhouseIdentity, val entities: MutableSet<UUID>,
        val plots: List<PlotScene>, var signature: String = "")
    private val scenes = mutableMapOf<String, Scene>()

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)
    fun identity(entity: Entity): HellGreenhouseIdentity? {
        val data = entity.persistentDataContainer
        val zone = data.get(zoneKey, PersistentDataType.STRING) ?: return null
        val role = data.get(roleKey, PersistentDataType.STRING)?.let { raw -> HellGreenhouseRole.entries.firstOrNull { it.name == raw } } ?: return null
        return HellGreenhouseIdentity(zone, data.get(sequenceKey, PersistentDataType.LONG) ?: return null,
            data.get(placementKey, PersistentDataType.LONG) ?: return null, role,
            data.get(indexKey, PersistentDataType.INTEGER) ?: -1)
    }

    fun stamp(runtime: FarmRuntime, role: HellGreenhouseRole = HellGreenhouseRole.SCENE) =
        HellGreenhouseIdentity(runtime.settings.id, runtime.state.sequence, runtime.state.placementSequence, role, -1)

    fun center(runtime: FarmRuntime, state: FarmHellGreenhouseState): Location = Location(runtime.region.world,
        state.points.map { it.x }.average(), state.points.first().y, state.points.map { it.z }.average())

    fun render(runtime: FarmRuntime, players: List<Player>, tick: Int, particles: Boolean) {
        val plantation = runtime.state.hellGreenhouse ?: return
        val identity = stamp(runtime)
        var active = scenes[runtime.settings.id]
        if (active == null || active.identity != identity || active.entities.any { Bukkit.getEntity(it)?.isValid != true }) {
            clear(runtime.settings.id)
            active = build(runtime, plantation, identity)
            scenes[runtime.settings.id] = active
        }
        val signature = plantation.plots.toString()
        if (active.signature != signature) {
            active.plots.forEachIndexed { index, plotScene ->
                val plot = plantation.plots[index]
                val age = (plot.growthSeconds * 3 / FarmHellGreenhouseEngine.GROWTH_TARGET_SECONDS).coerceIn(0, 3)
                plotScene.crops.forEach { uuid ->
                    (Bukkit.getEntity(uuid) as? BlockDisplay)?.block = Material.NETHER_WART.createBlockData().apply {
                        (this as Ageable).age = age
                    }
                }
                (Bukkit.getEntity(plotScene.valve) as? BlockDisplay)?.block = Bukkit.createBlockData(
                    "minecraft:lever[face=floor,facing=north,powered=${plot.heating}]")
                (Bukkit.getEntity(plotScene.label) as? TextDisplay)?.let {
                    val status = locale.render(statusKey(plot), values = statusValues(locale, index, plot))
                    val action = locale.render(if (plot.heating) MessageKey.FARM_HELL_PLANTATION_CLOSE else MessageKey.FARM_HELL_PLANTATION_OPEN)
                    val phase = FarmHellGreenhouseEngine.phase(plot)
                    val message = if (phase == FarmHellPlantationPhase.READY || phase == FarmHellPlantationPhase.COOLING) status
                        else status.append(net.kyori.adventure.text.Component.newline()).append(action)
                    text.render(it, message,
                        FarmTextDisplayStyle(lineWidth = 260, viewRange = 0.8f))
                }
                plotScene.channel.forEach { uuid ->
                    (Bukkit.getEntity(uuid) as? BlockDisplay)?.block =
                        (if (plot.heating) Material.MAGMA_BLOCK else Material.POLISHED_BLACKSTONE_BRICKS).createBlockData()
                }
            }
            active.signature = signature
        }
        if (particles && tick % 10 == 0) {
            plantation.points.forEachIndexed { index, point ->
                val plot = plantation.plots[index]
                val phase = FarmHellGreenhouseEngine.phase(plot)
                val particle = when {
                    phase == FarmHellPlantationPhase.READY -> Particle.HAPPY_VILLAGER
                    plot.heating -> Particle.FLAME
                    phase == FarmHellPlantationPhase.COOLING -> Particle.CLOUD
                    else -> return@forEachIndexed
                }
                players.forEach { player ->
                    player.spawnParticle(particle, Location(player.world, point.x, point.y + 1.5, point.z),
                        5, 1.0, 0.2, 1.0, 0.01)
                }
            }
        }
    }

    private fun build(runtime: FarmRuntime, state: FarmHellGreenhouseState, stamp: HellGreenhouseIdentity): Scene {
        val center = center(runtime, state)
        val entities = linkedSetOf<UUID>()
        try {
            val plots = state.points.mapIndexed { index, point ->
                val at = Location(center.world, point.x, point.y, point.z)
                val crops = (-2..2).flatMap { x -> (-2..2).map { z ->
                    block(at.clone().add(x - 0.5, 1.0, z - 0.5), Material.NETHER_WART, stamp, entities).uniqueId
                } }
                interaction(at.clone().add(0.0, 1.0, 0.0), stamp.copy(role = HellGreenhouseRole.CROP, index = index), entities, 5f, 1.3f)
                val side = if (point.x < center.x) -1 else 1
                val valveAt = Location(center.world, center.x + side * 2.0, center.y, point.z)
                val valve = block(valveAt.clone().add(-0.5, 0.0, -0.5), Material.LEVER, stamp, entities)
                interaction(valveAt, stamp.copy(role = HellGreenhouseRole.VALVE, index = index), entities, 1.0f, 1.5f)
                label(valveAt.clone().add(0.0, 1.3, 0.0), MessageKey.FARM_HELL_PLANTATION_VALVE, stamp, entities,
                    mapOf("point" to locale.text(index + 1)))
                val label = label(at.clone().add(0.0, 2.7, 0.0), MessageKey.FARM_HELL_PLANTATION_COLD, stamp, entities,
                    mapOf("point" to locale.text(index + 1)))
                val channel = (2..3).map { distance ->
                    block(Location(center.world, center.x + side * distance - 0.5, center.y - 0.98, point.z - 0.5),
                        Material.POLISHED_BLACKSTONE_BRICKS, stamp, entities).uniqueId
                }
                PlotScene(crops, valve.uniqueId, label.uniqueId, channel)
            }
            val exit = center.clone().add(0.0, 0.0, 10.0)
            interaction(exit, stamp.copy(role = HellGreenhouseRole.EXIT), entities, 1.8f, 2.5f)
            label(exit.clone().add(0.0, 2.5, 0.0), MessageKey.FARM_HELL_GREENHOUSE_EXIT, stamp, entities)
            label(center.clone().add(0.0, 3.0, -9.0), MessageKey.FARM_HELL_PLANTATION_GUIDE, stamp, entities)
            return Scene(stamp, entities, plots)
        } catch (failure: Exception) {
            entities.forEach { Bukkit.getEntity(it)?.remove() }
            throw failure
        }
    }

    private fun interaction(at: Location, identity: HellGreenhouseIdentity, entities: MutableSet<UUID>, width: Float, height: Float) {
        at.world.spawn(at, Interaction::class.java) {
            mark(it, identity, entities)
            it.interactionWidth = width
            it.interactionHeight = height
            it.isResponsive = true
        }
    }

    companion object {
        fun statusKey(plot: FarmHellPlantationPlot): MessageKey = when (FarmHellGreenhouseEngine.phase(plot)) {
            FarmHellPlantationPhase.COLD -> MessageKey.FARM_HELL_PLANTATION_COLD
            FarmHellPlantationPhase.GROWING -> MessageKey.FARM_HELL_PLANTATION_GROWING
            FarmHellPlantationPhase.HOT -> MessageKey.FARM_HELL_PLANTATION_HOT
            FarmHellPlantationPhase.COOLING -> MessageKey.FARM_HELL_PLANTATION_COOLING
            FarmHellPlantationPhase.READY -> MessageKey.FARM_HELL_PLANTATION_READY
        }
        fun statusValues(locale: ArcFarmsLocale, index: Int, plot: FarmHellPlantationPlot) = mapOf(
            "point" to locale.text(index + 1), "time" to locale.text(FarmHellGreenhouseEngine.secondsRemaining(plot)))
    }

    fun mark(entity: Entity, identity: HellGreenhouseIdentity, entities: MutableSet<UUID>? = null) {
        entity.isPersistent = false
        entity.setGravity(false)
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, identity.zone)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, identity.sequence)
        entity.persistentDataContainer.set(placementKey, PersistentDataType.LONG, identity.placement)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, identity.role.name)
        entity.persistentDataContainer.set(indexKey, PersistentDataType.INTEGER, identity.index)
        entities?.add(entity.uniqueId)
    }

    private fun block(at: Location, material: Material, identity: HellGreenhouseIdentity, entities: MutableSet<UUID>): BlockDisplay =
        at.world.spawn(at, BlockDisplay::class.java) { entity ->
            mark(entity, identity, entities)
            entity.block = material.createBlockData()
            entity.viewRange = 1.0f
        }

    private fun label(at: Location, message: MessageKey, identity: HellGreenhouseIdentity, entities: MutableSet<UUID>,
        values: Map<String, net.kyori.adventure.text.Component> = emptyMap()): TextDisplay =
        at.world.spawn(at, TextDisplay::class.java) { entity ->
            mark(entity, identity, entities)
            text.render(entity, locale.render(message, values = values), FarmTextDisplayStyle(lineWidth = 220, viewRange = 0.8f))
        }

    fun clear(zone: String) { scenes.remove(zone)?.entities?.forEach { Bukkit.getEntity(it)?.remove() } }
    fun cleanup() {
        scenes.keys.toList().forEach(::clear)
        Bukkit.getWorlds().forEach { world -> world.entities.filter(::owns).forEach(Entity::remove) }
    }
}
