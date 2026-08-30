package ru.ruscrafting.farms.persistence

import ru.arc.persistence.CoalescingAsyncWriter
import ru.ruscrafting.farms.domain.LumberBlockJournalState
import ru.ruscrafting.farms.domain.PendingLumberBlock
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

interface LumberRecoveryJournal {
    fun records(): List<PendingLumberBlock>
    fun containsPosition(positionKey: String): Boolean
    fun prepare(record: PendingLumberBlock): CompletableFuture<Unit>
    fun remove(recordId: String): CompletableFuture<Unit>
}

class LumberBlockJournal(dataRoot: Path) : LumberRecoveryJournal, AutoCloseable {
    private val store = AtomicJsonStore(
        path = dataRoot.resolve("data/lumber-blocks.json"),
        type = LumberBlockJournalState::class.java,
        emptyValue = ::LumberBlockJournalState,
        validate = ::validate,
    )
    private val writer = CoalescingAsyncWriter(store::saveAsync)
    private val lock = Any()
    private val records = store.load().records.toMutableMap()

    override fun records(): List<PendingLumberBlock> = synchronized(lock) { records.values.toList() }

    override fun containsPosition(positionKey: String): Boolean = synchronized(lock) {
        records.values.any { it.positionKey == positionKey }
    }

    override fun prepare(record: PendingLumberBlock): CompletableFuture<Unit> {
        val snapshot = synchronized(lock) {
            require(record.id !in records) { "Duplicate lumber journal id: ${record.id}" }
            require(records.values.none { it.positionKey == record.positionKey }) {
                "Lumber block is already pending: ${record.positionKey}"
            }
            records[record.id] = record
            LumberBlockJournalState(records = records.toMap())
        }
        return writer.submit(snapshot).whenComplete { _, failure ->
            if (failure != null) synchronized(lock) { records.remove(record.id) }
        }
    }

    override fun remove(recordId: String): CompletableFuture<Unit> {
        val removed = synchronized(lock) { records.remove(recordId) }
            ?: return CompletableFuture.completedFuture(Unit)
        val snapshot = synchronized(lock) { LumberBlockJournalState(records = records.toMap()) }
        return writer.submit(snapshot).whenComplete { _, failure ->
            if (failure != null) synchronized(lock) {
                if (records.values.none { it.positionKey == removed.positionKey }) records.putIfAbsent(recordId, removed)
            }
        }
    }

    override fun close() {
        writer.closeAsync().get(15, TimeUnit.SECONDS)
        store.close()
    }

    private companion object {
        fun validate(state: LumberBlockJournalState) {
            require(state.records.entries.all { (id, record) -> id == record.id }) { "Lumber journal key mismatch" }
            require(state.records.values.map(PendingLumberBlock::positionKey).distinct().size == state.records.size) {
                "Lumber journal contains duplicate positions"
            }
        }
    }
}
