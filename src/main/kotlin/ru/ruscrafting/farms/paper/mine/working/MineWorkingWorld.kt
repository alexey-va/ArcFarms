package ru.ruscrafting.farms.paper.mine.working

import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.concurrent.CompletableFuture
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.MineWorkingStage
import ru.ruscrafting.farms.domain.MineWorkingState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.platform.FarmBlockDataDecoder
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneOwner
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneRecord

internal data class MineWorkingScene(val plan: MineWorkingPlan, val blocks: WorksitePreparedScene,
    private val ceiling: Int = plan.blocks.keys.maxOf(WorksitePosition::y),
    val chunks: Set<Pair<Int, Int>> = plan.blocks.keys.mapTo(linkedSetOf()) { it.x shr 4 to (it.z shr 4) },
) {
    val floor: Int get() = plan.entrance.y
    fun inside(location: Location): Boolean = location.world === blocks.world &&
        location.blockY in floor + 1..ceiling &&
        WorksitePosition(location.world.name, location.blockX, floor + 1, location.blockZ) in plan.blocks

    fun surface(): Location = blocks.surface.clone()
}

/** Mine-specific stage projection over the same exact-original journal used by farm underground scenes. */
internal class MineWorkingWorld(
    private val registry: MineRuntimeRegistry,
    private val owner: WorksitePreparedSceneOwner,
    private val decoder: FarmBlockDataDecoder,
    private val occupiedByRecovery: (WorksitePosition) -> Boolean = { false },
    private val tasks: WorksiteTaskPort? = null,
) {
    private val scenes = mutableMapOf<String, MineWorkingScene>()
    private val ready = mutableSetOf<String>()
    private val retiring = mutableSetOf<String>()
    private val retained = mutableSetOf<String>()
    private val projectedDrive = mutableMapOf<String, ru.ruscrafting.farms.domain.MineDriveProgress>()

    private data class Capture(val runtime: MineRuntime, val sequence: Long, val plan: MineWorkingPlan,
        val placement: MineWorkingPlacement, val entries: List<Map.Entry<WorksitePosition,String>>,
        val records: MutableList<WorksitePreparedSceneRecord> = mutableListOf(),
        val types: MutableMap<WorksitePosition,Material> = mutableMapOf(), var cursor: Int = 0)
    private val captures = linkedMapOf<Pair<String,MineIncidentType>,Capture>()
    private val captured = mutableMapOf<Pair<String,MineIncidentType>,Pair<MineWorkingPlacement,WorksitePreparedScene>>()
    private val capturedPlans = mutableMapOf<Pair<String,MineIncidentType>,MineWorkingPlan>()
    private val captureData = mutableMapOf<String,String>()
    private var epoch = 0L
    private val captureRequests = mutableMapOf<Pair<String, MineIncidentType>, Any>()
    private val recoveryPlans = mutableMapOf<String, MineWorkingPlan>()
    private val planRequests = mutableSetOf<String>()
    private val pendingPreparations = mutableMapOf<String, CompletableFuture<Boolean>>()
    private data class ProjectionRequest(val type: MineIncidentType, val working: MineWorkingState,
        val completion: CompletableFuture<Unit> = CompletableFuture())
    private val projecting = mutableMapOf<String, ProjectionRequest>()
    private val pendingProjection = mutableMapOf<String, ProjectionRequest>()

    fun prewarm(runtime: MineRuntime, type: MineIncidentType, placement: MineWorkingPlacement) {
        if (tasks != null) { prewarmAsync(runtime, type, placement); return }
        val key=runtime.settings.id to type
        if(key in captures || captured[key]?.first==placement) return
        val plan=MineWorkingLayout.plan(type,placement)
        captures[key]=Capture(runtime,runtime.state.sequence,plan,placement,plan.blocks.entries.toList())
    }

    private fun prewarmAsync(runtime: MineRuntime, type: MineIncidentType, placement: MineWorkingPlacement) {
        val work = requireNotNull(tasks)
        val key = runtime.settings.id to type
        if (key in captureRequests || captured[key]?.let { it.first == placement && it.second.sequence == runtime.state.sequence } == true) return
        val request = Any()
        captureRequests[key] = request
        val token = work.lifecycleToken()
        val source = epoch
        val sequence = runtime.state.sequence
        val world = runtime.region.world
        val worldName = world.name
        val zoneId = runtime.settings.id
        val minimum = world.minHeight
        val maximum = world.maxHeight
        fun current() = epoch == source && captureRequests[key] === request && runtime.state.sequence == sequence &&
            registry.byId(zoneId) === runtime && org.bukkit.Bukkit.getWorld(worldName) === world
        work.compute {
            val plan = MineWorkingLayout.plan(type, placement)
            val points = linkedSetOf<WorksitePosition>().apply {
                addAll(plan.blocks.keys)
                val surface = placement.position(0, 0, -1)
                add(surface)
                (1..3).forEach { add(surface.copy(y = surface.y + it)) }
            }
            Triple(plan, points.iterator(), plan.blocks.mapValues { (p, data) -> initialActiveData(plan, p, data) })
        }.thenCompose { (plan, points, active) -> work.callSync(token) {
            val types = mutableMapOf<WorksitePosition, Material>()
            val records = mutableListOf<WorksitePreparedSceneRecord>()
            work.capture(token, ::current) {
                if (points.hasNext()) {
                    val p = points.next()
                    check(p.y in minimum until maximum && world.isChunkLoaded(p.x shr 4, p.z shr 4) && !occupiedByRecovery(p))
                    val block = world.getBlockAt(p.x, p.y, p.z)
                    types[p] = block.type
                    active[p]?.let { raw ->
                        check(block.state !is org.bukkit.block.TileState)
                        records += WorksitePreparedSceneRecord(worldName, zoneId, sequence, 0, p.x, p.y, p.z,
                            block.blockData.asString, captureData.getOrPut(raw) { decoder.decode(raw).asString }, "NONE", plan.blocks.size)
                    }
                }
                !points.hasNext()
            }.thenCompose { work.compute {
                require(MineWorkingPlanner.rejection(plan) { types[it] } == null)
                plan to records.toList()
            } }
        }.thenCompose { it } }.thenCompose { (plan, records) -> work.callSync(token) {
            check(current())
            captured[key] = placement to WorksitePreparedScene(world, zoneId, sequence, 0,
                placement.position(0, 1, -1).location(world), placement.position(0, 1, 1).location(world),
                placement.position(0, 1, 3).location(world), records)
            capturedPlans[key] = plan
        } }.whenComplete { _, _ -> work.callSync(token) { captureRequests.remove(key, request) } }
    }

    fun prepareAsync(runtime: MineRuntime, type: MineIncidentType, placement: MineWorkingPlacement, nonce: Long): CompletableFuture<Boolean> {
        val work = tasks ?: return CompletableFuture.completedFuture(prepare(runtime, type, placement, nonce))
        val sceneKey = key(runtime)
        pendingPreparations[sceneKey]?.let { return it }
        if (owner.restoring(runtime.settings.id) || sceneKey in retiring) return CompletableFuture.completedFuture(false)
        val key = runtime.settings.id to type
        val reserve = captured.remove(key)?.takeIf { it.first == placement && it.second.sequence == runtime.state.sequence }
            ?: return CompletableFuture.completedFuture(false)
        val plan = capturedPlans.remove(key) ?: return CompletableFuture.completedFuture(false)
        val old = reserve.second
        val source = epoch
        val token = work.lifecycleToken()
        val result = CompletableFuture<Boolean>()
        pendingPreparations[sceneKey] = result
        work.compute {
            Triple(old.records.map { it.copy(sceneId = sceneId(nonce)) }, plan.blocks.keys.maxOf(WorksitePosition::y),
                plan.blocks.keys.mapTo(linkedSetOf()) { it.x shr 4 to (it.z shr 4) })
        }.thenCompose { (records, ceiling, chunks) -> work.callSync(token) {
            check(epoch == source && runtime.state.sequence == old.sequence && sceneKey !in retiring)
            val prepared = WorksitePreparedScene(old.world, old.zoneId, old.sequence, sceneId(nonce), old.surface, old.start, old.end, records)
            scenes[sceneKey] = MineWorkingScene(plan, prepared, ceiling, chunks)
            owner.prepareAllAsync(listOf(prepared))
        }.thenCompose { it } }.whenComplete { success, failure -> work.callSync(token) {
            pendingPreparations.remove(sceneKey, result)
            if (failure != null || success != true) {
                scenes.remove(sceneKey)
                owner.beginRestore(old.world, old.zoneId, old.sequence, sceneId(nonce))
            }
            if (failure == null) result.complete(success) else result.completeExceptionally(failure)
        }.exceptionally { result.completeExceptionally(it); null } }
        return result
    }

    private fun captureSlice() {
        val entry=captures.entries.firstOrNull() ?: return
        val (key,c)=entry
        val world=c.runtime.region.world
        if(c.runtime.state.sequence!=c.sequence) { captures.remove(key);return }
        val deadline=System.nanoTime()+1_000_000L
        repeat(128) {
            if(System.nanoTime()>deadline) return
            val next=c.entries.getOrNull(c.cursor) ?: run {
                val valid=MineWorkingPlanner.rejection(c.plan) { p -> c.types[p] ?: if(world.isChunkLoaded(p.x shr 4,p.z shr 4)) world.getBlockAt(p.x,p.y,p.z).type else null } == null
                if(!valid) { captures.remove(key);return }
                val records=c.records.toList()
                captured[key]=c.placement to WorksitePreparedScene(world,c.runtime.settings.id,c.sequence,0,
                    c.placement.position(0,1,-1).location(world),c.placement.position(0,1,1).location(world),
                    c.placement.position(0,1,3).location(world),records)
                capturedPlans[key]=c.plan;captures.remove(key);return
            }
            val (p,data)=next
            if(!world.isChunkLoaded(p.x shr 4,p.z shr 4) || occupiedByRecovery(p) || p.y !in world.minHeight until world.maxHeight) {
                captures.remove(key);return
            }
            val block=world.getBlockAt(p.x,p.y,p.z)
            if(block.state is org.bukkit.block.TileState) { captures.remove(key);return }
            c.types[p]=block.type
            val active=initialActiveData(c.plan,p,data)
            c.records+=WorksitePreparedSceneRecord(world.name,c.runtime.settings.id,c.sequence,0,p.x,p.y,p.z,
                block.blockData.asString,captureData.getOrPut(active) { decoder.decode(active).asString },"NONE",c.entries.size)
            c.cursor++
        }
    }

    fun prepare(runtime: MineRuntime, type: MineIncidentType, placement: MineWorkingPlacement, nonce: Long): Boolean {
        if (owner.restoring(runtime.settings.id) || retiring.any { it.startsWith("${runtime.settings.id}:") }) return false
        val key=runtime.settings.id to type
        val reserve=captured.remove(key)?.takeIf { it.first==placement && it.second.sequence==runtime.state.sequence } ?: return false
        val plan=capturedPlans.remove(key) ?: return false
        val old=reserve.second
        val scene=WorksitePreparedScene(old.world,old.zoneId,old.sequence,sceneId(nonce),old.surface,old.start,old.end,
            old.records.map { it.copy(sceneId=sceneId(nonce)) })
        if (!owner.prepareIncrementally(scene,128)) return false
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
        val sceneKey = key(runtime)
        val plan = if (tasks == null) MineWorkingLayout.plan(incident.type, placement) else recoveryPlans[sceneKey] ?: run {
            if (planRequests.add(sceneKey)) {
                val work = requireNotNull(tasks)
                val token = work.lifecycleToken()
                val source = epoch
                work.compute { MineWorkingLayout.plan(incident.type, placement) }.thenCompose { value -> work.callSync(token) {
                    if (epoch == source && runtime.state.incident?.objectiveNonce == incident.objectiveNonce) recoveryPlans[sceneKey] = value
                    planRequests.remove(sceneKey)
                } }
            }
            return null
        }
        val (status, stored) = owner.ensurePreparedScene(
            world, runtime.settings.id, runtime.state.sequence, sceneId(incident.objectiveNonce),
            placement.position(0, 1, -1).location(world), RECOVERY_RADIUS,
            prepare = { if (owner.restoring(runtime.settings.id)) null
                else if (tasks == null) prepareScene(runtime, plan, placement, incident.objectiveNonce)
                else { prewarm(runtime, incident.type, placement); null } },
            start = placement.position(0, 1, 1).location(world), end = placement.position(0, 1, 3).location(world),
        )
        if (tasks != null && stored == null && status == ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneEnsureResult.UNAVAILABLE &&
            (runtime.settings.id to incident.type) in captured) {
            prepareAsync(runtime, incident.type, placement, incident.objectiveNonce)
        }
        return stored?.let { MineWorkingScene(plan, it) }?.also { scenes[key(runtime)] = it }
    }

    private fun prepareScene(runtime: MineRuntime, plan: MineWorkingPlan, placement: MineWorkingPlacement, nonce: Long): WorksitePreparedScene? {
        val world = runtime.region.world
        if (plan.blocks.keys.any { it.y !in world.minHeight until world.maxHeight ||
                !world.isChunkLoaded(it.x shr 4, it.z shr 4) || occupiedByRecovery(it) }) return null
        if (MineWorkingPlanner.rejection(plan) { world.getBlockAt(it.x, it.y, it.z).type } != null) return null
        // The block journal preserves BlockData, not container inventories or other block-entity data.
        if (plan.blocks.keys.any { world.getBlockAt(it.x, it.y, it.z).state is org.bukkit.block.TileState }) return null
        val records = plan.blocks.map { (position, data) ->
            val block = world.getBlockAt(position.x, position.y, position.z)
            WorksitePreparedSceneRecord(world.name, runtime.settings.id, runtime.state.sequence, sceneId(nonce),
                position.x, position.y, position.z, block.blockData.asString,
                decoder.decode(initialActiveData(plan, position, data)).asString,
                "NONE", plan.blocks.size)
        }
        return WorksitePreparedScene(world, runtime.settings.id, runtime.state.sequence, sceneId(nonce),
            placement.position(0, 1, -1).location(world), placement.position(0, 1, 1).location(world),
            placement.position(0, 1, 3).location(world), records)
    }

    fun preparationFailed(runtime: MineRuntime): Boolean = scenes[key(runtime)]?.let {
        owner.preparationFailed(it.blocks.zoneId,it.blocks.sequence,it.blocks.sceneId)
    } == true

    fun isReady(runtime: MineRuntime): Boolean {
        val key = key(runtime)
        if (key in ready) return key !in projecting
        val scene = scene(runtime) ?: return false
        if (owner.isBuilding(runtime.settings.id, runtime.state.sequence, scene.blocks.sceneId) ||
            scene.chunks.any { (x, z) -> !scene.blocks.world.isChunkLoaded(x, z) }) return false
        ready += key
        project(runtime)
        return tasks == null || key !in projecting
    }

    fun project(
        runtime: MineRuntime,
        typeOverride: MineIncidentType? = null,
        workingOverride: MineWorkingState? = null,
    ) {
        if (tasks != null) { projectAsync(runtime, typeOverride, workingOverride); return }
        if (key(runtime) !in ready) return
        val incident = runtime.state.incident
        val type = incident?.type ?: typeOverride ?: return
        val working = incident?.working ?: workingOverride ?: return
        val plan = scene(runtime)?.plan ?: return
        val changes = linkedMapOf<WorksitePosition, String>()
        if (MineDriveLayout.machine(type, plan.placement)) {
            projectDrive(runtime, plan, working)
            return
        }
        when (type) {
            MineIncidentType.TUNNEL_DRIVE -> {
                if (MineDriveLayout.enabled(plan.placement)) {
                    projectDrive(runtime, plan, working)
                    return
                }
                plan.excavation.forEachIndexed { index, position ->
                    if (working.stage != MineWorkingStage.EXCAVATE || index in working.completed) changes[position] = AIR
                }
                plan.supportFrames.forEachIndexed { index, frame ->
                    val installed = working.stage == MineWorkingStage.SUPPORT && index in working.completed
                    frame.forEach { (position, data) -> changes[position] = if (installed) data else ROCK }
                }
            }
            MineIncidentType.RAIL_EXTENSION, MineIncidentType.TRACK_DAMAGE -> {
                plan.rails.forEachIndexed { index, position ->
                    val rubbleIndex = plan.rubble.indexOf(position)
                    changes[position] = when (working.stage) {
                        MineWorkingStage.CLEAR_TRACK -> when {
                            rubbleIndex < 0 -> AIR
                            rubbleIndex in working.completed -> AIR
                            else -> RUBBLE
                        }
                        MineWorkingStage.LAY_TRACK -> if (index in working.completed) {
                            MineWorkingLayout.railData(plan, position)
                        } else AIR
                        MineWorkingStage.TEST_TRACK -> MineWorkingLayout.railData(plan, position)
                        else -> AIR
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

    fun projectAsync(runtime: MineRuntime, typeOverride: MineIncidentType? = null,
        workingOverride: MineWorkingState? = null): CompletableFuture<Unit> {
        val work = tasks ?: return runCatching { project(runtime, typeOverride, workingOverride) }.fold(
            { CompletableFuture.completedFuture(Unit) }, CompletableFuture<Unit>::failedFuture)
        val sceneKey = key(runtime)
        val incident = runtime.state.incident
        val type = typeOverride ?: incident?.type ?: return CompletableFuture.completedFuture(Unit)
        val working = workingOverride ?: incident?.working ?: return CompletableFuture.completedFuture(Unit)
        if (sceneKey !in ready) return CompletableFuture.completedFuture(Unit)
        val requested = ProjectionRequest(type, working)
        projecting[sceneKey]?.let { active ->
            if (active.type == type && active.working == working) return active.completion
            pendingProjection.put(sceneKey, requested)?.completion?.completeExceptionally(java.util.concurrent.CancellationException("Projection coalesced"))
            return requested.completion
        }
        startProjection(runtime, requested, work)
        return requested.completion
    }

    private fun startProjection(runtime: MineRuntime, request: ProjectionRequest, work: WorksiteTaskPort) {
        val sceneKey = key(runtime)
        val scene = scenes[sceneKey] ?: run { request.completion.completeExceptionally(IllegalStateException("Working scene is unavailable")); return }
        val world = runtime.region.world
        val worldName = world.name
        val source = epoch
        val token = work.lifecycleToken()
        val previous = projectedDrive[sceneKey]
        projecting[sceneKey] = request
        fun current() = epoch == source && scenes[sceneKey] === scene && sceneKey !in retiring &&
            org.bukkit.Bukkit.getWorld(worldName) === world && key(runtime) == sceneKey
        work.compute { workingProjection(scene.plan, request.type, request.working, previous) }.thenCompose { changes -> work.callSync(token) {
            val entries = changes.iterator()
            val drive = MineDriveLayout.machine(request.type, scene.plan.placement) ||
                (request.type == MineIncidentType.TUNNEL_DRIVE && MineDriveLayout.enabled(scene.plan.placement))
            work.capture(token, ::current) {
                if (entries.hasNext()) {
                    val (position, data) = entries.next()
                    check(world.isChunkLoaded(position.x shr 4, position.z shr 4)) { "Working projection chunk is unloaded" }
                    val block = world.getBlockAt(position.x, position.y, position.z)
                    if (!drive || block.type != Material.BEDROCK) {
                        val desired = decoder.decode(data)
                        if (block.blockData.asString != desired.asString) block.setBlockData(desired, false)
                    }
                }
                !entries.hasNext()
            }
        }.thenCompose { it } }.whenComplete { _, failure -> work.callSync(token) {
            if (projecting[sceneKey] === request) projecting.remove(sceneKey)
            if (failure == null) { request.working.drive?.let { projectedDrive[sceneKey] = it }; request.completion.complete(Unit) }
            else request.completion.completeExceptionally(failure)
            pendingProjection.remove(sceneKey)?.let { next ->
                if (current()) startProjection(runtime, next, work) else next.completion.completeExceptionally(java.util.concurrent.CancellationException("Working scene superseded"))
            }
        }.exceptionally { request.completion.completeExceptionally(it); null } }
    }

    private fun projectDrive(runtime: MineRuntime, plan: MineWorkingPlan, working: MineWorkingState) {
        val progress = working.drive ?: return
        val world = runtime.region.world
        fun put(position: WorksitePosition, data: String) {
            if (position !in plan.blocks || plan.blocks[position] == "minecraft:bedrock" ||
                !world.isChunkLoaded(position.x shr 4, position.z shr 4)) return
            val block = world.getBlockAt(position.x, position.y, position.z)
            if (block.type == Material.BEDROCK) return
            if (block.blockData.asString != data) block.setBlockData(decoder.decode(data), false)
        }
        val old = projectedDrive[key(runtime)]
        (progress.carved - old?.carved.orEmpty()).forEach { id ->
            if (MineDriveLayout.driveable(MineDriveLayout.side(id,plan.placement.geometryVersion), MineDriveLayout.forward(id,plan.placement.geometryVersion),plan.placement.geometryVersion,MineDriveLayout.rail(plan.type,plan.placement))) {
                for (up in 1..4) put(MineDriveLayout.position(plan.placement, id, up), AIR)
            }
        }
        (progress.lamps - old?.lamps.orEmpty()).forEach { id ->
            put(MineDriveLayout.position(plan.placement, id, 5), "minecraft:stripped_spruce_log[axis=y]")
            put(MineDriveLayout.position(plan.placement, id, 4), "minecraft:lantern[hanging=true,waterlogged=false]")
        }
        if (MineDriveLayout.rail(plan.type,plan.placement)) {
            val route = progress.rail?.route.orEmpty().map { MineDriveLayout.position(plan.placement,it) }
            val previous = old?.rail?.route.orEmpty().map { MineDriveLayout.position(plan.placement,it) }.toSet()
            // Leave the native carrier's immediate footprint free of rail physics;
            // the rear laying head commits sections as the chassis clears them.
            val laidIds = progress.rail?.let { ru.ruscrafting.farms.domain.MineRailProgression.laid(it,progress.checkpoint) }.orEmpty().toSet()
            val laid = route.filterIndexed { index,_ -> progress.rail!!.route[index] in laidIds }
            for(p in previous - laid.toSet()) put(p,AIR)
            laid.forEach { p ->
                put(p.copy(y=p.y-1), "minecraft:polished_andesite")
                put(p, MineWorkingLayout.railData(route,route.indexOf(p)))
            }
        }
        projectedDrive[key(runtime)] = progress
    }

    fun startRestore(runtime: MineRuntime) {
        projectedDrive.remove(key(runtime))
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

    /** Restores only a retained scene when the runtime has already advanced to another sequence. */
    fun startRestore(scene: MineWorkingScene) {
        val sceneKey = scenes.entries.firstOrNull { it.value === scene }?.key ?: return
        ready.remove(sceneKey)
        retiring += sceneKey
        owner.beginRestore(scene.blocks.world, scene.blocks.zoneId, scene.blocks.sequence)
    }

    fun hasScene(runtime: MineRuntime): Boolean = scenes.values.any { it.blocks.zoneId == runtime.settings.id }
    /** Retains a prepared scene for completion/recovery paths that outlive the incident lookup. */
    fun retainedScene(zoneId: String, sequence: Long? = null): MineWorkingScene? = scenes.values
        .asSequence()
        .filter { it.blocks.zoneId == zoneId && (sequence == null || it.blocks.sequence == sequence) }
        .maxByOrNull { it.blocks.sequence }

    fun retain(scene: MineWorkingScene) {
        scenes.entries.firstOrNull { it.value === scene }?.key?.let { retained += it }
    }

    fun release(scene: MineWorkingScene) {
        scenes.entries.firstOrNull { it.value === scene }?.key?.let { retained -= it }
    }

    fun occupied(runtime: MineRuntime): Boolean = scenes.values.any { scene ->
        scene.blocks.zoneId == runtime.settings.id && scene.blocks.world.players.any { scene.inside(it.location) }
    }

    fun occupied(scene: MineWorkingScene): Boolean = scene.blocks.world.players.any { scene.inside(it.location) }

    fun isRestoring(runtime: MineRuntime): Boolean = owner.restoring(runtime.settings.id)
    fun protects(location: Location): Boolean = owner.protects(location)

    fun process(): Int {
        if (tasks == null) captureSlice()
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
        projectedDrive.clear()
        scenes.forEach { (key, scene) ->
            if (!active(scene.blocks.zoneId, scene.blocks.sequence)) retiring += key
        }
        owner.reconcileLoaded(::active, ::activeScene)
    }
    fun onChunkLoad(chunk: Chunk) {
        registry.snapshot().filter { it.region.world === chunk.world && it.state.incident?.working != null }.forEach {
            ready.remove(key(it))
            projectedDrive.remove(key(it))
        }
        owner.onChunkLoad(chunk, ::active, ::activeScene)
    }

    private fun activeScene(record: WorksitePreparedSceneRecord): Boolean = registry.byId(record.zoneId)?.let {
        "${record.zoneId}:${record.sequence}" in retained ||
            it.state.incident?.let { incident -> incident.working != null && sceneId(incident.objectiveNonce) == record.sceneId } == true
    } == true
    fun clearQueues() {
        epoch++
        projecting.values.forEach { it.completion.completeExceptionally(java.util.concurrent.CancellationException("Working owner closed")) }
        pendingProjection.values.forEach { it.completion.completeExceptionally(java.util.concurrent.CancellationException("Working owner closed")) }
        projecting.clear(); pendingProjection.clear(); pendingPreparations.clear(); recoveryPlans.clear(); planRequests.clear(); captureRequests.clear()
        projectedDrive.clear(); captures.clear(); captured.clear(); capturedPlans.clear(); captureData.clear(); owner.clearQueues(); scenes.clear(); ready.clear(); retiring.clear(); retained.clear() }

    private fun active(zoneId: String, sequence: Long): Boolean = registry.byId(zoneId)?.let {
        ("$zoneId:$sequence" in retained ||
            (it.state.sequence == sequence && it.state.incident?.working != null)) && "$zoneId:$sequence" !in retiring
    } == true

    private fun key(runtime: MineRuntime) = "${runtime.settings.id}:${runtime.state.sequence}"
    private fun sceneId(nonce: Long) = (nonce % 16).toInt()

    private fun initialActiveData(plan: MineWorkingPlan, position: WorksitePosition, data: String): String = when {
        plan.type == MineIncidentType.TUNNEL_DRIVE && plan.supportFrames.any { position in it } -> ROCK
        plan.type == MineIncidentType.RAIL_EXTENSION && position in plan.rails && position !in plan.rubble -> AIR
        else -> data
    }

    companion object {
        private const val BLOCK_BUDGET = 256
        // The rail route now reaches local forward 50; the scene surface is
        // at -1, so a 56-block bounded probe covers the far chunk on restart.
        private const val RECOVERY_RADIUS = 56
        private const val ROCK = "minecraft:stone"
        private const val AIR = "minecraft:air"
        private const val RUBBLE = "minecraft:cobblestone"
    }
}

internal fun WorksitePosition.location(world: org.bukkit.World, centered: Boolean = true): Location =
    Location(world, x + if (centered) 0.5 else 0.0, y.toDouble(), z + if (centered) 0.5 else 0.0)
