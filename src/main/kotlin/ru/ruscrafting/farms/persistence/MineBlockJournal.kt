package ru.ruscrafting.farms.persistence

import ru.arc.persistence.CoalescingAsyncWriter
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.PersistentMap
import ru.ruscrafting.farms.domain.MineBlockJournalState
import ru.ruscrafting.farms.domain.PendingMineBlock
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

interface MineRecoveryJournal {
    fun records(): List<PendingMineBlock>
    fun recordsView(): Collection<PendingMineBlock> = records()
    fun recordsForZone(zoneId: String): Collection<PendingMineBlock> = records().filter { it.zoneId == zoneId }
    fun hasNonMiningRecords(zoneId: String): Boolean = recordsForZone(zoneId).any { !it.id.startsWith("mine:") || it.temporaryMaterial != "AIR" }
    fun materialNames(): Set<String> = records().flatMap { listOf(it.originalMaterial, it.temporaryMaterial, it.nextMaterial) }.toSet()
    /** O(1) record lookup for queued recovery work; the default keeps test journals source-compatible. */
    fun record(recordId: String): PendingMineBlock? = records().firstOrNull { it.id == recordId }
    /** O(1) position lookup for forced recovery; the default keeps test journals source-compatible. */
    fun recordAtPosition(positionKey: String): PendingMineBlock? = records().firstOrNull { it.positionKey == positionKey }
    /** O(1) pending count lets controllers detect external journal changes without copying records. */
    fun pendingCount(): Int = records().size
    fun containsPosition(positionKey: String): Boolean
    fun prepare(record: PendingMineBlock): CompletableFuture<Unit>
    fun prepareAll(records: List<PendingMineBlock>): CompletableFuture<Unit> {
        var result = CompletableFuture.completedFuture(Unit)
        records.forEach { record -> result = result.thenCompose { prepare(record) } }
        return result
    }
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
    private var records: PersistentMap<String, PendingMineBlock> = store.load().records.toPersistentMap()
    private val idsByPosition: MutableMap<String, String> = records.values.associateTo(mutableMapOf()) {
        it.positionKey to it.id
    }

    private var byZone = records.values.groupBy(PendingMineBlock::zoneId).mapValues { (_, values) ->
        values.associateBy(PendingMineBlock::id).toPersistentMap()
    }.toPersistentMap()
    private val nonMiningCounts = records.values.filter { !it.id.startsWith("mine:") || it.temporaryMaterial != "AIR" }
        .groupingBy(PendingMineBlock::zoneId).eachCount().toMutableMap()
    private val materialCounts = records.values.flatMap { listOf(it.originalMaterial, it.temporaryMaterial, it.nextMaterial) }
        .groupingBy { it }.eachCount().toMutableMap()

    private fun putRecord(record: PendingMineBlock) {
        records = records.put(record.id, record)
        if (!record.id.startsWith("mine:") || record.temporaryMaterial != "AIR")
            nonMiningCounts[record.zoneId] = (nonMiningCounts[record.zoneId] ?: 0) + 1
        byZone = byZone.put(record.zoneId, byZone[record.zoneId].orEmpty().toPersistentMap().put(record.id, record))
        listOf(record.originalMaterial, record.temporaryMaterial, record.nextMaterial).forEach { material ->
            materialCounts[material] = (materialCounts[material] ?: 0) + 1
        }
    }

    private fun removeRecord(record: PendingMineBlock) {
        records = records.remove(record.id)
        if (!record.id.startsWith("mine:") || record.temporaryMaterial != "AIR") {
            val count = (nonMiningCounts[record.zoneId] ?: 1) - 1
            if (count == 0) nonMiningCounts.remove(record.zoneId) else nonMiningCounts[record.zoneId] = count
        }
        byZone[record.zoneId]?.remove(record.id)?.let { changed ->
            byZone = if (changed.isEmpty()) byZone.remove(record.zoneId) else byZone.put(record.zoneId, changed)
        }
        listOf(record.originalMaterial, record.temporaryMaterial, record.nextMaterial).forEach { material ->
            val count = (materialCounts[material] ?: 1) - 1
            if (count == 0) materialCounts.remove(material) else materialCounts[material] = count
        }
    }

