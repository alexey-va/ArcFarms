package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Location
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionEngine
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind
import ru.ruscrafting.farms.domain.mine.expedition.*
import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.persistence.MineExpeditionSceneReceipt
import ru.ruscrafting.farms.persistence.MineExpeditionSceneRepository
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

internal interface MineExpeditionSceneLoader {
    fun prepare(receipt: MineExpeditionSceneReceipt)
    fun prepared(id: Long): MineExpeditionScene?
    fun restore(receipt: MineExpeditionSceneReceipt)
    fun failure(id: Long): String?
}

internal data class MineExpeditionStockStatus(val kind: MineExpeditionKind, val ready: Int, val building: Int, val failed: Int, val retiring: Int)

/** Durable reserve allocation and claiming; the world owner performs only journalled scene work. */
internal class MineExpeditionStock(
    private val receipts: MineExpeditionSceneRepository,
    private val tasks: WorksiteTaskPort,
    private val state: WorksiteStatePort,
    private val loader: MineExpeditionSceneLoader,
) {
    private val experimentPresets = MineFactoryExperimentPresets()
    private val writes = hashSetOf<String>()
    private val creating = linkedMapOf<MineExpeditionKind, MineExpeditionSceneReceipt>()
    private val claimed = hashSetOf<Long>()
    private val retiring = hashSetOf<Long>()
    private val failures = hashMapOf<String, String>()
    var site: MineExpeditionSite? = null
    private fun current(receipt: MineExpeditionSceneReceipt) = receipt.placement.geometryVersion == ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlacement.CURRENT_GEOMETRY_VERSION && receipt.placement.world == site?.world
    private var active = false
    private var nextRefill = 0L
    private val retryAfter = hashMapOf<String, Long>()

    fun activate() { active = true; nextRefill = 0L }
    fun deactivate() { active = false; experimentPresets.clear(); writes.clear(); creating.clear(); claimed.clear(); retiring.clear(); failures.clear(); retryAfter.clear() }

    fun maintain(now: Long) {
        if (!active || now < nextRefill) return
        val site = site ?: return
        nextRefill = now + 1_000L
        receipts.records().filter { !it.restoring && !current(it) }.forEach(::retire)
        retiring.toList().forEach { id -> receipts.findJournal(id)?.let(::retire) }
        MineExpeditionKind.entries.forEach { kind ->
            val reserves = receipts.records().filter { it.kind == kind && current(it) && it.reserved && !it.restoring && it.journalSequence !in claimed }
            reserves.forEach(loader::prepare)
            if (receipts.records().any { it.kind == kind && current(it) && !it.restoring } || kind in creating || now < (retryAfter["create:$kind"] ?: 0L)) return@forEach
            val id = receipts.allocateJournalSequence()
            require(id <= Int.MAX_VALUE) { "Expedition scene identifier exhausted" }
            // The shared scene owner fences restoration by zone, so each allocation needs its own owner.
            val owner = "reserve_${kind.name.lowercase()}_$id"
            val placement = MineExpeditionAllocation.allocate(kind, id, receipts.records() + creating.values, site)
            val receipt = MineExpeditionSceneReceipt(owner, 0L, id, id, kind, placement,
                site.world, site.surfaceX, site.surfaceY, site.surfaceZ, reserved = true,
                journalZoneId = owner, journalSceneId = id.toInt())
            creating[kind] = receipt
            write("create:$kind", { receipts.commit(receipt) }, { creating.remove(kind) }) {
                creating.remove(kind)
                loader.prepare(receipt)
            }
        }
    }

    fun available(type: MineIncidentType): Boolean = MineExpeditionEngine.kind(type)?.let(::available) != null
    private fun available(kind: MineExpeditionKind): MineExpeditionSceneReceipt? = receipts.records().firstOrNull {
        it.kind == kind && current(it) && it.reserved && !it.restoring && it.journalSequence !in claimed && it.journalSequence !in retiring && loader.prepared(it.journalSequence)?.ready == true
    }

    fun ensure(runtime: MineRuntime, surface: Location): MineExpeditionScene? {
        if (!active) return null
        val incident = runtime.state.incident ?: return null
        val kind = MineExpeditionEngine.kind(incident.type) ?: return null
        val key = key(runtime)
        receipts.find(runtime.settings.id, runtime.state.sequence, incident.objectiveNonce)?.let { receipt ->
            if (receipt.restoring || !current(receipt)) return null
            loader.prepare(receipt)
            return loader.prepared(receipt.journalSequence)?.takeIf { it.ready }?.also {
                bind(it, receipt, surface)
                if (incident.expedition == null || incident.expedition.placement != receipt.placement) {
                    runtime.state = runtime.state.copy(incident = incident.copy(expedition = initial(receipt, incident.type)))
                    state.persistAsync()
                }
            }
        }
        if ("claim:$key" in writes || key in failures) return null
        val reserve = available(kind)?.takeIf { it.placement.world == surface.world.name } ?: return null
        val receipt = reserve.copy(zoneId = runtime.settings.id, sequence = runtime.state.sequence,
            objectiveNonce = incident.objectiveNonce, surfaceWorld = surface.world.name,
            surfaceX = surface.x, surfaceY = surface.y, surfaceZ = surface.z, surfaceYaw = surface.yaw, surfacePitch = surface.pitch,
            reserved = false)
        claimed += reserve.journalSequence
        write("claim:$key", { receipts.claim(reserve, receipt) }, { failure ->
            claimed.remove(reserve.journalSequence); failures[key] = failure.message ?: failure.javaClass.simpleName
        }) {
            claimed.remove(reserve.journalSequence)
            if (key(runtime) == key && runtime.state.incident?.type == incident.type) {
                loader.prepared(receipt.journalSequence)?.let { bind(it, receipt, surface) }
                runtime.state = runtime.state.copy(incident = runtime.state.incident!!.copy(
                    expedition = initial(receipt, incident.type)))
                state.persistAsync()
            } else loader.prepared(receipt.journalSequence)?.let {
                bind(it, receipt, surface)
                release(it)
            }
            nextRefill = 0L
        }
        return null
    }

    fun configureFactoryExperiments(zoneId: String, preset: String): Boolean =
        experimentPresets.configure(zoneId, preset)

    private fun initial(receipt: MineExpeditionSceneReceipt, type: MineIncidentType) =
        MineExpeditionEngine.initial(type, receipt.placement, Math.floorMod(receipt.objectiveNonce, 3L).toInt(),
            if (type == MineIncidentType.DEAD_FACTORY && receipt.placement.geometryVersion >= 3)
                experimentPresets.consume(receipt.zoneId, WorksiteDeterministicSeed.derive(
                    receipt.objectiveNonce, receipt.sequence)) else null)

    fun failure(runtime: MineRuntime): String? = failures[key(runtime)] ?: runtime.state.incident?.let {
        receipts.find(runtime.settings.id, runtime.state.sequence, it.objectiveNonce)?.let { receipt -> loader.failure(receipt.journalSequence) }
    }

    fun markBuilt(scene: MineExpeditionScene) {
        if (receipts.findJournal(scene.journalSequence)?.siteBuilt == true) return
        write("built:${scene.journalSequence}", { receipts.markBuilt(scene.journalSequence) }) {}
    }

    fun complete(scene: MineExpeditionScene, now: Long) {
        if (scene.reserved || scene.completedAt != 0L) return
        write("complete:${scene.journalSequence}", { receipts.markCompleted(scene.zoneId, scene.sequence, scene.objectiveNonce, now) }) {
            scene.completedAt = now
        }
    }

    /** Releases event ownership without restoring or regenerating the operator-editable site. */
    fun release(scene: MineExpeditionScene) {
        val receipt = receipts.findJournal(scene.journalSequence) ?: return
        if (receipt.reserved || receipt.restoring) return
        val reserve = receipt.copy(zoneId = receipt.journalOwner, sequence = 0,
            objectiveNonce = receipt.journalSequence, completedAt = 0, reserved = true)
        write("release:${scene.journalSequence}", { receipts.release(receipt, reserve) }) {
            bind(scene, reserve, scene.surface)
            nextRefill = 0
        }
    }

    fun retire(receipt: MineExpeditionSceneReceipt) {
        if (receipt.restoring) { retiring.remove(receipt.journalSequence); return }
        retiring += receipt.journalSequence
        write("retire:${receipt.journalSequence}", { receipts.markRestoring(receipt.zoneId, receipt.sequence, receipt.objectiveNonce) }) {
            retiring.remove(receipt.journalSequence)
            loader.prepared(receipt.journalSequence)?.invalidateReady()
            loader.restore(receipt.copy(restoring = true))
            nextRefill = 0L
        }
    }

    fun remove(receipt: MineExpeditionSceneReceipt, complete: () -> Unit) {
        write("remove:${receipt.journalSequence}", { receipts.remove(receipt.zoneId, receipt.sequence, receipt.objectiveNonce) }, success = complete)
    }

    fun rebuild(kind: MineExpeditionKind?): Int {
        val targets = receipts.records().filter { it.reserved && !it.restoring && it.journalSequence !in claimed && it.journalSequence !in retiring && (kind == null || it.kind == kind) }
        targets.forEach(::retire)
        nextRefill = 0L
        return targets.size
    }

    fun status(): List<MineExpeditionStockStatus> = MineExpeditionKind.entries.map { kind ->
        val records = receipts.records().filter { it.kind == kind && it.reserved }
        val ready = records.count { !it.restoring && it.journalSequence !in claimed && it.journalSequence !in retiring && loader.prepared(it.journalSequence)?.ready == true }
        val failed = records.count { !it.restoring && it.journalSequence !in retiring && loader.failure(it.journalSequence) != null }
        val retiring = records.count { it.restoring || it.journalSequence in retiring }
        MineExpeditionStockStatus(kind, ready, records.size - ready - failed - retiring + if (kind in creating) 1 else 0, failed, retiring)
    }

    private fun write(id: String, operation: () -> CompletableFuture<Unit>, failed: (Throwable) -> Unit = {}, success: () -> Unit) {
        if (System.currentTimeMillis() < (retryAfter[id] ?: 0L) || !writes.add(id)) return
        val token = tasks.lifecycleToken()
        val result = runCatching(operation).getOrElse { CompletableFuture.failedFuture(it) }
        result.whenComplete { _, failure ->
            tasks.runSync(token) {
                writes.remove(id)
                if (failure == null) success() else {
                    retryAfter[id] = System.currentTimeMillis() + 30_000L
                    failed(failure)
                    state.log(Level.WARNING, "Mine expedition storage failed operation=$id", failure)
                }
            }
        }
    }

    private fun bind(scene: MineExpeditionScene, receipt: MineExpeditionSceneReceipt, surface: Location) {
        scene.zoneId = receipt.zoneId; scene.sequence = receipt.sequence; scene.objectiveNonce = receipt.objectiveNonce
        scene.surface = surface.clone(); scene.reserved = receipt.reserved; scene.completedAt = receipt.completedAt
    }

    private fun key(runtime: MineRuntime) = "${runtime.settings.id}:${runtime.state.sequence}:${runtime.state.incident?.objectiveNonce}"
}
