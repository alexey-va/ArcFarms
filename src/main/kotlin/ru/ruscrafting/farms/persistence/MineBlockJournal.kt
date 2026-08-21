package ru.ruscrafting.farms.persistence

import ru.ruscrafting.farms.domain.MineBlockJournalState
import ru.ruscrafting.farms.domain.PendingMineBlock
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class MineBlockJournal(dataRoot: Path) : AutoCloseable {
    private val store = AtomicJsonStore(
        path = dataRoot.resolve("data/mine-blocks.json"),
        type = MineBlockJournalState::class.java,
        emptyValue = ::MineBlockJournalState,
        validate = ::validateState,
    )
    private val lock = Any()
    private val records: MutableMap<String, PendingMineBlock> = store.load().records.toMutableMap()

    fun records(): List<PendingMineBlock> = synchronized(lock) { records.values.toList() }

    fun containsPosition(positionKey: String): Boolean = synchronized(lock) {
        records.values.any { it.positionKey == positionKey }
    }

    fun prepare(record: PendingMineBlock): CompletableFuture<Unit> {
        val snapshot = synchronized(lock) {
            require(record.id !in records) { "Duplicate mine journal id: ${record.id}" }
            require(records.values.none { it.positionKey == record.positionKey }) { "Mine block is already pending: ${record.positionKey}" }
            records[record.id] = record
            MineBlockJournalState(records = records.toMap())
        }
        return store.saveAsync(snapshot).whenComplete { _, failure ->
            if (failure != null) synchronized(lock) { records.remove(record.id) }
        }
    }

    fun remove(recordId: String): CompletableFuture<Unit> {
        val removed = synchronized(lock) { records.remove(recordId) }
            ?: return CompletableFuture.completedFuture(Unit)
        val snapshot = synchronized(lock) { MineBlockJournalState(records = records.toMap()) }
        return store.saveAsync(snapshot).whenComplete { _, failure ->
            if (failure != null) synchronized(lock) { records.putIfAbsent(recordId, removed) }
        }
    }

    override fun close() = store.close()

    private companion object {
        fun validateState(state: MineBlockJournalState) {
            require(state.schemaVersion == 1) { "Unsupported mine journal schema" }
            require(state.records.size <= 100_000) { "Mine journal is unbounded" }
            require(state.records.entries.all { (id, record) -> id == record.id }) { "Mine journal key mismatch" }
            require(state.records.values.map(PendingMineBlock::positionKey).toSet().size == state.records.size) {
                "Mine journal contains duplicate block positions"
            }
        }
    }
}
