package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.generator.ChunkGenerator
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import net.kyori.adventure.util.TriState
import ru.ruscrafting.farms.domain.mine.expedition.ExpeditionPoint
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionEngine
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionGenerator
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlan
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlacement
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.index.MineChunkTicket
import ru.ruscrafting.farms.paper.worksite.WorksiteAsyncBlockScanner
import ru.ruscrafting.farms.paper.worksite.WorksiteBlockSnapshot
import ru.ruscrafting.farms.paper.worksite.WorksiteChunkCoordinate
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneBlockDataDecoder
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneOwner
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneRecord
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneChunkRetention
import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.persistence.MineExpeditionSceneReceipt
import ru.ruscrafting.farms.persistence.MineExpeditionSceneRepository
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

internal interface MineExpeditionWorldRegistry {
    fun ensureWorld(): World?
}

/** Bukkit world owner used when the plugin does not provide its own world registry. */
internal class BukkitMineExpeditionWorldRegistry(private val plugin: Plugin) : MineExpeditionWorldRegistry {
    private val ownerKey = NamespacedKey(plugin, "mine_expeditions_owner")

    override fun ensureWorld(): World? {
        Bukkit.getWorld(MineExpeditionWorldGenerator.WORLD_NAME)?.let { world ->
            validate(world)
            configure(world)
            return world
        }
        val creator = WorldCreator(MineExpeditionWorldGenerator.WORLD_NAME)
            .environment(World.Environment.NORMAL)
            .generateStructures(false)
            .generator(MineExpeditionWorldGenerator())
            .keepSpawnLoaded(TriState.FALSE)
        val existed = java.io.File(Bukkit.getWorldContainer(), MineExpeditionWorldGenerator.WORLD_NAME).exists()
        val world = Bukkit.createWorld(creator) ?: return null
        if (existed) validate(world)
        else world.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, OWNER_MARKER)
        configure(world)
        return world
    }

    private fun validate(world: World) {
        require(world.name == MineExpeditionWorldGenerator.WORLD_NAME) { "Unexpected expedition world" }
        require(world.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) == OWNER_MARKER) {
            "Existing expedition world is not owned by ArcFarms"
        }
    }

    private fun configure(world: World) {
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
        world.setGameRule(GameRule.DO_FIRE_TICK, false)
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false)
        world.setSpawnFlags(false, false)
        world.setStorm(false)
        world.isThundering = false
    }

    companion object {
        const val OWNER_MARKER = "arc-farms-mine-expeditions-v1"
    }
}