    override fun recordsView(): Collection<PendingMineBlock> = synchronized(lock) { records.values }
    override fun recordsForZone(zoneId: String): Collection<PendingMineBlock> = synchronized(lock) { byZone[zoneId]?.values ?: emptyList() }
    override fun hasNonMiningRecords(zoneId: String): Boolean = synchronized(lock) { (nonMiningCounts[zoneId] ?: 0) != 0 }
    override fun materialNames(): Set<String> = synchronized(lock) { materialCounts.keys.toSet() }

    override fun records(): List<PendingMineBlock> = synchronized(lock) { records.values.toList() }

    fun snapshotRecords(): Map<String, PendingMineBlock> = synchronized(lock) { records }

    override fun record(recordId: String): PendingMineBlock? = synchronized(lock) { records[recordId] }

    override fun recordAtPosition(positionKey: String): PendingMineBlock? = synchronized(lock) {
        idsByPosition[positionKey]?.let(records::get)
    }

    override fun pendingCount(): Int = synchronized(lock) { records.size }

    fun pendingRecordCount(): Int = pendingCount()

    override fun containsPosition(positionKey: String): Boolean = synchronized(lock) {
        idsByPosition.containsKey(positionKey)
    }

    override fun prepare(record: PendingMineBlock): CompletableFuture<Unit> {
        return prepareAll(listOf(record))
    }

    override fun prepareAll(records: List<PendingMineBlock>): CompletableFuture<Unit> {
        if (records.isEmpty()) return CompletableFuture.completedFuture(Unit)
        require(records.map(PendingMineBlock::id).distinct().size == records.size) { "Duplicate mine journal ids in batch" }
        require(records.map(PendingMineBlock::positionKey).distinct().size == records.size) {
            "Duplicate mine journal positions in batch"
        }
        val snapshot = synchronized(lock) {
            records.forEach { record ->
                require(record.id !in this.records) { "Duplicate mine journal id: ${record.id}" }
                require(record.positionKey !in idsByPosition) { "Mine block is already pending: ${record.positionKey}" }
            }
            records.forEach { record ->
                putRecord(record)
                idsByPosition[record.positionKey] = record.id
            }
            MineBlockJournalState(records = this.records)
        }
        return writer.submit(snapshot).whenComplete { _, failure ->
            if (failure != null) synchronized(lock) {
                records.forEach { record ->
                    if (this.records[record.id] == record) {
                        removeRecord(record)
                        if (idsByPosition[record.positionKey] == record.id) idsByPosition.remove(record.positionKey)
                    }
                }
            }
        }
    }

    override fun remove(recordId: String): CompletableFuture<Unit> {
        val removed = synchronized(lock) {
            records[recordId]?.also { removedRecord ->
                removeRecord(removedRecord)
                if (idsByPosition[removedRecord.positionKey] == recordId) idsByPosition.remove(removedRecord.positionKey)
            }
        }
            ?: return CompletableFuture.completedFuture(Unit)
        val snapshot = synchronized(lock) { MineBlockJournalState(records = records) }
        return writer.submit(snapshot).whenComplete { _, failure ->
            if (failure != null) synchronized(lock) {
                if (recordId !in records && removed.positionKey !in idsByPosition) {
                    putRecord(removed)
                    idsByPosition[removed.positionKey] = recordId
                }
            }
        }
    }

    fun closeAsync(): CompletableFuture<Unit> = writer.closeAsync().thenCompose { store.closeAsync() }

    override fun close() { closeAsync() }

    private companion object {
        fun validateState(state: MineBlockJournalState) {
            require(state.schemaVersion == MineBlockJournalState.SCHEMA_VERSION) { "Unsupported mine journal schema" }
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
