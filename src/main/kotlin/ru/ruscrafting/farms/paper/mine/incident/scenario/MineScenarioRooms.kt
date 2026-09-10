package ru.ruscrafting.farms.paper.mine.incident.scenario

import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.MineScenarioPlacement
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowScene
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorld
import ru.ruscrafting.farms.paper.farm.care.mole.MoleBurrowChunkRetention
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.platform.FarmBlockDataDecoder
import ru.ruscrafting.farms.paper.platform.FarmRouteChunkLoader
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteExpeditionTravel
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Mine room policy composes the farm's journal and shared durable travel owners. */
internal class MineScenarioRooms(
    private val plugin: Plugin,
    private val tasks: WorksiteTaskPort,
    access: WorksiteAccessPort,
    private val state: WorksiteStatePort,
    decoder: FarmBlockDataDecoder,
    retention: MoleBurrowChunkRetention,
    private val chunks: FarmRouteChunkLoader,
    private val locale: ru.ruscrafting.farms.config.ArcFarmsLocale? = null,
) {
    val template = MineEventRoomTemplate(decoder)
    val travel = WorksiteExpeditionTravel(plugin, tasks, access, state, Path.of("data/recovery/mine-event-returns"))
    private val world = FarmMoleBurrowWorld(plugin, ArcFarmsDebug({ false }) {}, retention, decoder, "mine_event_rooms")
    private val marker = locale?.let { ru.ruscrafting.farms.paper.worksite.WorksiteEntryMarker(it,
        ru.ruscrafting.farms.paper.platform.PaperFarmTextDisplayRenderer) }
    private val gates = mutableMapOf<java.util.UUID, Pair<String, Boolean>>()
    private val gateEntities = mutableMapOf<String, List<java.util.UUID>>()
    private val gateKey = org.bukkit.NamespacedKey(plugin, "mine_event_gate")
    private val scenes = mutableMapOf<String, FarmMoleBurrowScene>()
    private val rejectedPlacement = mutableMapOf<String, Long>()
    private val loading = mutableSetOf<String>()
    private val loadedTickets = mutableMapOf<String, List<Chunk>>()

    fun scene(runtime: MineRuntime): FarmMoleBurrowScene? = scenes[runtime.settings.id]
        ?.takeIf { it.sequence == runtime.state.sequence }

    fun ensure(runtime: MineRuntime): FarmMoleBurrowScene? {
        scene(runtime)?.let { if (it.ready) ensureMarkers(runtime, it); return it }
        val placement = runtime.state.incident?.scenarioPlacement ?: return null
        val origin = placement.origin.location(runtime)
        val key = runtime.settings.id
        if (!loadChunks(runtime, placement)) return null
        val surface = placement.entrance
        val result = world.ensurePreparedScene(origin.world, key, runtime.state.sequence, MineEventRoomTemplate.ROOM_ID,
            FarmPointPosition(surface.world, surface.x + 0.5, surface.y.toDouble(), surface.z + 0.5), 4) {
            template.prepare(origin.world, key, runtime.state.sequence, origin, placement.entrance.location(runtime))
        }.second
        if (result != null) { scenes[key] = result; if (result.ready) ensureMarkers(runtime, result) }
        releaseLoadTickets(key)
        return result
    }

    fun canPrepare(runtime: MineRuntime, placement: MineScenarioPlacement): Boolean {
        if (world.restoring(runtime.settings.id)) return false
        if (!loadChunks(runtime, placement)) return false
        val plan = template.prepare(runtime.region.world, runtime.settings.id, runtime.state.sequence,
            placement.origin.location(runtime), placement.entrance.location(runtime))
        if (plan == null) {
            releaseLoadTickets(runtime.settings.id)
            if (rejectedPlacement.put(runtime.settings.id, runtime.state.sequence) != runtime.state.sequence) state.log(Level.WARNING, "Mine event room placement rejected: zone=${runtime.settings.id} sequence=${runtime.state.sequence} reason=occupied_or_out_of_bounds origin=${placement.origin}")
        }
        if (plan != null) rejectedPlacement.remove(runtime.settings.id)
        return plan != null
    }

    private fun loadChunks(runtime: MineRuntime, placement: MineScenarioPlacement): Boolean {
        val origin = placement.origin.location(runtime)
        val key = runtime.settings.id
        val coordinates = ((origin.blockX shr 4)..((origin.blockX + template.size[0] - 1) shr 4)).flatMap { x ->
            ((origin.blockZ shr 4)..((origin.blockZ + template.size[2] - 1) shr 4)).map { z -> x to z }
        }
        if (coordinates.any { (x, z) -> !origin.world.isChunkLoaded(x, z) }) {
            if (loading.add(key)) {
                val token = tasks.lifecycleToken()
                val sequence = runtime.state.sequence
                val futures = coordinates.map { (x, z) -> chunks.load(origin.world, x, z) }
                CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, failure ->
                    tasks.runSync(token) {
                        loading.remove(key)
                        if (runtime.state.sequence != sequence || (runtime.state.incident != null && runtime.state.incident?.scenarioPlacement != placement)) return@runSync
                        val loaded = futures.mapNotNull { if (it.isCompletedExceptionally) null else it.getNow(null) }
                        if (failure != null || loaded.size != coordinates.size) {
                            state.log(Level.WARNING, "Mine event room unavailable: zone=$key sequence=$sequence reason=missing_chunks")
                        } else {
                            loadedTickets[key] = loaded.filter { chunks.retain(it, plugin) }
                        }
                    }
                }
            }
            return false
        }
        return true
    }

    fun enter(player: Player, runtime: MineRuntime) {
        val room = scene(runtime)?.takeIf { it.ready } ?: return
        val sequence = runtime.state.sequence
        val placement = runtime.state.incident?.scenarioPlacement ?: return
        travel.enter(WorksiteExpeditionTravel.EntryRequest(player, runtime.settings.id, sequence,
            runtime.settings.permission, room.surface, room.start)) {
            runtime.state.sequence == sequence && runtime.state.incident?.scenarioPlacement == placement && scene(runtime) === room && room.ready
        }
    }

    fun at(location: Location): String? = scenes.entries.firstOrNull { it.value.contains(location) }?.key

    fun restore(runtime: MineRuntime, sequence: Long = runtime.state.sequence): Boolean {
        val key = runtime.settings.id
        if (!travel.evacuate(key)) return false
        clearMarkers(key)
        val room = scenes.remove(key)
        world.beginRestore(room?.world ?: runtime.region.world, key, room?.sequence ?: sequence)
        releaseLoadTickets(key)
        return true
    }

    fun protects(location: Location): Boolean = at(location) != null || world.hasPendingBlock(location)

    fun process() {
        world.process(512) { true }
    }

    fun reconcileChunk(chunk: Chunk, active: (String, Long) -> Boolean) = world.onChunkLoad(chunk, active)

    fun reconcileLoaded(active: (String, Long) -> Boolean) = world.reconcileLoaded(active)

    fun close() {
        gateEntities.keys.toList().forEach(::clearMarkers)
        scenes.keys.toList().forEach { key ->
            if (travel.evacuate(key)) scenes.remove(key)?.let { world.beginRestore(it.world, it.zoneId, it.sequence) }
        }
        world.process(8192) { true }
        loadedTickets.keys.toList().forEach(::releaseLoadTickets)
        loading.clear()
        world.clearQueues()
    }

    fun gate(entity: org.bukkit.entity.Entity): Pair<String, Boolean>? = gates[entity.uniqueId]

    private fun ensureMarkers(runtime: MineRuntime, room: FarmMoleBurrowScene) {
        val factory = marker ?: return
        val key = runtime.settings.id
        if (gateEntities[key]?.all { org.bukkit.Bukkit.getEntity(it)?.isValid == true } == true) return
        clearMarkers(key)
        val placement = runtime.state.incident?.scenarioPlacement ?: return
        val floor = requireNotNull(locale).renderPath(if (placement.floorId in setOf("top", "upper", "middle", "lower", "bottom"))
            "mine-lift.floors.${placement.floorId}" else "route.mine.${runtime.settings.id}")
        val visual = ru.ruscrafting.farms.config.FarmCareVisualSettings("IRON_PICKAXE", 0,
            ru.ruscrafting.farms.config.FarmItemDisplayTransform.FIXED, 1.0f, 0.7)
        val ids = mutableListOf<java.util.UUID>()
        listOf(room.surface to false, room.lair to true).forEach { (at, exit) ->
            factory.spawn(at, visual, if (exit) "mine.events.common.exit" else "mine.events.common.entry",
                true, 1.5f, { entity ->
                    entity.persistentDataContainer.set(gateKey, org.bukkit.persistence.PersistentDataType.STRING, key)
                    gates[entity.uniqueId] = key to exit
                    ids += entity.uniqueId
                }, mapOf("floor" to floor, "destination" to floor))
        }
        gateEntities[key] = ids
    }

    private fun clearMarkers(key: String) {
        gateEntities.remove(key).orEmpty().forEach { id -> gates.remove(id); org.bukkit.Bukkit.getEntity(id)?.remove() }
    }

    fun releasePreflight(runtime: MineRuntime) = releaseLoadTickets(runtime.settings.id)

    private fun releaseLoadTickets(key: String) {
        loadedTickets.remove(key).orEmpty().forEach { chunks.release(it, plugin) }
    }

    private fun WorksitePosition.location(runtime: MineRuntime): Location {
        require(world == runtime.region.world.name)
        return Location(runtime.region.world, x + 0.5, y.toDouble(), z + 0.5)
    }
}