/** Owns placement allocation, receipts, async planning and bounded journal processing. */
internal class MineExpeditionWorld(
    private val plugin: Plugin,
    private val registry: MineExpeditionWorldRegistry,
    private val tasks: WorksiteTaskPort,
    private val state: WorksiteStatePort,
    private val tickets: MineChunkTicket,
    blockDataDecoder: WorksitePreparedSceneBlockDataDecoder,
) : AutoCloseable {
    private data class SceneKey(val zoneId: String, val sequence: Long, val nonce: Long)
    private class Pending(val key: SceneKey) {
        val leases = mutableListOf<AutoCloseable>()
        var nextChunk = 0
        var inFlight = 0
    }

    private val receipts = MineExpeditionSceneRepository(plugin.dataFolder.toPath())
    private val scanner = WorksiteAsyncBlockScanner(tasks)
    private val prepared = WorksitePreparedSceneOwner(
        plugin = plugin,
        namespace = "mine_expedition",
        codec = MineExpeditionSceneCodec,
        chunkRetention = WorksitePreparedSceneChunkRetention { chunk ->
            check(tickets.retain(chunk))
            AutoCloseable { tickets.release(chunk) }
        },
        blockDataDecoder = blockDataDecoder,
    )
    private val scenes = linkedMapOf<SceneKey, MineExpeditionScene>()
    private val pending = ConcurrentHashMap<SceneKey, Pending>()
    private val restoringLeases = ConcurrentHashMap<SceneKey, MutableList<AutoCloseable>>()
    private val baselineRepairs = ConcurrentHashMap<SceneKey, ArrayDeque<Pair<ExpeditionPoint, String>>>()
    private var world: World? = null

    fun initialize(): Boolean = runCatching {
        world = requireNotNull(registry.ensureWorld()) { "Could not load expedition world" }
        reconcileLoaded()
        // Receipts outlive MineShiftState so completed scenes remain protected across restart.
        receipts.records().forEach { receipt ->
            if (receipt.restoring) prepared.beginRestore(world!!, receipt.zoneId, receipt.journalSequence, receipt.sceneId)
            schedulePreparation(receipt)
        }
        true
    }.getOrElse { failure ->
        state.log(Level.SEVERE, "Mine expedition world initialization failed", failure)
        false
    }

    /** Assigns and durably receipts a placement before any prepared-scene journal mutation. */
    fun ensure(runtime: MineRuntime, surface: Location, now: Long): MineExpeditionScene? {
        require(now >= 0L)
        val incident = runtime.state.incident ?: return null
        val kind = MineExpeditionEngine.kind(incident.type) ?: return null
        val nonce = incident.objectiveNonce.takeIf { it in 1..Int.MAX_VALUE.toLong() } ?: return null
        val world = world ?: registry.ensureWorld()?.also { this.world = it } ?: return null
        require(surface.world != null) { "Expedition surface has no world" }
        val key = SceneKey(runtime.settings.id, runtime.state.sequence, nonce)
        scenes[key]?.let { scene -> return scene.takeIf { it.ready } }

        val receipt = receipts.find(runtime.settings.id, runtime.state.sequence, nonce)
        val placement = incident.expedition?.placement ?: receipt?.placement ?: allocate(kind, key)
        require(receipt == null || receipt.placement == placement) {
            "Expedition placement disagrees with its durable scene receipt"
        }
        require(placement.world == world.name) { "Expedition placement uses an unexpected world" }
        val initial = MineExpeditionEngine.initial(incident.type, placement)
        if (incident.expedition?.placement != placement) {
            runtime.state = runtime.state.copy(incident = incident.copy(expedition = initial))
        }
        val durableReceipt = receipt ?: MineExpeditionSceneReceipt(
            zoneId = key.zoneId,
            sequence = key.sequence,
            objectiveNonce = key.nonce,
            journalSequence = receipts.nextJournalSequence(),
            kind = kind,
            placement = placement,
            surfaceWorld = surface.world.name,
            surfaceX = surface.x,
            surfaceY = surface.y,
            surfaceZ = surface.z,
            surfaceYaw = surface.yaw,
            surfacePitch = surface.pitch,
        ).also { receipts.commit(it) }
        if (incident.expedition?.placement != placement || receipt == null) state.persistAsync()
        if (!pending.containsKey(key)) schedulePreparation(durableReceipt)
        return scenes[key]?.takeIf { it.ready }
    }

    fun scene(runtime: MineRuntime): MineExpeditionScene? {
        val incident = runtime.state.incident ?: return null
        val nonce = incident.objectiveNonce
        return scenes[SceneKey(runtime.settings.id, runtime.state.sequence, nonce)]
    }

    fun retainedScenes(): Collection<MineExpeditionScene> = scenes.values.toList()

    /** Journal-first bounded block projection for controller-owned machine feedback. */
    fun project(scene: MineExpeditionScene, changes: Map<ExpeditionPoint, String>): Boolean {
        require(changes.size <= 1_024) { "Expedition projection is too large" }
        require(changes.keys.all(scene.plan.bounds::contains)) { "Expedition projection escapes the scene" }
        val worldChanges = changes.mapKeys { (point, _) ->
            Triple(scene.placement.originX + point.x, scene.placement.originY + point.y, scene.placement.originZ + point.z)
        }
        return prepared.project(scene.prepared, worldChanges).also {
            val repairing = !baselineRepairs[SceneKey(scene.zoneId, scene.sequence, scene.objectiveNonce)].isNullOrEmpty()
            scene.refreshReady(repairing || prepared.isBuilding(scene.zoneId, scene.journalSequence, scene.sceneId), prepared.isComplete(scene.prepared))
        }
    }

    fun markCompleted(scene: MineExpeditionScene, now: Long) {
        receipts.markCompleted(scene.zoneId, scene.sequence, scene.objectiveNonce, now)
        scene.completedAt = now
    }

    /** Caller must evacuate players first; this only starts journal restoration. */
    fun beginRestore(scene: MineExpeditionScene) {
        if (receipts.find(scene.zoneId, scene.sequence, scene.objectiveNonce)?.restoring != false) return
        receipts.markRestoring(scene.zoneId, scene.sequence, scene.objectiveNonce)
        scene.invalidateReady()
        prepared.beginRestore(scene.world, scene.zoneId, scene.journalSequence, scene.sceneId)
    }

    fun process(limit: Int = 1_024): Int {
        require(limit in 1..4_096)
        val baselineProcessed = processBaseline(limit)
        val preparedBudget = limit - baselineProcessed
        val processed = if (preparedBudget > 0) prepared.process(preparedBudget, allowed = { record ->
            receipts.records().any {
                it.zoneId == record.zoneId && it.journalSequence == record.sequence && it.sceneId == record.sceneId
            }
        }) else 0
        scenes.values.forEach { scene ->
            if (!baselineRepairs[SceneKey(scene.zoneId, scene.sequence, scene.objectiveNonce)].isNullOrEmpty()) return@forEach
            scene.refreshReady(
                prepared.isBuilding(scene.zoneId, scene.journalSequence, scene.sceneId),
                prepared.isComplete(scene.prepared),
            )
        }
        finishRestores()
        return baselineProcessed + processed
    }

    fun onChunkLoad(chunk: Chunk) = prepared.onChunkLoad(
        chunk,
        active = { zoneId, sequence -> receipts.records().any { it.journalSequence == sequence && !it.restoring } },
        isActiveScene = { record -> receipts.records().any {
            it.zoneId == record.zoneId && it.journalSequence == record.sequence && it.sceneId == record.sceneId && !it.restoring
        } },
    )

    fun protects(location: Location): Boolean = location.world.name == MineExpeditionWorldGenerator.WORLD_NAME

    fun reconcileLoaded() = prepared.reconcileLoaded(
        active = { zoneId, sequence -> receipts.records().any { it.journalSequence == sequence && !it.restoring } },
        isActiveScene = { record -> receipts.records().any {
            it.zoneId == record.zoneId && it.journalSequence == record.sequence && it.sceneId == record.sceneId && !it.restoring
        } },
    )

    fun beforeReload() {
        pending.values.forEach { pending -> pending.leases.forEach { lease -> runCatching(lease::close) } }
        restoringLeases.values.forEach { leases -> leases.forEach { lease -> runCatching(lease::close) } }
        pending.clear()
        restoringLeases.clear()
        baselineRepairs.clear()
        prepared.clearQueues()
        scenes.clear()
    }

    override fun close() {
        beforeReload()
        prepared.clearQueues()
        receipts.close()
    }

    private fun schedulePreparation(receipt: MineExpeditionSceneReceipt) {
        val key = SceneKey(receipt.zoneId, receipt.sequence, receipt.objectiveNonce)
        if (pending.putIfAbsent(key, Pending(key)) != null) return
        val token = tasks.lifecycleToken()
        if (!tasks.runAsync(token) {
            val result = runCatching { MineExpeditionGenerator.plan(receipt.kind, receipt.placement.seed) }
            if (!tasks.runSync(token) {
                result.onSuccess { plan -> capturePlan(receipt, plan, key) }
                    .onFailure { failure -> failPreparation(key, failure) }
            }) failPreparation(key, IllegalStateException("Could not schedule expedition capture"))
        }) failPreparation(key, IllegalStateException("Could not schedule expedition planning"))
    }

    private fun capturePlan(receipt: MineExpeditionSceneReceipt, plan: MineExpeditionPlan, key: SceneKey) {
        val world = world ?: return failPreparation(key, IllegalStateException("Expedition world disappeared"))
        val coordinates = plan.blocks.keys.map { local ->
            WorksiteChunkCoordinate(
                (receipt.placement.originX + local.x) shr 4,
                (receipt.placement.originZ + local.z) shr 4,
            )
        }.distinct()
        val pendingState = pending[key] ?: return
        preloadChunks(receipt, plan, key, coordinates, pendingState)
    }

    private fun preloadChunks(
        receipt: MineExpeditionSceneReceipt,
        plan: MineExpeditionPlan,
        key: SceneKey,
        coordinates: List<WorksiteChunkCoordinate>,
        pendingState: Pending,
    ) {
        val world = world ?: return failPreparation(key, IllegalStateException("Expedition world disappeared"))
        if (receipts.find(key.zoneId, key.sequence, key.nonce) == null) {
            failPreparation(key, IllegalStateException("Expedition receipt is no longer active"))
            return
        }
        val token = tasks.lifecycleToken()
        while (pendingState.nextChunk < coordinates.size && pendingState.inFlight < MAX_CHUNK_REQUESTS) {
            val coordinate = coordinates[pendingState.nextChunk++]
            pendingState.inFlight++
            val future = runCatching { world.getChunkAtAsync(coordinate.x, coordinate.z, true) }
                .getOrElse { failure ->
                    pendingState.inFlight--
                    failPreparation(key, failure)
                    return
                }
            future.whenComplete { chunk, failure ->
                if (!tasks.runSync(token) {
                    pendingState.inFlight--
                    if (pending[key] !== pendingState) return@runSync
                    if (failure != null || chunk == null || chunk.world !== world ||
                        chunk.x != coordinate.x || chunk.z != coordinate.z
                    ) {
                        failPreparation(key, failure ?: IllegalStateException("Expedition chunk preload returned wrong chunk"))
                        return@runSync
                    }
                    check(tickets.retain(chunk))
                    pendingState.leases += AutoCloseable { tickets.release(chunk) }
                    if (pendingState.nextChunk >= coordinates.size && pendingState.inFlight == 0) {
                        captureSnapshot(receipt, plan, key, coordinates)
                    } else {
                        preloadChunks(receipt, plan, key, coordinates, pendingState)
                    }
                }) failPreparation(key, IllegalStateException("Could not join expedition chunk preload"))
            }
        }
    }

    private fun captureSnapshot(
        receipt: MineExpeditionSceneReceipt,
        plan: MineExpeditionPlan,
        key: SceneKey,
        coordinates: List<WorksiteChunkCoordinate>,
    ) {
        val world = world ?: return failPreparation(key, IllegalStateException("Expedition world disappeared"))
        prepared.scene(
            world, receipt.zoneId, receipt.journalSequence, receipt.sceneId, surfaceFor(receipt, world), 0,
            start = at(world, receipt.placement, plan.spawn), end = at(world, receipt.placement, plan.exit),
        )?.let { recovered ->
            val leases = pending.remove(key)?.leases.orEmpty()
            val scene = MineExpeditionScene(
                plan, receipt.placement, world, receipt.zoneId, receipt.sequence, receipt.objectiveNonce,
                receipt.journalSequence, surfaceFor(receipt, world), recovered, receipt.completedAt,
            )
            scenes[key] = scene
            if (!receipt.restoring) {
                val baseline = baselineChanges(receipt, plan, recovered)
                if (baseline.isEmpty()) scene.refreshReady(
                    prepared.isBuilding(scene.zoneId, scene.journalSequence, scene.sceneId),
                    prepared.isComplete(scene.prepared),
                ) else baselineRepairs[key] = ArrayDeque(baseline)
            }
            if (receipt.restoring) {
                prepared.beginRestore(world, receipt.zoneId, receipt.journalSequence, receipt.sceneId)
                // The prepared owner retains the scene's chunks; release only preload leases.
                leases.forEach { lease -> runCatching(lease::close) }
            } else leases.forEach { lease -> runCatching(lease::close) }
            return
        }
        if (receipt.restoring) {
            val leases = pending.remove(key)?.leases.orEmpty()
            restoringLeases[key] = leases.toMutableList()
            prepared.beginRestore(world, receipt.zoneId, receipt.journalSequence, receipt.sceneId)
            return
        }
        if (prepared.hasLoadedSceneRecords(world, receipt.zoneId, receipt.journalSequence, receipt.sceneId)) {
            // A crash between preparation slices leaves a partial ledger. Restore
            // its exact originals before capturing a new, complete baseline.
            prepared.beginRestore(world, receipt.zoneId, receipt.journalSequence, receipt.sceneId)
            awaitPartialRestore(receipt, plan, key, coordinates)
            return
        }
        val submitted = scanner.submit(
            world = world,
            chunkCoordinates = coordinates,
            stillValid = { receipts.find(key.zoneId, key.sequence, key.nonce)?.restoring == false },
            plan = { snapshot -> records(receipt, plan, snapshot) },
            complete = { result ->
                pending.remove(key)?.leases?.forEach { lease -> runCatching(lease::close) }
                result.onSuccess { records -> install(receipt, plan, records) }
                    .onFailure { failure -> failPreparation(key, failure) }
            },
        )
        if (!submitted) failPreparation(key, IllegalStateException("Could not schedule expedition snapshot"))
    }

    private fun awaitPartialRestore(receipt: MineExpeditionSceneReceipt, plan: MineExpeditionPlan,
        key: SceneKey, coordinates: List<WorksiteChunkCoordinate>) {
        val token = tasks.lifecycleToken()
        if (!tasks.runLater(token, 2L) {
            if (!pending.containsKey(key)) return@runLater
            val currentWorld = world ?: return@runLater
            if (prepared.hasLoadedSceneRecords(currentWorld, receipt.zoneId, receipt.journalSequence, receipt.sceneId)) {
                awaitPartialRestore(receipt, plan, key, coordinates)
            } else captureSnapshot(receipt, plan, key, coordinates)
        }) failPreparation(key, IllegalStateException("Could not await partial expedition restoration"))
    }

    private fun baselineChanges(
        receipt: MineExpeditionSceneReceipt,
        plan: MineExpeditionPlan,
        preparedScene: WorksitePreparedScene,
    ): List<Pair<ExpeditionPoint, String>> = preparedScene.records.mapNotNull { record ->
        val local = ExpeditionPoint(
            record.x - receipt.placement.originX,
            record.y - receipt.placement.originY,
            record.z - receipt.placement.originZ,
        )
        val baseline = plan.blocks[local] ?: record.originalData
        baseline.takeIf { it != record.activeData }?.let { local to it }
    }

    private fun processBaseline(limit: Int): Int {
        var remaining = limit
        var processed = 0
        baselineRepairs.entries.toList().forEach { (key, queue) ->
            if (remaining == 0) return@forEach
            val scene = scenes[key] ?: return@forEach
            val batch = buildList {
                repeat(minOf(remaining, queue.size)) { add(queue.removeFirst()) }
            }
            if (batch.isEmpty()) {
                baselineRepairs.remove(key, queue)
                return@forEach
            }
            if (project(scene, batch.toMap())) {
                processed += batch.size
                remaining -= batch.size
                if (queue.isEmpty()) baselineRepairs.remove(key, queue)
            } else {
                batch.asReversed().forEach(queue::addFirst)
            }
        }
        return processed
    }

    private fun records(
        receipt: MineExpeditionSceneReceipt,
        plan: MineExpeditionPlan,
        snapshot: WorksiteBlockSnapshot,
    ): List<WorksitePreparedSceneRecord> = plan.blocks.entries.map { (local, active) ->
        val x = receipt.placement.originX + local.x
        val y = receipt.placement.originY + local.y
        val z = receipt.placement.originZ + local.z
        val material = requireNotNull(snapshot.type(WorksitePosition(MineExpeditionWorldGenerator.WORLD_NAME, x, y, z))) {
            "Expedition snapshot missed $x,$y,$z"
        }
        WorksitePreparedSceneRecord(
            world = MineExpeditionWorldGenerator.WORLD_NAME,
            zoneId = receipt.zoneId,
            sequence = receipt.journalSequence,
            sceneId = receipt.sceneId,
            x = x,
            y = y,
            z = z,
            originalData = material.key.toString(),
            activeData = active,
            marker = "NONE",
            totalRecords = plan.blocks.size,
        )
    }.sortedWith(compareBy(WorksitePreparedSceneRecord::x, WorksitePreparedSceneRecord::y, WorksitePreparedSceneRecord::z))

    private fun install(receipt: MineExpeditionSceneReceipt, plan: MineExpeditionPlan, records: List<WorksitePreparedSceneRecord>) {
        val world = world ?: return
        val surface = surfaceFor(receipt, world)
        val preparedScene = WorksitePreparedScene(
            world, receipt.zoneId, receipt.journalSequence, receipt.sceneId, surface,
            Location(world, receipt.placement.originX + plan.spawn.x + 0.5, receipt.placement.originY + plan.spawn.y.toDouble(), receipt.placement.originZ + plan.spawn.z + 0.5),
            Location(world, receipt.placement.originX + plan.exit.x + 0.5, receipt.placement.originY + plan.exit.y.toDouble(), receipt.placement.originZ + plan.exit.z + 0.5),
            records,
        )
        check(prepared.prepareIncrementally(preparedScene)) { "Could not begin expedition scene preparation" }
        scenes[SceneKey(receipt.zoneId, receipt.sequence, receipt.objectiveNonce)] = MineExpeditionScene(
            plan, receipt.placement, world, receipt.zoneId, receipt.sequence, receipt.objectiveNonce, receipt.journalSequence, surface,
            preparedScene, receipt.completedAt,
        )
    }

    private fun surfaceFor(receipt: MineExpeditionSceneReceipt, fallbackWorld: World): Location =
        Location(
            Bukkit.getWorld(receipt.surfaceWorld) ?: fallbackWorld,
            receipt.surfaceX,
            receipt.surfaceY,
            receipt.surfaceZ,
            receipt.surfaceYaw,
            receipt.surfacePitch,
        )

    private fun at(world: World, placement: MineExpeditionPlacement, point: ExpeditionPoint): Location = Location(
        world,
        placement.originX + point.x + 0.5,
        placement.originY + point.y.toDouble(),
        placement.originZ + point.z + 0.5,
    )

    private fun finishRestores() {
        receipts.records().filter { it.restoring }.forEach { receipt ->
            val key = SceneKey(receipt.zoneId, receipt.sequence, receipt.objectiveNonce)
            if (pending.containsKey(key)) return@forEach
            val scene = scenes[key]
            if (scene == null) {
                if (prepared.hasLoadedSceneRecords(world ?: return@forEach, receipt.zoneId, receipt.journalSequence, receipt.sceneId)) {
                    return@forEach
                }
                receipts.remove(receipt.zoneId, receipt.sequence, receipt.objectiveNonce)
                baselineRepairs.remove(key)
                restoringLeases.remove(key)?.forEach { lease -> runCatching(lease::close) }
                return@forEach
            }
            // A partially restored journal is expected to fail scene reconstruction because its
            // records no longer reach totalRecords. Presence, rather than reconstruction, is the
            // durable completion test while all scene chunks remain ticketed.
            if (!prepared.hasLoadedSceneRecords(scene.world, scene.zoneId, scene.journalSequence, scene.sceneId)) {
                receipts.remove(scene.zoneId, scene.sequence, scene.objectiveNonce)
                baselineRepairs.remove(key)
                scenes.remove(key)
            }
        }
    }

    private fun failPreparation(key: SceneKey, failure: Throwable) {
        pending.remove(key)?.leases?.forEach { lease -> runCatching(lease::close) }
        state.log(Level.WARNING, "Mine expedition preparation failed zone=${key.zoneId} sequence=${key.sequence} nonce=${key.nonce}", failure)
    }

    private fun allocate(kind: MineExpeditionKind, key: SceneKey): MineExpeditionPlacement {
        val seed = WorksiteDeterministicSeed.derive(key.sequence, key.zoneId.hashCode().toLong() xor key.nonce xor kind.ordinal.toLong())
        val occupied = receipts.records().map { it.placement to bounds(it.kind) }
        repeat(MAX_ALLOCATION_PROBES) { probe ->
            val score = WorksiteDeterministicSeed.gridScore(seed, probe, key.zoneId.hashCode())
            val gx = Math.floorMod(score, GRID_SIZE.toLong()).toInt() - GRID_SIZE / 2
            val gz = Math.floorMod(score ushr 32, GRID_SIZE.toLong()).toInt() - GRID_SIZE / 2
            val candidate = MineExpeditionPlacement(MineExpeditionWorldGenerator.WORLD_NAME, gx * CELL_SIZE, 0, gz * CELL_SIZE, seed)
            val candidateBounds = bounds(kind)
            if (occupied.none { (placement, bounds) -> overlaps(candidate, candidateBounds, placement, bounds) }) return candidate
        }
        error("No bounded expedition allocation cell is available")
    }

    private fun overlaps(a: MineExpeditionPlacement, aBounds: Bounds, b: MineExpeditionPlacement, bBounds: Bounds): Boolean =
        a.originX + aBounds.minX < b.originX + bBounds.maxX + 1 &&
            a.originX + aBounds.maxX + 1 > b.originX + bBounds.minX &&
            a.originZ + aBounds.minZ < b.originZ + bBounds.maxZ + 1 &&
            a.originZ + aBounds.maxZ + 1 > b.originZ + bBounds.minZ

    private fun bounds(kind: MineExpeditionKind): Bounds = when (kind) {
        MineExpeditionKind.LAST_DESCENT -> Bounds(-34, 34, -34, 34)
        MineExpeditionKind.DRILLING_ARK -> Bounds(-48, 48, -32, 32)
        MineExpeditionKind.DEAD_FACTORY -> Bounds(-34, 34, -28, 31)
    }

    private data class Bounds(val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int)

    companion object {
        const val CELL_SIZE = 128
        const val GRID_SIZE = 4_096
        const val MAX_ALLOCATION_PROBES = 4_096
        const val MAX_CHUNK_REQUESTS = 8
    }
}
