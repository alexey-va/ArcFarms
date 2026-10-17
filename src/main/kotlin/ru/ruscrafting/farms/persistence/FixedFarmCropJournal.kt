package ru.ruscrafting.farms.persistence

import ru.arc.persistence.CoalescingAsyncWriter
import kotlinx.collections.immutable.toPersistentMap
import ru.ruscrafting.farms.domain.FixedFarmCropJournalState
import ru.ruscrafting.farms.domain.PendingFixedFarmCrop
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** Atomic crash-recovery journal for fruit blocks that have been accepted for harvest. */
class FixedFarmCropJournal(dataRoot: Path) : AutoCloseable {
    private val store = AtomicJsonStore(
        path = dataRoot.resolve("data/fixed-farm-crops.json"),
        type = FixedFarmCropJournalState::class.java,
        emptyValue = ::FixedFarmCropJournalState,
        validate = ::validateState,
    )
    private val writer = CoalescingAsyncWriter(store::saveAsync)
    private val lock = Any()
    private var records = store.load().records.toPersistentMap()
    private var chunks = records.values.groupBy(::chunkKey).mapValues { (_, values) ->
        values.associateBy(PendingFixedFarmCrop::positionKey).toPersistentMap()
    }.toPersistentMap()

    private fun chunkKey(record: PendingFixedFarmCrop): String = "${record.world}:${record.x shr 4}:${record.z shr 4}"

    private fun put(record: PendingFixedFarmCrop) {
        records = records.put(record.positionKey, record)
        val key = chunkKey(record)
        chunks = chunks.put(key, chunks[key].orEmpty().toPersistentMap().put(record.positionKey, record))
    }

    private fun removeIndex(record: PendingFixedFarmCrop) {
        records = records.remove(record.positionKey)
        val key = chunkKey(record)
        chunks[key]?.let { previous ->
            val changed = previous.remove(record.positionKey)
            chunks = if (changed.isEmpty()) chunks.remove(key) else chunks.put(key, changed)
        }
    }

    fun recordsInChunk(world: String, x: Int, z: Int): Collection<PendingFixedFarmCrop> = synchronized(lock) {
        chunks["$world:$x:$z"]?.values ?: emptyList()
    }

    fun records(): List<PendingFixedFarmCrop> = synchronized(lock) { records.values.toList() }

    fun snapshotRecords(): Map<String, PendingFixedFarmCrop> = synchronized(lock) { records }

    fun pendingRecordCount(): Int = synchronized(lock) { records.size }

    fun record(positionKey: String): PendingFixedFarmCrop? = synchronized(lock) { records[positionKey] }

    fun contains(positionKey: String): Boolean = synchronized(lock) { positionKey in records }

    fun prepare(record: PendingFixedFarmCrop): CompletableFuture<Unit> {
        val snapshot = synchronized(lock) {
            require(record.positionKey !in records) { "Fixed crop is already pending: ${record.positionKey}" }
            put(record)
            FixedFarmCropJournalState(records = records)
        }
        return writer.submit(snapshot).whenComplete { _, failure ->
            if (failure != null) synchronized(lock) {
                if (records[record.positionKey] == record) removeIndex(record)
            }
        }
    }

    fun remove(positionKey: String): CompletableFuture<Unit> {
        val removed = synchronized(lock) { records[positionKey]?.also(::removeIndex) }
            ?: return CompletableFuture.completedFuture(Unit)
        val snapshot = synchronized(lock) { FixedFarmCropJournalState(records = records) }
        return writer.submit(snapshot).whenComplete { _, failure ->
            if (failure != null) synchronized(lock) { if (positionKey !in records) put(removed) }
        }
    }

    fun closeAsync(): CompletableFuture<Unit> = writer.closeAsync().thenCompose { store.closeAsync() }

    override fun close() { closeAsync() }

    private companion object {
        fun validateState(state: FixedFarmCropJournalState) {
            require(state.schemaVersion == FixedFarmCropJournalState.SCHEMA_VERSION) {
                "Unsupported fixed crop journal schema"
            }
            require(state.records.size <= 100_000) { "Fixed crop journal is unbounded" }
            require(state.records.entries.all { (key, record) -> key == record.positionKey }) {
                "Fixed crop journal key mismatch"
            }
        }
    }
}
