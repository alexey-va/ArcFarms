package ru.ruscrafting.farms.persistence

import ru.ruscrafting.farms.domain.FixedFarmCropJournalState
import ru.ruscrafting.farms.domain.PendingFixedFarmCrop
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

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
    private val records = store.load().records.toMutableMap()

    fun records(): List<PendingFixedFarmCrop> = synchronized(lock) { records.values.toList() }

    fun record(positionKey: String): PendingFixedFarmCrop? = synchronized(lock) { records[positionKey] }

    fun contains(positionKey: String): Boolean = synchronized(lock) { positionKey in records }

    fun prepare(record: PendingFixedFarmCrop): CompletableFuture<Unit> {
        val snapshot = synchronized(lock) {
            require(record.positionKey !in records) { "Fixed crop is already pending: ${record.positionKey}" }
            records[record.positionKey] = record
            FixedFarmCropJournalState(records = records.toMap())
        }
        return writer.submit(snapshot).whenComplete { _, failure ->
            if (failure != null) synchronized(lock) { records.remove(record.positionKey) }
        }
    }

    fun remove(positionKey: String): CompletableFuture<Unit> {
        val removed = synchronized(lock) { records.remove(positionKey) }
            ?: return CompletableFuture.completedFuture(Unit)
        val snapshot = synchronized(lock) { FixedFarmCropJournalState(records = records.toMap()) }
        return writer.submit(snapshot).whenComplete { _, failure ->
            if (failure != null) synchronized(lock) { records.putIfAbsent(positionKey, removed) }
        }
    }

    override fun close() {
        writer.close().get(15, TimeUnit.SECONDS)
        store.close()
    }

    private companion object {
        fun validateState(state: FixedFarmCropJournalState) {
            require(state.schemaVersion == 1) { "Unsupported fixed crop journal schema" }
            require(state.records.size <= 100_000) { "Fixed crop journal is unbounded" }
            require(state.records.entries.all { (key, record) -> key == record.positionKey }) {
                "Fixed crop journal key mismatch"
            }
        }
    }
}
