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
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryLine
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

/** Pure decision table used by the bounded live-floor migration and its tests. */
internal object MineFactoryFloorMigration {
    enum class Decision { PROJECT, COMPLETE, PRESERVE }

    fun repairPending(baselinePending: Boolean, floorPending: Boolean): Boolean =
        baselinePending || floorPending

    fun decide(recordedActive: String, current: String, former: String, desired: String): Decision {
        if (former == desired || recordedActive !in setOf(former, desired)) return Decision.PRESERVE
        return when (current) {
            desired -> Decision.COMPLETE
            former -> Decision.PROJECT
            else -> Decision.PRESERVE
        }
    }
}

/** Bukkit world owner used when the plugin does not provide its own world registry. */
internal class BukkitMineExpeditionWorldRegistry(private val plugin: Plugin) : MineExpeditionWorldRegistry {
    // Compatibility lookup for restoring legacy receipts; never create another expedition world.
    override fun ensureWorld(): World? = Bukkit.getWorld(MineExpeditionWorldGenerator.WORLD_NAME)

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
        preserveEdits = { record -> receipts.findJournal(record.sequence)?.let { it.siteBuilt && it.placement.geometryVersion >= 3 && !it.restoring } == true },
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
    private data class FactoryFloorRepair(
        val point: ExpeditionPoint,
        val former: String,
        val desired: String,
        val recordedActive: String,
    )
    private val factoryFloorRepairs = ConcurrentHashMap<Long, ArrayDeque<FactoryFloorRepair>>()
    private fun receiptWorld(receipt: MineExpeditionSceneReceipt): World? =
        Bukkit.getWorld(receipt.placement.world) ?: registry.ensureWorld()?.takeIf { it.name == receipt.placement.world }

    private fun refreshReady(scene: MineExpeditionScene) {
        val repairing = MineFactoryFloorMigration.repairPending(
            baselinePending = !baselineRepairs[scene.journalSequence].isNullOrEmpty(),
            floorPending = !factoryFloorRepairs[scene.journalSequence].isNullOrEmpty(),
        )
        scene.refreshReady(
            repairing || prepared.isBuilding(scene.journalOwner, scene.journalSequence, scene.sceneId),
            prepared.isComplete(scene.prepared),
        )
    }

    fun configure(runtime: MineRuntime, surface: Location) {
        val bounds = runtime.region.bounds
        stock.site = MineExpeditionSite(runtime.region.world.name, (bounds.minX + bounds.maxX) / 2,
            bounds.minZ, surface.blockY, surface.x, surface.y, surface.z)
    }

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
                        reconcileLoaded()
                        receipts.records().forEach { receipt ->
                            if (receipt.placement.geometryVersion < 2 && !receipt.restoring) stock.retire(receipt)
                            else schedulePreparation(receipt)
                        }
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

    fun allScenes(): Collection<MineExpeditionScene> = scenes.values.toList()

    fun releaseStatic(scene: MineExpeditionScene) = stock.release(scene)

    fun retainedScenes(): Collection<MineExpeditionScene> = scenes.values.filterNot { it.reserved }

