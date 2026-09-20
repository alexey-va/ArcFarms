package ru.ruscrafting.farms.persistence

import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlacement
import java.nio.file.Path

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
    val restoring: Boolean = false,
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
    }

    val sceneId: Int get() = objectiveNonce.toInt()

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
        require(nextJournalSequence == 0L || scenes.all { it.journalSequence < nextJournalSequence }) {
            "Expedition journal high-water mark is behind a receipt"
        }
        scenes.forEach(MineExpeditionSceneReceipt::validate)
    }
}

/** Synchronous receipt commits are intentional: the receipt precedes any scene journal mutation. */
class MineExpeditionSceneRepository(dataRoot: Path) : AutoCloseable {
    private val store = AtomicJsonStore(
        path = dataRoot.resolve("data/recovery/mine-expedition-scenes.json"),
        type = MineExpeditionSceneLedger::class.java,
        emptyValue = ::MineExpeditionSceneLedger,
        validate = MineExpeditionSceneLedger::validate,
    )
    private var current = store.load()

    @Synchronized fun records(): List<MineExpeditionSceneReceipt> = current.scenes.toList()

    @Synchronized fun find(zoneId: String, sequence: Long, objectiveNonce: Long): MineExpeditionSceneReceipt? =
        current.scenes.firstOrNull { it.zoneId == zoneId && it.sequence == sequence && it.objectiveNonce == objectiveNonce }

    @Synchronized fun nextJournalSequence(): Long {
        val currentMaximum = current.scenes.maxOfOrNull(MineExpeditionSceneReceipt::journalSequence) ?: 0L
        require(currentMaximum < Long.MAX_VALUE) { "Expedition journal sequence exhausted" }
        val highWater = maxOf(current.nextJournalSequence.coerceAtLeast(1L), currentMaximum + 1L)
        require(highWater < Long.MAX_VALUE) { "Expedition journal sequence exhausted" }
        return highWater
    }

    @Synchronized fun commit(receipt: MineExpeditionSceneReceipt) {
        receipt.validate()
        val existing = current.scenes.firstOrNull {
            it.zoneId == receipt.zoneId && it.sequence == receipt.sequence && it.objectiveNonce == receipt.objectiveNonce
        }
        require(existing == null || existing == receipt) { "Conflicting expedition scene receipt" }
        require(current.scenes.none { it.journalSequence == receipt.journalSequence && it != existing }) {
            "Conflicting expedition journal sequence"
        }
        if (existing != null) return
        require(receipt.journalSequence < Long.MAX_VALUE) { "Expedition journal sequence exhausted" }
        replace(current.scenes + receipt, maxOf(current.nextJournalSequence, receipt.journalSequence + 1L))
    }

    @Synchronized fun markCompleted(zoneId: String, sequence: Long, objectiveNonce: Long, completedAt: Long) {
        require(completedAt >= 0L)
        val receipt = requireNotNull(find(zoneId, sequence, objectiveNonce)) { "Unknown expedition scene receipt" }
        replace(current.scenes.map {
            if (it == receipt) it.copy(completedAt = completedAt, restoring = false) else it
        })
    }

    @Synchronized fun markRestoring(zoneId: String, sequence: Long, objectiveNonce: Long) {
        val receipt = requireNotNull(find(zoneId, sequence, objectiveNonce)) { "Unknown expedition scene receipt" }
        replace(current.scenes.map { if (it == receipt) it.copy(restoring = true) else it })
    }

    @Synchronized fun remove(zoneId: String, sequence: Long, objectiveNonce: Long) {
        require(find(zoneId, sequence, objectiveNonce) != null) { "Unknown expedition scene receipt" }
        replace(current.scenes.filterNot { it.zoneId == zoneId && it.sequence == sequence && it.objectiveNonce == objectiveNonce })
    }

    private fun replace(scenes: List<MineExpeditionSceneReceipt>, nextJournalSequence: Long = current.nextJournalSequence) {
        val next = MineExpeditionSceneLedger(scenes, nextJournalSequence)
        store.saveBlocking(next)
        current = next
    }

    override fun close() = store.close()
}
