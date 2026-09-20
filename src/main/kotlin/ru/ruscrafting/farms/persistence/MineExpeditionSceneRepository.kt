package ru.ruscrafting.farms.persistence

import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlacement
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** Small durable receipt kept separately from the active MineShiftState. */
data class MineExpeditionSceneReceipt(
    val zoneId: String,
    val sequence: Long,
    val objectiveNonce: Long,
    /** Monotonic identity used by the shared block journal; mine sequence is retained separately. */
    val journalSequence: Long,
    val kind: MineExpeditionKind,
    val placement: MineExpeditionPlacement,
    val surfaceWorld: String,
    val surfaceX: Double,
    val surfaceY: Double,
    val surfaceZ: Double,
    val surfaceYaw: Float = 0f,
    val surfacePitch: Float = 0f,
    val completedAt: Long = 0L,
    val siteBuilt: Boolean = false,
    val restoring: Boolean = false,
    val reserved: Boolean = false,
    /** Journal identity remains stable when a ready reserve is bound to an incident. */
    val journalZoneId: String? = null,
    val journalSceneId: Int? = null,
) {
    init { validate() }

    fun validate() {
        require(zoneId.matches(ZONE_ID)) { "Invalid expedition receipt zone" }
        require(sequence >= 0L) { "Invalid expedition receipt sequence" }
        require(objectiveNonce in 1..Int.MAX_VALUE.toLong()) { "Invalid expedition receipt nonce" }
        require(journalSequence in 1..Long.MAX_VALUE) { "Invalid expedition journal sequence" }
        placement.validate()
        require(surfaceWorld.matches(WORLD_ID)) { "Invalid expedition receipt surface world" }
        require(listOf(surfaceX, surfaceY, surfaceZ).all(Double::isFinite)) { "Invalid expedition receipt surface" }
        require(surfaceX in -30_000_000.0..30_000_000.0 && surfaceZ in -30_000_000.0..30_000_000.0) {
            "Expedition receipt surface is outside world limits"
        }
        require(surfaceY in -2_048.0..2_048.0) { "Expedition receipt surface height is invalid" }
        require(surfaceYaw.isFinite() && surfacePitch.isFinite()) { "Expedition receipt rotation is invalid" }
        require(completedAt >= 0L) { "Invalid expedition completion timestamp" }
        require(journalZoneId == null || journalZoneId.matches(ZONE_ID)) { "Invalid expedition journal owner" }
        require(journalSceneId == null || journalSceneId > 0) { "Invalid expedition journal scene" }
        require(!reserved || completedAt == 0L) { "A reserve cannot be completed" }
    }

    val sceneId: Int get() = journalSceneId ?: objectiveNonce.toInt()
    val journalOwner: String get() = journalZoneId ?: zoneId

    companion object {
        private val ZONE_ID = Regex("[a-z0-9_-]{1,48}")
        private val WORLD_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}

data class MineExpeditionSceneLedger(
    val scenes: List<MineExpeditionSceneReceipt> = emptyList(),
    /** High-water mark; journal identities are never reused after receipt retirement. */
    val nextJournalSequence: Long = 1L,
) {
    init { validate() }

    fun validate() {
        require(scenes.size <= 1_024) { "Too many expedition scene receipts" }
        require(nextJournalSequence >= 0L) { "Invalid expedition journal high-water mark" }
        require(scenes.map { Triple(it.zoneId, it.sequence, it.objectiveNonce) }.toSet().size == scenes.size) {
            "Duplicate expedition scene receipt"
        }
        require(scenes.map { it.journalSequence }.toSet().size == scenes.size) { "Duplicate expedition journal identity" }
        require(nextJournalSequence == 0L || scenes.all { it.journalSequence < nextJournalSequence }) {
            "Expedition journal high-water mark is behind a receipt"
        }
        scenes.forEach(MineExpeditionSceneReceipt::validate)
    }
}

internal interface MineExpeditionLedgerStorage {
    fun load(): CompletableFuture<MineExpeditionSceneLedger>
    fun save(ledger: MineExpeditionSceneLedger): CompletableFuture<Unit>
    fun shutdown()
}

private class JsonMineExpeditionLedgerStorage(dataRoot: Path) : MineExpeditionLedgerStorage {
    private val store = AtomicJsonStore(
        path = dataRoot.resolve("data/recovery/mine-expedition-scenes.json"),
        type = MineExpeditionSceneLedger::class.java,
        emptyValue = ::MineExpeditionSceneLedger,
        validate = MineExpeditionSceneLedger::validate,
    )
    override fun load() = store.loadAsync()
    override fun save(ledger: MineExpeditionSceneLedger) = store.saveAsync(ledger)
    override fun shutdown() = store.shutdown()
}

/** Serial durable commits. Callers continue world work only after the returned future succeeds. */
class MineExpeditionSceneRepository internal constructor(private val store: MineExpeditionLedgerStorage) : AutoCloseable {
    constructor(dataRoot: Path) : this(JsonMineExpeditionLedgerStorage(dataRoot))

    @Volatile private var current = MineExpeditionSceneLedger()
    private var reservedSequence = 1L
    private var closed = false
    val ready: CompletableFuture<Unit> = store.load().thenApply { loaded -> loaded.validate(); current = loaded; Unit }
    private var tail = ready

    @Synchronized fun records(): List<MineExpeditionSceneReceipt> = current.scenes.toList()

    fun findJournal(id: Long): MineExpeditionSceneReceipt? = current.scenes.firstOrNull { it.journalSequence == id }

    @Synchronized fun find(zoneId: String, sequence: Long, objectiveNonce: Long): MineExpeditionSceneReceipt? =
        current.scenes.firstOrNull { it.zoneId == zoneId && it.sequence == sequence && it.objectiveNonce == objectiveNonce }

    @Synchronized fun nextJournalSequence(): Long {
        val currentMaximum = current.scenes.maxOfOrNull(MineExpeditionSceneReceipt::journalSequence) ?: 0L
        require(currentMaximum < Long.MAX_VALUE) { "Expedition journal sequence exhausted" }
        val highWater = maxOf(reservedSequence, current.nextJournalSequence.coerceAtLeast(1L), currentMaximum + 1L)
        require(highWater < Long.MAX_VALUE) { "Expedition journal sequence exhausted" }
        return highWater
    }

    @Synchronized fun allocateJournalSequence(): Long = nextJournalSequence().also { reservedSequence = it + 1L }

    fun commit(receipt: MineExpeditionSceneReceipt): CompletableFuture<Unit> = update { current ->
        receipt.validate()
        val existing = current.scenes.firstOrNull {
            it.zoneId == receipt.zoneId && it.sequence == receipt.sequence && it.objectiveNonce == receipt.objectiveNonce
        }
        require(existing == null || existing == receipt) { "Conflicting expedition scene receipt" }
        require(current.scenes.none { it.journalSequence == receipt.journalSequence && it != existing }) {
            "Conflicting expedition journal sequence"
        }
        if (existing != null) return@update current
        require(receipt.journalSequence < Long.MAX_VALUE) { "Expedition journal sequence exhausted" }
        MineExpeditionSceneLedger(current.scenes + receipt, maxOf(current.nextJournalSequence, receipt.journalSequence + 1L))
    }

    fun markCompleted(zoneId: String, sequence: Long, objectiveNonce: Long, completedAt: Long): CompletableFuture<Unit> = update { current ->
        require(completedAt >= 0L)
        val receipt = requireNotNull(find(zoneId, sequence, objectiveNonce)) { "Unknown expedition scene receipt" }
        if (receipt.restoring) return@update current
        current.copy(scenes = current.scenes.map {
            if (it == receipt) it.copy(completedAt = completedAt) else it
        })
    }

    fun markRestoring(zoneId: String, sequence: Long, objectiveNonce: Long): CompletableFuture<Unit> = update { current ->
        val receipt = requireNotNull(find(zoneId, sequence, objectiveNonce)) { "Unknown expedition scene receipt" }
        current.copy(scenes = current.scenes.map { if (it == receipt) it.copy(restoring = true) else it })
    }

    fun remove(zoneId: String, sequence: Long, objectiveNonce: Long): CompletableFuture<Unit> = update { current ->
        current.copy(scenes = current.scenes.filterNot { it.zoneId == zoneId && it.sequence == sequence && it.objectiveNonce == objectiveNonce })
    }

    fun release(receipt: MineExpeditionSceneReceipt, reserve: MineExpeditionSceneReceipt): CompletableFuture<Unit> = update { current ->
        val latest = requireNotNull(current.scenes.firstOrNull { it.journalSequence == receipt.journalSequence })
        require(!latest.restoring && latest.zoneId == receipt.zoneId && latest.objectiveNonce == receipt.objectiveNonce && reserve.reserved)
        require(receipt.journalSequence == reserve.journalSequence && receipt.placement == reserve.placement)
        current.copy(scenes = current.scenes.map { if (it == latest) reserve.copy(siteBuilt = latest.siteBuilt) else it })
    }

    fun markBuilt(id: Long): CompletableFuture<Unit> = update { current ->
        current.copy(scenes = current.scenes.map { if (it.journalSequence == id) it.copy(siteBuilt = true) else it })
    }

    fun claim(reserve: MineExpeditionSceneReceipt, claimed: MineExpeditionSceneReceipt): CompletableFuture<Unit> = update { current ->
        require(reserve in current.scenes && reserve.reserved && !reserve.restoring) { "Expedition reserve is unavailable" }
        require(!claimed.reserved && claimed.placement == reserve.placement && claimed.kind == reserve.kind &&
            claimed.journalSequence == reserve.journalSequence && claimed.journalOwner == reserve.journalOwner &&
            claimed.sceneId == reserve.sceneId) { "Claim changed prepared scene identity" }
        current.copy(scenes = current.scenes.map { if (it == reserve) claimed else it })
    }

    @Synchronized private fun update(change: (MineExpeditionSceneLedger) -> MineExpeditionSceneLedger): CompletableFuture<Unit> {
        if (closed) return CompletableFuture.failedFuture(IllegalStateException("Expedition scene repository is closed"))
        val next = tail.handle { _, _ -> Unit }.thenCompose {
            ready.thenCompose {
                val replacement = change(current)
                replacement.validate()
                if (replacement == current) CompletableFuture.completedFuture(Unit)
                else store.save(replacement).thenApply { current = replacement; Unit }
            }
        }
        tail = next
        return next
    }

    /** Only the validated plugin shutdown boundary may wait for the durable writer. */
    fun flushAndClose() {
        val pending = synchronized(this) { closed = true; tail }
        try { pending.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        finally { store.shutdown() }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        tail.whenComplete { _, _ -> store.shutdown() }
    }
}
