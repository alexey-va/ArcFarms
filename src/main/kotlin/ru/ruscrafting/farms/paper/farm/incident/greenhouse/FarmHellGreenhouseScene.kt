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
import org.joml.Matrix4f
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmHellGreenhouseEngine
import ru.ruscrafting.farms.domain.FarmHellGreenhouseState
import ru.ruscrafting.farms.domain.FarmHellHazardSide
import ru.ruscrafting.farms.domain.FarmHellHazardPhase
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayStyle
import java.util.UUID

internal enum class HellGreenhouseRole { SCENE, PEPPER, VAT, ENTRANCE, EXIT }
internal data class HellGreenhouseIdentity(val zone: String, val sequence: Long, val placement: Long, val role: HellGreenhouseRole, val index: Int)

/** Display contents of the journalled underground chamber; the entrance stays on the surface. */
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
    private data class Scene(val identity: HellGreenhouseIdentity, val entities: MutableSet<UUID>, val plants: List<UUID>, val carried: MutableMap<UUID, UUID>, val vatLabel: UUID, val heatFloors: List<UUID>, var cooled: Int = -1)
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

    fun center(runtime: FarmRuntime, state: FarmHellGreenhouseState): Location = Location(runtime.region.world,
        state.points.map { it.x }.average(), state.points.first().y, state.points.map { it.z }.average())

    fun render(runtime: FarmRuntime, players: List<Player>, tick: Int, particles: Boolean) {
        val greenhouse = runtime.state.hellGreenhouse ?: return
        val zone = runtime.settings.id
        val stamp = HellGreenhouseIdentity(zone, runtime.state.sequence, runtime.state.placementSequence, HellGreenhouseRole.SCENE, -1)
        var active = scenes[zone]
        if (active == null || active.identity != stamp || active.entities.any { Bukkit.getEntity(it)?.isValid != true }) {
            clear(zone)
            active = build(runtime, greenhouse, stamp)
            scenes[zone] = active
        }
        val owned = active
        if (owned.cooled != greenhouse.cooled) {
            (Bukkit.getEntity(owned.vatLabel) as? TextDisplay)?.let {
                text.render(it, locale.render(MessageKey.FARM_HELL_GREENHOUSE_VAT, values = mapOf(
                    "done" to locale.text(greenhouse.cooled), "total" to locale.text(runtime.settings.specialIncidents.hellGreenhouse.quota),
                )), FarmTextDisplayStyle(viewRange = 1.0f))
            }
            owned.cooled = greenhouse.cooled
        }
        val hazard = FarmHellGreenhouseEngine.hazard(greenhouse)
        owned.heatFloors.forEachIndexed { index, uuid ->
            val selected = (index == 0 && hazard.side == FarmHellHazardSide.LEFT) ||
                (index == 1 && hazard.side == FarmHellHazardSide.RIGHT)
            val material = when {
                !selected || hazard.phase == FarmHellHazardPhase.REST -> Material.SOUL_SOIL
                hazard.phase == FarmHellHazardPhase.WARNING -> Material.YELLOW_CONCRETE
                else -> Material.MAGMA_BLOCK
            }
            (Bukkit.getEntity(uuid) as? BlockDisplay)?.let { if (it.block.material != material) it.block = material.createBlockData() }
        }
        greenhouse.points.forEachIndexed { index, _ ->
            val material = when {
                index in greenhouse.harvested -> Material.CRIMSON_ROOTS
                FarmHellGreenhouseEngine.ripe(greenhouse, index, runtime.settings.specialIncidents.hellGreenhouse) -> Material.SHROOMLIGHT
                else -> Material.NETHER_WART_BLOCK
            }
            (Bukkit.getEntity(owned.plants[index]) as? BlockDisplay)?.let { if (it.block.material != material) it.block = material.createBlockData() }
        }
        val carriers = players.filter { it.uniqueId in greenhouse.carried }.associateBy(Player::getUniqueId)
        (owned.carried.keys - carriers.keys).forEach { id ->
            owned.carried.remove(id)?.let { uuid -> Bukkit.getEntity(uuid)?.remove(); owned.entities.remove(uuid) }
        }
        carriers.forEach { (id, player) ->
            val at = player.eyeLocation.add(player.location.direction.setY(0).multiply(0.65)).add(-0.15, -0.55, -0.15)
            val display = owned.carried[id]?.let(Bukkit::getEntity) as? BlockDisplay
            if (display == null) {
                val created = block(at, Material.SHROOMLIGHT, 0.3f, 0.35f, 0.3f, stamp, owned.entities)
                owned.carried[id] = created.uniqueId
            } else display.teleport(at)
        }
        if (particles && tick % 10 == 0) {
            val at = center(runtime, greenhouse)
            players.take(12).forEach { player ->
                if (player.uniqueId in greenhouse.carried) {
                    val from = player.location.add(0.0, 0.4, 0.0)
                    val step = at.clone().add(0.0, 0.5, -2.3).toVector().subtract(from.toVector()).multiply(1.0 / 8)
                    repeat(8) { player.spawnParticle(Particle.END_ROD, from.add(step), 1, 0.0, 0.0, 0.0, 0.0) }
                }
                player.spawnParticle(Particle.SPLASH, at.clone().add(0.0, 1.0, -2.3), 5, 0.35, 0.1, 0.35, 0.01)
            }
        }
    }

    private fun build(runtime: FarmRuntime, state: FarmHellGreenhouseState, stamp: HellGreenhouseIdentity): Scene {
        val center = center(runtime, state)
        val entities = linkedSetOf<UUID>()
        try {
        // Glazed walls surround one wide entrance; the central aisle leads directly to the water vat.
        for (x in listOf(-4.0, 3.75)) for (z in listOf(-5.0, 4.75)) {
            block(center.clone().add(x, 0.0, z), Material.POLISHED_BASALT, 0.25f, 4.0f, 0.25f, stamp, entities)
        }
        for (x in listOf(-4.0, 3.88)) {
            block(center.clone().add(x, 0.0, -5.0), Material.RED_STAINED_GLASS, 0.12f, 3.8f, 10.0f, stamp, entities)
        }
        block(center.clone().add(-4.0, 0.0, -5.0), Material.RED_STAINED_GLASS, 8.0f, 3.8f, 0.12f, stamp, entities)
        for (x in listOf(-4.0, 1.25)) {
            block(center.clone().add(x, 0.0, 4.88), Material.RED_STAINED_GLASS, 2.75f, 3.8f, 0.12f, stamp, entities)
        }
        for (x in listOf(-1.5, 1.25)) {
            block(center.clone().add(x, 0.0, 4.75), Material.CRIMSON_PLANKS, 0.25f, 3.8f, 0.25f, stamp, entities)
        }
        block(center.clone().add(-1.25, 0.01, -4.0), Material.POLISHED_BLACKSTONE_BRICKS, 2.5f, 0.08f, 9.0f, stamp, entities)
        for (z in listOf(-5.0, -2.5, 0.0, 2.5, 4.75)) {
            block(center.clone().add(-4.0, 3.8, z), Material.CRIMSON_PLANKS, 8.0f, 0.2f, 0.25f, stamp, entities)
        }
        block(center.clone().add(-4.0, 4.0, -5.0), Material.RED_STAINED_GLASS, 8.0f, 0.12f, 10.0f, stamp, entities)
        val heatFloors = mutableListOf<UUID>()
        for (x in listOf(-3.0, 1.0)) {
            heatFloors += block(center.clone().add(x, 0.03, -4.0), Material.SOUL_SOIL, 2.0f, 0.18f, 8.0f, stamp, entities).uniqueId
            block(center.clone().add(x, 0.03, -4.2), Material.POLISHED_BLACKSTONE_BRICKS, 2.0f, 0.35f, 0.2f, stamp, entities)
        }
        val plants = state.points.mapIndexed { index, point ->
            val base = Location(center.world, point.x, point.y, point.z)
            block(base.clone().add(-0.12, 0.15, -0.12), Material.CRIMSON_STEM, 0.24f, 0.9f, 0.24f, stamp, entities)
            val fruit = block(base.clone().add(-0.32, 0.9, -0.32), Material.NETHER_WART_BLOCK, 0.64f, 0.75f, 0.64f, stamp, entities)
            interaction(base.clone().add(0.0, 0.5, 0.0), stamp.copy(role = HellGreenhouseRole.PEPPER, index = index), 1.15f, 1.3f, entities)
            fruit.uniqueId
        }
        val vat = center.clone().add(0.0, 0.0, -2.3)
        block(vat.clone().add(-0.6, 0.0, -0.6), Material.WATER_CAULDRON, 1.2f, 1.0f, 1.2f, stamp, entities)
        interaction(vat, stamp.copy(role = HellGreenhouseRole.VAT), 1.5f, 1.4f, entities)
        val vatLabel = label(vat.clone().add(0.0, 2.0, 0.0), MessageKey.FARM_HELL_GREENHOUSE_VAT, stamp, entities,
            mapOf("done" to locale.text(state.cooled), "total" to locale.text(runtime.settings.specialIncidents.hellGreenhouse.quota)))
        val exit = center.clone().add(0.0, 0.0, 4.4)
        interaction(exit, stamp.copy(role = HellGreenhouseRole.EXIT), 1.6f, 2.0f, entities)
        label(exit.clone().add(0.0, 2.4, 0.0), MessageKey.FARM_HELL_GREENHOUSE_EXIT, stamp, entities)
        state.entrance?.let { point ->
            val surface = Location(center.world, point.x, point.y, point.z)
            block(surface.clone().add(-0.65, 0.02, -0.65), Material.CRIMSON_TRAPDOOR, 1.3f, 0.15f, 1.3f, stamp, entities)
            interaction(surface, stamp.copy(role = HellGreenhouseRole.ENTRANCE), 1.6f, 1.5f, entities)
            label(surface.clone().add(0.0, 2.1, 0.0), MessageKey.FARM_HELL_GREENHOUSE_ENTRANCE, stamp, entities,
                mapOf("total" to locale.text(runtime.settings.specialIncidents.hellGreenhouse.quota)))
        }
        return Scene(stamp, entities, plants, mutableMapOf(), vatLabel.uniqueId, heatFloors)

        } catch (failure: Exception) {
            entities.forEach { Bukkit.getEntity(it)?.remove() }
            throw failure
        }
    }

    private fun mark(entity: Entity, identity: HellGreenhouseIdentity, entities: MutableSet<UUID>) {
        entity.isPersistent = false
        entity.setGravity(false)
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, identity.zone)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, identity.sequence)
        entity.persistentDataContainer.set(placementKey, PersistentDataType.LONG, identity.placement)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, identity.role.name)
        entity.persistentDataContainer.set(indexKey, PersistentDataType.INTEGER, identity.index)
        entities += entity.uniqueId
    }

    private fun block(at: Location, material: Material, x: Float, y: Float, z: Float, identity: HellGreenhouseIdentity, entities: MutableSet<UUID>): BlockDisplay =
        at.world.spawn(at, BlockDisplay::class.java) { entity ->
            mark(entity, identity, entities)
            entity.block = material.createBlockData().also {
                if (it is org.bukkit.block.data.Levelled && material == Material.WATER_CAULDRON) it.level = it.maximumLevel
            }
            entity.setTransformationMatrix(Matrix4f().scale(x, y, z))
            entity.viewRange = 1.5f
        }

    private fun interaction(at: Location, identity: HellGreenhouseIdentity, width: Float, height: Float, entities: MutableSet<UUID>) {
        at.world.spawn(at, Interaction::class.java) { entity ->
            mark(entity, identity, entities)
            entity.interactionWidth = width
            entity.interactionHeight = height
            entity.isResponsive = true
        }
    }

    private fun label(at: Location, message: MessageKey, identity: HellGreenhouseIdentity, entities: MutableSet<UUID>,
        values: Map<String, net.kyori.adventure.text.Component> = emptyMap()): TextDisplay =
        at.world.spawn(at, TextDisplay::class.java) { entity ->
            mark(entity, identity, entities)
            text.render(entity, locale.render(message, values = values), FarmTextDisplayStyle(
                lineWidth = if (message == MessageKey.FARM_HELL_GREENHOUSE_ENTRANCE) 256 else 180, viewRange = 1.0f))
        }

    fun clear(zone: String) { scenes.remove(zone)?.entities?.forEach { Bukkit.getEntity(it)?.remove() } }
    fun cleanup() {
        scenes.keys.toList().forEach(::clear)
        // Explicit reload/startup reconciliation is the only full-world scan.
        Bukkit.getWorlds().forEach { world -> world.entities.filter(::owns).forEach(Entity::remove) }
    }
}