    /** Journal-first bounded block projection for controller-owned machine feedback. */
    fun project(scene: MineExpeditionScene, changes: Map<ExpeditionPoint, String>): Boolean {
        require(changes.size <= 1_024) { "Expedition projection is too large" }
        require(changes.keys.all(scene.plan.bounds::contains)) { "Expedition projection escapes the scene" }
        val worldChanges = changes.mapKeys { (point, _) ->
            Triple(scene.placement.originX + point.x, scene.placement.originY + point.y, scene.placement.originZ + point.z)
        }
        return prepared.project(scene.prepared, worldChanges).also {
            refreshReady(scene)
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
        if (receiptWorld(receipt) == null || scenes.containsKey(receipt.journalSequence) || pending.containsKey(receipt.journalSequence)) return
        if (System.currentTimeMillis() < (failures[receipt.journalSequence]?.first ?: 0L)) return
        schedulePreparation(receipt)
    }
    override fun restore(receipt: MineExpeditionSceneReceipt) {
        val world = receiptWorld(receipt) ?: return
        val key = receipt.journalSequence
        pending.remove(key)?.leases?.forEach { runCatching(it::close) }
        baselineRepairs.remove(key)
        factoryFloorRepairs.remove(key)
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
        val floorProcessed = processFactoryFloorRepairs(minOf(limit, FACTORY_FLOOR_BATCH))
        val baselineProcessed = processBaseline(limit - floorProcessed)
        val preparedBudget = limit - floorProcessed - baselineProcessed
        val processed = if (preparedBudget > 0) prepared.process(preparedBudget, allowed = { record ->
            receipts.records().any {
                it.journalOwner == record.zoneId && it.journalSequence == record.sequence && it.sceneId == record.sceneId
            }
        }) else 0
        scenes.values.forEach { scene ->
            refreshReady(scene)
            if (scene.ready) stock.markBuilt(scene)
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
        return floorProcessed + baselineProcessed + processed
    }

    fun onChunkLoad(chunk: Chunk) {
        if (!storageLoaded) return
        prepared.onChunkLoad(chunk,
            active = { owner, id -> receipts.records().any { it.journalOwner == owner && it.journalSequence == id && !it.restoring } },
            isActiveScene = { record -> receipts.records().any { it.journalOwner == record.zoneId &&
                it.journalSequence == record.sequence && it.sceneId == record.sceneId && !it.restoring } },
        )
    }

    fun protects(location: Location): Boolean = scenes.values.any {
        it.contains(location) && (!it.ready || (!it.reserved && it.completedAt == 0L))
    }

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
        factoryFloorRepairs.clear()
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
            val result = runCatching { MineExpeditionGenerator.plan(receipt.kind, receipt.placement.seed, receipt.placement.geometryVersion) }
            if (!tasks.runSync(token) {
                if (pending[key] !== work) return@runSync
                result.onSuccess { plan -> capturePlan(receipt, plan, key) }
                    .onFailure { failure -> failPreparation(key, failure) }
            }) return@runAsync // Lifecycle invalidation owns lease cleanup on the Paper thread.
        }) failPreparation(key, IllegalStateException("Could not schedule expedition planning"))
    }

    private fun capturePlan(receipt: MineExpeditionSceneReceipt, plan: MineExpeditionPlan, key: Long) {
        val world = receiptWorld(receipt) ?: return failPreparation(key, IllegalStateException("Expedition world disappeared"))
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
        val world = receiptWorld(receipt) ?: return failPreparation(key, IllegalStateException("Expedition world disappeared"))
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
        val world = receiptWorld(receipt) ?: return failPreparation(key, IllegalStateException("Expedition world disappeared"))
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
                val baseline = if (receipt.placement.geometryVersion >= 3) emptyList() else baselineChanges(receipt, plan, recovered)
                if (baseline.isEmpty()) baselineRepairs.remove(key)
                else baselineRepairs[key] = ArrayDeque(baseline)
                scheduleFactoryFloorMigration(receipt, plan, scene)
                refreshReady(scene)
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
            val currentWorld = receiptWorld(receipt) ?: return@runLater
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
        if (limit <= 0) return 0
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

    /**
     * Reconciles only the authored floor palette of existing permanent factory
     * scenes. Every live read and projection is bounded to the normal owner
     * budget; a player-edited block is consumed from the queue without being
     * changed.
     */
    private fun processFactoryFloorRepairs(limit: Int): Int {
        if (limit <= 0) return 0
        var remaining = limit
        var processed = 0
        factoryFloorRepairs.entries.toList().forEach { (key, queue) ->
            if (remaining == 0) return@forEach
            val scene = scenes[key] ?: return@forEach
            val batch = mutableListOf<FactoryFloorRepair>()
            while (remaining > 0 && queue.isNotEmpty()) {
                val repair = queue.removeFirst()
                remaining--
                processed++
                val x = scene.placement.originX + repair.point.x
                val y = scene.placement.originY + repair.point.y
                val z = scene.placement.originZ + repair.point.z
                if (!scene.world.isChunkLoaded(x shr 4, z shr 4)) {
                    queue.addFirst(repair)
                    break
                }
                val current = scene.world.getBlockAt(x, y, z).blockData.asString
                when (MineFactoryFloorMigration.decide(repair.recordedActive, current, repair.former, repair.desired)) {
                    MineFactoryFloorMigration.Decision.COMPLETE,
                    MineFactoryFloorMigration.Decision.PRESERVE -> Unit
                    MineFactoryFloorMigration.Decision.PROJECT -> batch += repair
                }
            }
            // One journal rewrite per affected chunk, not one full rewrite per floor block.
            if (batch.isNotEmpty() && !project(scene, batch.associate { it.point to it.desired }))
                batch.asReversed().forEach(queue::addFirst)
            if (queue.isEmpty()) factoryFloorRepairs.remove(key, queue)
        }
        return processed
    }

    private fun scheduleFactoryFloorMigration(
        receipt: MineExpeditionSceneReceipt,
        plan: MineExpeditionPlan,
        scene: MineExpeditionScene,
    ) {
        if (receipt.kind != MineExpeditionKind.DEAD_FACTORY || receipt.placement.geometryVersion < 3
        ) return
        val repairs = ArrayDeque<FactoryFloorRepair>()
        scene.prepared.records.asSequence()
            .filter { it.y - receipt.placement.originY == 4 }
            .mapNotNull { record ->
                val point = ExpeditionPoint(
                    record.x - receipt.placement.originX,
                    record.y - receipt.placement.originY,
                    record.z - receipt.placement.originZ,
                )
                val former = MineFactoryLine.formerFloorMaterial(point) ?: return@mapNotNull null
                val desired = plan.blocks[point] ?: return@mapNotNull null
                if (former == desired || (record.activeData != former && record.activeData != desired)) return@mapNotNull null
                FactoryFloorRepair(point, former, desired, record.activeData)
            }
            .forEach(repairs::addLast)
        if (repairs.isEmpty()) factoryFloorRepairs.remove(receipt.journalSequence)
        else factoryFloorRepairs[receipt.journalSequence] = repairs
    }

    private fun records(
        receipt: MineExpeditionSceneReceipt,
        plan: MineExpeditionPlan,
        snapshot: WorksiteBlockSnapshot,
    ): List<WorksitePreparedSceneRecord> = plan.blocks.entries.map { (local, active) ->
        val x = receipt.placement.originX + local.x
        val y = receipt.placement.originY + local.y
        val z = receipt.placement.originZ + local.z
        val material = requireNotNull(snapshot.type(WorksitePosition(receipt.placement.world, x, y, z))) {
            "Expedition snapshot missed $x,$y,$z"
        }
        require(receipt.placement.geometryVersion < 2 || material.isAir || material in setOf(org.bukkit.Material.STONE, org.bukkit.Material.DEEPSLATE, org.bukkit.Material.TUFF, org.bukkit.Material.ANDESITE, org.bukkit.Material.DIORITE, org.bukkit.Material.GRANITE)) {
            "Nearby expedition cell overlaps existing terrain at $x,$y,$z ($material)"
        }
        WorksitePreparedSceneRecord(
            world = receipt.placement.world,
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
        val world = receiptWorld(receipt) ?: return
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
                if (prepared.hasLoadedSceneRecords(receiptWorld(receipt) ?: return@forEach, receipt.journalOwner, receipt.journalSequence, receipt.sceneId)) {
                    return@forEach
                }
                stock.remove(receipt) {
                    baselineRepairs.remove(key)
                    factoryFloorRepairs.remove(key)
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
                    factoryFloorRepairs.remove(key)
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
        const val FACTORY_STATIC_JOURNAL = 18L
        private const val FACTORY_FLOOR_BATCH = 128
    }
}
