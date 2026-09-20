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
    private val receipts: MineExpeditionSceneRepository = MineExpeditionSceneRepository(plugin.dataFolder.toPath()),
) : AutoCloseable, MineExpeditionSceneLoader {
    private class Pending(val key: Long, val startedAt: Long = System.currentTimeMillis()) {
        val leases = mutableListOf<AutoCloseable>()
        var nextChunk = 0
        var inFlight = 0
    }

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
    private val scenes = linkedMapOf<Long, MineExpeditionScene>()
    private val stock = MineExpeditionStock(receipts, tasks, state, this)
    private val failures = ConcurrentHashMap<Long, Pair<Long, String>>()
    private val buildingSince = hashMapOf<Long, Long>()
    private val announcedReady = hashSetOf<Long>()
    private var initializing = false
    private var storageLoaded = false
    private var prewarm = false
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val restoringLeases = ConcurrentHashMap<Long, MutableList<AutoCloseable>>()
    private val baselineRepairs = ConcurrentHashMap<Long, ArrayDeque<Pair<ExpeditionPoint, String>>>()
    private var world: World? = null

    fun initialize(prewarm: Boolean = true) {
        this.prewarm = prewarm
        if (initializing) return
        initializing = true
        val token = tasks.lifecycleToken()
        receipts.ready.whenComplete { _, failure ->
            tasks.runSync(token) {
                runCatching {
                    if (failure != null) throw failure
                    storageLoaded = true
                    if (prewarm || receipts.records().isNotEmpty()) {
                        world = requireNotNull(registry.ensureWorld()) { "Could not load expedition world" }
                        reconcileLoaded()
                        receipts.records().forEach(::schedulePreparation)
                    }
                    stock.activate()
                }.onFailure {
                    initializing = false
                    state.log(Level.SEVERE, "Mine expedition world initialization failed", it)
                }
            }
        }
    }

    fun ensure(runtime: MineRuntime, surface: Location, now: Long): MineExpeditionScene? {
        require(now >= 0L)
        return stock.ensure(runtime, surface)
    }

    fun available(type: ru.ruscrafting.farms.domain.MineIncidentType): Boolean = stock.available(type)
    fun failure(runtime: MineRuntime): String? = stock.failure(runtime)
    fun stockStatus(): List<MineExpeditionStockStatus> = stock.status()
    fun rebuildStock(kind: MineExpeditionKind?): Int = stock.rebuild(kind)

    fun scene(runtime: MineRuntime): MineExpeditionScene? = runtime.state.incident?.let { incident ->
        scenes.values.firstOrNull { !it.reserved && it.zoneId == runtime.settings.id &&
            it.sequence == runtime.state.sequence && it.objectiveNonce == incident.objectiveNonce }
    }

    fun retainedScenes(): Collection<MineExpeditionScene> = scenes.values.filterNot { it.reserved }

    /** Journal-first bounded block projection for controller-owned machine feedback. */
    fun project(scene: MineExpeditionScene, changes: Map<ExpeditionPoint, String>): Boolean {
        require(changes.size <= 1_024) { "Expedition projection is too large" }
        require(changes.keys.all(scene.plan.bounds::contains)) { "Expedition projection escapes the scene" }
        val worldChanges = changes.mapKeys { (point, _) ->
            Triple(scene.placement.originX + point.x, scene.placement.originY + point.y, scene.placement.originZ + point.z)
        }
        return prepared.project(scene.prepared, worldChanges).also {
            val repairing = !baselineRepairs[scene.journalSequence].isNullOrEmpty()
            scene.refreshReady(repairing || prepared.isBuilding(scene.journalOwner, scene.journalSequence, scene.sceneId), prepared.isComplete(scene.prepared))
        }
    }

    fun markCompleted(scene: MineExpeditionScene, now: Long) = stock.complete(scene, now)

    /** Caller evacuates first. Restoration starts only after its durable receipt commit. */
    fun beginRestore(scene: MineExpeditionScene) {
        receipts.findJournal(scene.journalSequence)?.let(stock::retire)
    }

    fun retireUnbuilt(runtime: MineRuntime) {
        val incident = runtime.state.incident ?: return
        receipts.find(runtime.settings.id, runtime.state.sequence, incident.objectiveNonce)?.let(stock::retire)
    }

    override fun prepared(id: Long): MineExpeditionScene? = scenes[id]
    override fun failure(id: Long): String? = failures[id]?.second
    override fun prepare(receipt: MineExpeditionSceneReceipt) {
        if (world == null || scenes.containsKey(receipt.journalSequence) || pending.containsKey(receipt.journalSequence)) return
        if (System.currentTimeMillis() < (failures[receipt.journalSequence]?.first ?: 0L)) return
        schedulePreparation(receipt)
    }
    override fun restore(receipt: MineExpeditionSceneReceipt) {
        val world = world ?: return
        val key = receipt.journalSequence
        pending.remove(key)?.leases?.forEach { runCatching(it::close) }
        baselineRepairs.remove(key)
        prepared.beginRestore(world, receipt.journalOwner, key, receipt.sceneId)
        if (scenes[key] == null) schedulePreparation(receipt)
    }

    fun process(limit: Int = 1_024): Int {
        require(limit in 1..4_096)
        if (!storageLoaded) return 0
        val now = System.currentTimeMillis()
        pending.values.filter { now - it.startedAt > PREPARATION_TIMEOUT }.forEach {
            failPreparation(it.key, IllegalStateException("Expedition chunk preparation timed out"))
        }
        receipts.records().filter { it.restoring && !restoringLeases.containsKey(it.journalSequence) }.forEach(::prepare)
        if (prewarm) stock.maintain(now)
        val baselineProcessed = processBaseline(limit)
        val preparedBudget = limit - baselineProcessed
        val processed = if (preparedBudget > 0) prepared.process(preparedBudget, allowed = { record ->
            receipts.records().any {
                it.journalOwner == record.zoneId && it.journalSequence == record.sequence && it.sceneId == record.sceneId
            }
        }) else 0
        scenes.values.forEach { scene ->
            if (!baselineRepairs[scene.journalSequence].isNullOrEmpty()) return@forEach
            scene.refreshReady(
                prepared.isBuilding(scene.journalOwner, scene.journalSequence, scene.sceneId),
                prepared.isComplete(scene.prepared),
            )
            if (scene.ready && announcedReady.add(scene.journalSequence)) {
                buildingSince.remove(scene.journalSequence)
                state.log(Level.INFO, "Mine expedition ready kind=${scene.kind} journal=${scene.journalSequence} reserve=${scene.reserved}")
            } else if (!scene.ready && now - (buildingSince[scene.journalSequence] ?: now) > PREPARATION_TIMEOUT) {
                buildingSince.remove(scene.journalSequence)
                failPreparation(scene.journalSequence, IllegalStateException("Expedition block preparation timed out"))
                receipts.findJournal(scene.journalSequence)?.let(stock::retire)
            }
        }
        finishRestores()
        return baselineProcessed + processed
    }

    fun onChunkLoad(chunk: Chunk) {
        if (!storageLoaded) return
        prepared.onChunkLoad(chunk,
            active = { owner, id -> receipts.records().any { it.journalOwner == owner && it.journalSequence == id && !it.restoring } },
            isActiveScene = { record -> receipts.records().any { it.journalOwner == record.zoneId &&
                it.journalSequence == record.sequence && it.sceneId == record.sceneId && !it.restoring } },
        )
    }

    fun protects(location: Location): Boolean = location.world.name == MineExpeditionWorldGenerator.WORLD_NAME

    fun reconcileLoaded() {
        if (!storageLoaded) return
        prepared.reconcileLoaded(
            active = { owner, id -> receipts.records().any { it.journalOwner == owner && it.journalSequence == id && !it.restoring } },
            isActiveScene = { record -> receipts.records().any { it.journalOwner == record.zoneId &&
                it.journalSequence == record.sequence && it.sceneId == record.sceneId && !it.restoring } },
        )
    }

    fun beforeReload() {
        stock.deactivate()
        initializing = false
        storageLoaded = false
        failures.clear()
        buildingSince.clear()
        announcedReady.clear()
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
        receipts.flushAndClose()
    }

    private fun schedulePreparation(receipt: MineExpeditionSceneReceipt) {
        val key = receipt.journalSequence
        failures.remove(key)
        val work = Pending(key)
        if (pending.putIfAbsent(key, work) != null) return
        val token = tasks.lifecycleToken()
        if (!tasks.runAsync(token) {
            val result = runCatching { MineExpeditionGenerator.plan(receipt.kind, receipt.placement.seed) }
            if (!tasks.runSync(token) {
                if (pending[key] !== work) return@runSync
                result.onSuccess { plan -> capturePlan(receipt, plan, key) }
                    .onFailure { failure -> failPreparation(key, failure) }
            }) return@runAsync // Lifecycle invalidation owns lease cleanup on the Paper thread.
        }) failPreparation(key, IllegalStateException("Could not schedule expedition planning"))
    }

    private fun capturePlan(receipt: MineExpeditionSceneReceipt, plan: MineExpeditionPlan, key: Long) {
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
        key: Long,
        coordinates: List<WorksiteChunkCoordinate>,
        pendingState: Pending,
    ) {
        val world = world ?: return failPreparation(key, IllegalStateException("Expedition world disappeared"))
        if (receipts.findJournal(key) == null) {
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
                }) return@whenComplete // A stale lifecycle never touches chunk tickets.
            }
        }
    }

    private fun captureSnapshot(
        receipt: MineExpeditionSceneReceipt,
        plan: MineExpeditionPlan,
        key: Long,
        coordinates: List<WorksiteChunkCoordinate>,
    ) {
        val world = world ?: return failPreparation(key, IllegalStateException("Expedition world disappeared"))
        prepared.scene(
            world, receipt.journalOwner, receipt.journalSequence, receipt.sceneId, surfaceFor(receipt, world), 0,
            start = at(world, receipt.placement, plan.spawn), end = at(world, receipt.placement, plan.exit),
        )?.let { recovered ->
            val leases = pending.remove(key)?.leases.orEmpty()
            val scene = MineExpeditionScene(
                plan, receipt.placement, world, receipt.zoneId, receipt.sequence, receipt.objectiveNonce,
                receipt.journalSequence, surfaceFor(receipt, world), recovered, receipt.completedAt, receipt.reserved,
            )
            scenes[key] = scene
            if (!receipt.restoring) {
                buildingSince[key] = System.currentTimeMillis()
                val baseline = baselineChanges(receipt, plan, recovered)
                if (baseline.isEmpty()) scene.refreshReady(
                    prepared.isBuilding(scene.journalOwner, scene.journalSequence, scene.sceneId),
                    prepared.isComplete(scene.prepared),
                ) else baselineRepairs[key] = ArrayDeque(baseline)
            }
            if (receipt.restoring) {
                prepared.beginRestore(world, receipt.journalOwner, receipt.journalSequence, receipt.sceneId)
                // The prepared owner retains the scene's chunks; release only preload leases.
                leases.forEach { lease -> runCatching(lease::close) }
            } else leases.forEach { lease -> runCatching(lease::close) }
            return
        }
        if (receipt.restoring) {
            val leases = pending.remove(key)?.leases.orEmpty()
            restoringLeases[key] = leases.toMutableList()
            prepared.beginRestore(world, receipt.journalOwner, receipt.journalSequence, receipt.sceneId)
            return
        }
        if (prepared.hasLoadedSceneRecords(world, receipt.journalOwner, receipt.journalSequence, receipt.sceneId)) {
            // A crash between preparation slices leaves a partial ledger. Restore
            // its exact originals before capturing a new, complete baseline.
            prepared.beginRestore(world, receipt.journalOwner, receipt.journalSequence, receipt.sceneId)
            awaitPartialRestore(receipt, plan, key, coordinates)
            return
        }
        val capturing = pending[key] ?: return
        val submitted = scanner.submit(
            world = world,
            chunkCoordinates = coordinates,
            stillValid = { pending[key] === capturing && receipts.findJournal(key)?.restoring == false },
            plan = { snapshot -> records(receipt, plan, snapshot) },
            complete = { result ->
                if (pending[key] !== capturing) return@submit
                pending.remove(key)?.leases?.forEach { lease -> runCatching(lease::close) }
                result.fold({ records -> runCatching { install(receipt, plan, records) }
                    .onFailure { failure -> failPreparation(key, failure) } }, { failure -> failPreparation(key, failure) })
            },
        )
        if (!submitted) failPreparation(key, IllegalStateException("Could not schedule expedition snapshot"))
    }

    private fun awaitPartialRestore(receipt: MineExpeditionSceneReceipt, plan: MineExpeditionPlan,
        key: Long, coordinates: List<WorksiteChunkCoordinate>) {
        val token = tasks.lifecycleToken()
        if (!tasks.runLater(token, 2L) {
            if (!pending.containsKey(key)) return@runLater
            val currentWorld = world ?: return@runLater
            if (prepared.hasLoadedSceneRecords(currentWorld, receipt.journalOwner, receipt.journalSequence, receipt.sceneId)) {
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
            zoneId = receipt.journalOwner,
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
            world, receipt.journalOwner, receipt.journalSequence, receipt.sceneId, surface,
            Location(world, receipt.placement.originX + plan.spawn.x + 0.5, receipt.placement.originY + plan.spawn.y.toDouble(), receipt.placement.originZ + plan.spawn.z + 0.5),
            Location(world, receipt.placement.originX + plan.exit.x + 0.5, receipt.placement.originY + plan.exit.y.toDouble(), receipt.placement.originZ + plan.exit.z + 0.5),
            records,
        )
        check(prepared.prepareIncrementally(preparedScene)) { "Could not begin expedition scene preparation" }
        buildingSince[receipt.journalSequence] = System.currentTimeMillis()
        scenes[receipt.journalSequence] = MineExpeditionScene(
            plan, receipt.placement, world, receipt.zoneId, receipt.sequence, receipt.objectiveNonce, receipt.journalSequence, surface,
            preparedScene, receipt.completedAt, receipt.reserved,
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
            val key = receipt.journalSequence
            if (pending.containsKey(key)) return@forEach
            val scene = scenes[key]
            if (scene == null) {
                // Only retire the receipt after every relevant chunk has been loaded and inspected.
                if (!restoringLeases.containsKey(key)) return@forEach
                if (prepared.hasLoadedSceneRecords(world ?: return@forEach, receipt.journalOwner, receipt.journalSequence, receipt.sceneId)) {
                    return@forEach
                }
                stock.remove(receipt) {
                    baselineRepairs.remove(key)
                    failures.remove(key)
                    restoringLeases.remove(key)?.forEach { lease -> runCatching(lease::close) }
                }
                return@forEach
            }
            // A partially restored journal is expected to fail scene reconstruction because its
            // records no longer reach totalRecords. Presence, rather than reconstruction, is the
            // durable completion test while all scene chunks remain ticketed.
            if (!prepared.hasLoadedSceneRecords(scene.world, scene.journalOwner, scene.journalSequence, scene.sceneId)) {
                stock.remove(receipt) {
                    baselineRepairs.remove(key)
                    failures.remove(key)
                    scenes.remove(key)
                }
            }
        }
    }

    private fun failPreparation(key: Long, failure: Throwable) {
        pending.remove(key)?.leases?.forEach { lease -> runCatching(lease::close) }
        failures[key] = (System.currentTimeMillis() + 30_000L) to (failure.message ?: failure.javaClass.simpleName)
        state.log(Level.WARNING, "Mine expedition preparation failed journal=$key retryInSeconds=30", failure)
    }

    companion object {
        const val MAX_CHUNK_REQUESTS = 8
        const val PREPARATION_TIMEOUT = 180_000L
    }
}
