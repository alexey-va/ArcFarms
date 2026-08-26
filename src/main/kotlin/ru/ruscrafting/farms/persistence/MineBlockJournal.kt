package ru.ruscrafting.farms.persistence

import ru.arc.persistence.CoalescingAsyncWriter
import ru.ruscrafting.farms.domain.MineBlockJournalState
import ru.ruscrafting.farms.domain.PendingMineBlock
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

interface MineRecoveryJournal {
    fun records(): List<PendingMineBlock>
    fun containsPosition(positionKey: String): Boolean
    fun prepare(record: PendingMineBlock): CompletableFuture<Unit>
    fun remove(recordId: String): CompletableFuture<Unit>
}

class MineBlockJournal(dataRoot: Path) : MineRecoveryJournal, AutoCloseable {
    private val store = AtomicJsonStore(
        path = dataRoot.resolve("data/mine-blocks.json"),
        type = MineBlockJournalState::class.java,
        emptyValue = ::MineBlockJournalState,
        validate = ::validateState,
    )
    private val writer = CoalescingAsyncWriter(store::saveAsync)
    private val lock = Any()
    private val records: MutableMap<String, PendingMineBlock> = store.load().records.toMutableMap()

    override fun records(): List<PendingMineBlock> = synchronized(lock) { records.values.toList() }

    override fun containsPosition(positionKey: String): Boolean = synchronized(lock) {
        records.values.any { it.positionKey == positionKey }
    }

    override fun prepare(record: PendingMineBlock): CompletableFuture<Unit> {
        val snapshot = synchronized(lock) {
            require(record.id !in records) { "Duplicate mine journal id: ${record.id}" }
            require(records.values.none { it.positionKey == record.positionKey }) { "Mine block is already pending: ${record.positionKey}" }
            records[record.id] = record
            MineBlockJournalState(records = records.toMap())
        }
        return writer.submit(snapshot).whenComplete { _, failure ->
            if (failure != null) synchronized(lock) { records.remove(record.id) }
        }
    }

    override fun remove(recordId: String): CompletableFuture<Unit> {
        val removed = synchronized(lock) { records.remove(recordId) }
            ?: return CompletableFuture.completedFuture(Unit)
        val snapshot = synchronized(lock) { MineBlockJournalState(records = records.toMap()) }
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
        fun validateState(state: MineBlockJournalState) {
            require(state.schemaVersion == 1) { "Unsupported mine journal schema" }
            require(state.records.size <= 100_000) { "Mine journal is unbounded" }
            require(state.records.entries.all { (id, record) -> id == record.id }) { "Mine journal key mismatch" }
            require(state.records.values.map(PendingMineBlock::positionKey).toSet().size == state.records.size) {
                "Mine journal contains duplicate block positions"
            }
            state.records.values.forEach { record ->
                require(record.id.matches(Regex("[a-zA-Z0-9._:-]{1,160}"))) { "Invalid mine journal id" }
                require(record.zoneId.matches(Regex("[a-z0-9_-]{1,48}"))) { "Invalid mine journal zone id" }
                require(record.world.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid mine journal world" }
                require(record.x in -30_000_000..30_000_000 && record.z in -30_000_000..30_000_000) {
                    "Mine journal position is outside the world border"
                }
                require(record.y in -4_096..4_096) { "Mine journal height is invalid" }
                require(
                    listOf(record.originalMaterial, record.temporaryMaterial, record.nextMaterial)
                        .all { it.matches(Regex("[A-Z0-9_]{2,64}")) },
                ) { "Mine journal material is invalid" }
                require(record.restoreAt > 0) { "Mine journal restore time is invalid" }
            }
        }
    }
}
