package ru.ruscrafting.farms.paper.farm.incident.greenhouse

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
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

internal enum class HellGreenhouseRole { SCENE, ENTRANCE, EXIT }
internal data class HellGreenhouseIdentity(val zone: String, val sequence: Long, val placement: Long, val role: HellGreenhouseRole, val index: Int)

/** Rift-specific content only. The common underground expedition owns the surface entrance. */
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
    private data class Scene(val identity: HellGreenhouseIdentity, val entities: MutableSet<UUID>,
        val pads: List<UUID>, val labels: List<UUID>, val heatFloors: List<Pair<Int, UUID>>, var signature: String = "")
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

    fun render(runtime: FarmRuntime, players: List<Player>, tick: Int, particles: Boolean, charge: FarmHellRiftCharge) {
        val rift = runtime.state.hellGreenhouse ?: return
        val identity = stamp(runtime)
        var active = scenes[runtime.settings.id]
        if (active == null || active.identity != identity || active.entities.any { Bukkit.getEntity(it)?.isValid != true }) {
            clear(runtime.settings.id)
            active = build(runtime, rift, identity)
            scenes[runtime.settings.id] = active
        }
        val hazard = FarmHellGreenhouseEngine.hazard(rift)
        val signature = "${rift.cooled}:${hazard.phase}:${hazard.side}:${charge.remainingSeconds}"
        if (active.signature != signature) {
            active.pads.forEachIndexed { index, uuid ->
                val material = when {
                    index < rift.cooled -> Material.SEA_LANTERN
                    index == rift.cooled -> Material.WARPED_PLANKS
                    else -> Material.LODESTONE
                }
                (Bukkit.getEntity(uuid) as? BlockDisplay)?.block = material.createBlockData()
                (Bukkit.getEntity(active.labels[index]) as? TextDisplay)?.let {
                    val key = when {
                        index < rift.cooled -> MessageKey.FARM_HELL_RIFT_SEALED
                        index == rift.cooled -> MessageKey.FARM_HELL_RIFT_RUNE
                        else -> MessageKey.FARM_HELL_RIFT_DORMANT
                    }
                    text.render(it, locale.render(key, values = mapOf("point" to locale.text(index + 1))), FarmTextDisplayStyle(viewRange = 0.7f))
                }
            }
            active.heatFloors.forEach { (side, uuid) ->
                val selected = (side < 0 && hazard.side == FarmHellHazardSide.LEFT) || (side > 0 && hazard.side == FarmHellHazardSide.RIGHT)
                val material = when {
                    !selected || hazard.phase == FarmHellHazardPhase.REST -> Material.CRACKED_POLISHED_BLACKSTONE_BRICKS
                    hazard.phase == FarmHellHazardPhase.WARNING -> Material.ORANGE_TERRACOTTA
                    else -> Material.MAGMA_BLOCK
                }
                (Bukkit.getEntity(uuid) as? BlockDisplay)?.block = material.createBlockData()
            }
            active.signature = signature
        }
        if (particles && tick % 5 == 0) {
            val center = center(runtime, rift)
            players.forEach { player ->
                player.spawnParticle(Particle.PORTAL, center.clone().add(0.0, 1.5, 0.0), 12, 0.25, 1.0, 0.1, 0.02)
                rift.points.getOrNull(rift.cooled)?.let { point ->
                    val at = Location(center.world, point.x, point.y + 0.15, point.z)
                    player.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 5, 0.35, 0.05, 0.35, 0.0)
                }
            }
        }
    }

    private fun build(runtime: FarmRuntime, state: FarmHellGreenhouseState, stamp: HellGreenhouseIdentity): Scene {
        val center = center(runtime, state)
        val entities = linkedSetOf<UUID>()
        try {
            // Full-sized block models preserve native texture scale, with only a tiny depth offset above the real floor.
            val heat = listOf(-3, -2, 2, 3).flatMap { x -> (-4..4).map { z ->
                x to block(center.clone().add(x - 0.5, -0.99, z - 0.5), Material.CRACKED_POLISHED_BLACKSTONE_BRICKS, stamp, entities).uniqueId
            } }
            val labels = mutableListOf<UUID>()
            val pads = state.points.take(runtime.settings.specialIncidents.hellGreenhouse.quota).mapIndexed { index, point ->
                val at = Location(center.world, point.x, point.y, point.z)
                labels += label(at.clone().add(0.0, 1.0, 0.0), MessageKey.FARM_HELL_RIFT_DORMANT, stamp, entities,
                    mapOf("point" to locale.text(index + 1))).uniqueId
                block(at.clone().add(-0.5, -0.96, -0.5), Material.LODESTONE, stamp, entities).uniqueId
            }
            val exit = center.clone().add(0.0, 0.0, 4.4)
            exit.world.spawn(exit, Interaction::class.java) {
                mark(it, stamp.copy(role = HellGreenhouseRole.EXIT), entities)
                it.interactionWidth = 1.6f
                it.interactionHeight = 2f
                it.isResponsive = true
            }
            label(exit.clone().add(0.0, 2.2, 0.0), MessageKey.FARM_HELL_GREENHOUSE_EXIT, stamp, entities)
            label(center.clone().add(0.0, 4.0, 0.0), MessageKey.FARM_HELL_GREENHOUSE_STARTED, stamp, entities)
            return Scene(stamp, entities, pads, labels, heat)
        } catch (failure: Exception) {
            entities.forEach { Bukkit.getEntity(it)?.remove() }
            throw failure
        }
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
