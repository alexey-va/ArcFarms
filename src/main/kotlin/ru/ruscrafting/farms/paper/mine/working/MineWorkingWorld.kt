package ru.ruscrafting.farms.paper.mine.working

import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.MineWorkingStage
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.platform.FarmBlockDataDecoder
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneOwner
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneRecord

internal data class MineWorkingScene(val plan: MineWorkingPlan, val blocks: WorksitePreparedScene) {
    val floor: Int get() = plan.entrance.y
    fun inside(location: Location): Boolean = location.world === blocks.world &&
        location.blockY in floor + 1..floor + 4 &&
        WorksitePosition(location.world.name, location.blockX, floor + 1, location.blockZ) in plan.blocks

    fun surface(): Location = blocks.surface.clone()
}

/** Mine-specific stage projection over the same exact-original journal used by farm underground scenes. */
internal class MineWorkingWorld(
    private val registry: MineRuntimeRegistry,
    private val owner: WorksitePreparedSceneOwner,
    private val decoder: FarmBlockDataDecoder,
    private val occupiedByRecovery: (WorksitePosition) -> Boolean = { false },
) {
    private val scenes = mutableMapOf<String, MineWorkingScene>()
    private val ready = mutableSetOf<String>()
    private val retiring = mutableSetOf<String>()

    fun prepare(runtime: MineRuntime, type: MineIncidentType, placement: MineWorkingPlacement, nonce: Long): Boolean {
        if (owner.restoring(runtime.settings.id) || retiring.any { it.startsWith("${runtime.settings.id}:") }) return false
        val plan = MineWorkingLayout.plan(type, placement)
        val scene = prepareScene(runtime, plan, placement, nonce) ?: return false
        if (!owner.prepare(listOf(scene))) return false
        scenes[key(runtime)] = MineWorkingScene(plan, scene)
        return true
    }

    fun scene(runtime: MineRuntime): MineWorkingScene? {
        scenes[key(runtime)]?.let { return it }
        val incident = runtime.state.incident ?: return null
        if (incident.type == MineIncidentType.ORE_WORKSHOP) return null
        val working = incident.working ?: return null
        val placement = working.placement
        val world = runtime.region.world
        val plan = MineWorkingLayout.plan(incident.type, placement)
        val (_, stored) = owner.ensurePreparedScene(
            world, runtime.settings.id, runtime.state.sequence, sceneId(incident.objectiveNonce),
            placement.position(0, 1, -1).location(world), RECOVERY_RADIUS,
            prepare = { if (owner.restoring(runtime.settings.id)) null
                else prepareScene(runtime, plan, placement, incident.objectiveNonce) },
            start = placement.position(0, 1, 1).location(world), end = placement.position(0, 1, 3).location(world),
        )
        return stored?.let { MineWorkingScene(plan, it) }?.also { scenes[key(runtime)] = it }
    }

    private fun prepareScene(runtime: MineRuntime, plan: MineWorkingPlan, placement: MineWorkingPlacement, nonce: Long): WorksitePreparedScene? {
        val world = runtime.region.world
        if (plan.blocks.keys.any { !runtime.region.bounds.contains(it.x, it.y, it.z) ||
                !world.isChunkLoaded(it.x shr 4, it.z shr 4) || occupiedByRecovery(it) }) return null
        if (MineWorkingPlanner.rejection(plan) { world.getBlockAt(it.x, it.y, it.z).type } != null) return null
        // The block journal preserves BlockData, not container inventories or other block-entity data.
        if (plan.blocks.keys.any { world.getBlockAt(it.x, it.y, it.z).state is org.bukkit.block.TileState }) return null
        val records = plan.blocks.map { (position, data) ->
            val block = world.getBlockAt(position.x, position.y, position.z)
            WorksitePreparedSceneRecord(world.name, runtime.settings.id, runtime.state.sequence, sceneId(nonce),
                position.x, position.y, position.z, block.blockData.asString, decoder.decode(data).asString,
                "NONE", plan.blocks.size)
        }
        return WorksitePreparedScene(world, runtime.settings.id, runtime.state.sequence, sceneId(nonce),
            placement.position(0, 1, -1).location(world), placement.position(0, 1, 1).location(world),
            placement.position(0, 1, 3).location(world), records)
    }

    fun isReady(runtime: MineRuntime): Boolean {
        val key = key(runtime)
        if (key in ready) return true
        val scene = scene(runtime) ?: return false
        if (owner.isBuilding(runtime.settings.id, runtime.state.sequence, scene.blocks.sceneId) ||
            scene.blocks.records.any { !scene.blocks.world.isChunkLoaded(it.x shr 4, it.z shr 4) }) return false
        ready += key
        project(runtime)
        return true
    }

    fun project(runtime: MineRuntime) {
        if (key(runtime) !in ready) return
        val incident = runtime.state.incident ?: return
        val working = incident.working ?: return
        val plan = scene(runtime)?.plan ?: return
        val changes = linkedMapOf<WorksitePosition, String>()
        when (incident.type) {
            MineIncidentType.TUNNEL_DRIVE -> {
                plan.excavation.forEachIndexed { index, position ->
                    if (working.stage != MineWorkingStage.EXCAVATE || index in working.completed) changes[position] = AIR
                }
                if (working.stage == MineWorkingStage.SUPPORT) working.completed.forEach { changes.putAll(plan.supportBlocks(it)) }
            }
            MineIncidentType.RAIL_EXTENSION, MineIncidentType.TRACK_DAMAGE -> {
                plan.rubble.forEachIndexed { index, position ->
                    if (working.stage != MineWorkingStage.CLEAR_TRACK || index in working.completed) changes[position] = AIR
                }
                plan.rails.forEachIndexed { index, position ->
                    if (working.stage == MineWorkingStage.TEST_TRACK ||
                        working.stage == MineWorkingStage.LAY_TRACK && index in working.completed) {
                        changes[position] = MineWorkingLayout.railData(plan, position)
                    }
                }
            }
            MineIncidentType.ORE_WORKSHOP -> {
                // A lit furnace is part of the journalled assembly, never a usable vanilla inventory.
                plan.stations["furnace"]?.let { furnace ->
                    val original = plan.blocks.getValue(furnace)
                    changes[furnace] = if (working.stage == MineWorkingStage.HEAT)
                        original.replace("lit=false", "lit=true") else original
                }
            }
            else -> Unit
        }
        changes.forEach { (position, data) ->
            if (position !in plan.blocks || !runtime.region.world.isChunkLoaded(position.x shr 4, position.z shr 4)) return@forEach
            val block = runtime.region.world.getBlockAt(position.x, position.y, position.z)
            val desired = decoder.decode(data)
            if (block.blockData.asString != desired.asString) block.setBlockData(desired, false)
        }
    }

    fun startRestore(runtime: MineRuntime) {
        val owned = scenes.filterValues { it.blocks.zoneId == runtime.settings.id }
        if (owned.isEmpty()) {
            ready.remove(key(runtime))
            retiring += key(runtime)
            owner.beginRestore(runtime.region.world, runtime.settings.id, runtime.state.sequence)
        } else owned.forEach { (key, scene) ->
            ready.remove(key)
            retiring += key
            owner.beginRestore(scene.blocks.world, scene.blocks.zoneId, scene.blocks.sequence)
        }
    }

    fun hasScene(runtime: MineRuntime): Boolean = scenes.values.any { it.blocks.zoneId == runtime.settings.id }
    fun occupied(runtime: MineRuntime): Boolean = scenes.values.any { scene ->
        scene.blocks.zoneId == runtime.settings.id && scene.blocks.world.players.any { scene.inside(it.location) }
    }

    fun isRestoring(runtime: MineRuntime): Boolean = owner.restoring(runtime.settings.id)
    fun protects(location: Location): Boolean = owner.protects(location)

    fun process(): Int {
        val result = owner.process(BLOCK_BUDGET) { record ->
            val scene = scenes["${record.zoneId}:${record.sequence}"]
            // Never seal a player into a restored wall, including an admin who bypassed normal entry.
            val clearing = "${record.zoneId}:${record.sequence}" in retiring || !active(record.zoneId, record.sequence)
            !clearing || if (scene != null) scene.blocks.world.players.none { scene.inside(it.location) }
            else org.bukkit.Bukkit.getWorld(record.world)?.players?.none {
                // Orphan recovery can run before an online player's durable return is loaded.
                kotlin.math.abs(it.location.x - record.x) < 2.0 &&
                    kotlin.math.abs(it.location.z - record.z) < 2.0 &&
                    it.location.y in (record.y - 2.0)..(record.y + 2.0)
            } != false
        }
        retiring.toList().forEach { key ->
            val zone = key.substringBefore(':')
            if (!owner.restoring(zone)) { retiring.remove(key); scenes.remove(key) }
        }
        return result
    }

    fun reconcileLoaded() {
        ready.clear()
        scenes.forEach { (key, scene) ->
            if (!active(scene.blocks.zoneId, scene.blocks.sequence)) retiring += key
        }
        owner.reconcileLoaded(::active, ::activeScene)
    }
    fun onChunkLoad(chunk: Chunk) {
        registry.snapshot().filter { it.region.world === chunk.world && it.state.incident?.working != null }.forEach {
            ready.remove(key(it))
        }
        owner.onChunkLoad(chunk, ::active, ::activeScene)
    }

    private fun activeScene(record: WorksitePreparedSceneRecord): Boolean = registry.byId(record.zoneId)?.let {
        it.state.incident?.let { incident -> incident.working != null && sceneId(incident.objectiveNonce) == record.sceneId }
    } == true
    fun clearQueues() { owner.clearQueues(); scenes.clear(); ready.clear(); retiring.clear() }

    private fun active(zoneId: String, sequence: Long): Boolean = registry.byId(zoneId)?.let {
        it.state.sequence == sequence && it.state.incident?.working != null && "${zoneId}:$sequence" !in retiring
    } == true

    private fun key(runtime: MineRuntime) = "${runtime.settings.id}:${runtime.state.sequence}"
    private fun sceneId(nonce: Long) = (nonce % 16).toInt()

    companion object {
        private const val BLOCK_BUDGET = 256
        private const val RECOVERY_RADIUS = 32
        private const val AIR = "minecraft:air"
    }
}

internal fun WorksitePosition.location(world: org.bukkit.World, centered: Boolean = true): Location =
    Location(world, x + if (centered) 0.5 else 0.0, y.toDouble(), z + if (centered) 0.5 else 0.0)
