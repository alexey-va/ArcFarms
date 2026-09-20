package ru.ruscrafting.farms.paper.worksite.scene

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorksiteSceneCodec

private class FailingProjectionCodec(
    private val delegate: WorksitePreparedSceneCodec,
) : WorksitePreparedSceneCodec {
    var failingChunkX: Int? = null

    override fun decode(
        raw: ByteArray,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        minHeight: Int,
        maxHeight: Int,
    ): List<WorksitePreparedSceneRecord> = delegate.decode(raw, world, chunkX, chunkZ, minHeight, maxHeight)

    override fun encode(
        records: List<WorksitePreparedSceneRecord>,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        minHeight: Int,
        maxHeight: Int,
    ): ByteArray {
        check(chunkX != failingChunkX) { "injected projection journal write failure" }
        return delegate.encode(records, world, chunkX, chunkZ, minHeight, maxHeight)
    }

    override fun foreignJournalOverlaps(
        chunk: Chunk,
        positions: Collection<Triple<Int, Int, Int>>,
    ): Boolean = delegate.foreignJournalOverlaps(chunk, positions)
}

class WorksitePreparedSceneProjectionMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var plugin: Plugin
    lateinit var leases: MutableSet<String>

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("projection_world")
        world.getChunkAt(0, 0).load()
        world.getChunkAt(1, 0).load()
        plugin = paper.createSimplePlugin("PreparedSceneProjectionTest")
        leases = linkedSetOf()
    }

    afterEach { paper.close() }

    fun owner(
        namespace: String = "projection",
        codec: WorksitePreparedSceneCodec = FarmMoleBurrowWorksiteSceneCodec(plugin, namespace),
    ): WorksitePreparedSceneOwner = WorksitePreparedSceneOwner(
        plugin = plugin,
        namespace = namespace,
        codec = codec,
        chunkRetention = WorksitePreparedSceneChunkRetention { chunk ->
            val key = "${chunk.world.name}:${chunk.x}:${chunk.z}"
            check(leases.add(key))
            object : AutoCloseable {
                override fun close() {
                    leases.remove(key)
                }
            }
        },
        blockDataDecoder = WorksitePreparedSceneBlockDataDecoder { Bukkit.createBlockData(it) },
    )

    fun scene(
        sceneId: Int = 1,
        xs: List<Int> = listOf(0),
        zoneId: String = "projection_zone",
        sequence: Long = 12,
        activeData: String = "minecraft:air",
    ): WorksitePreparedScene {
        val originalData = "minecraft:farmland[moisture=7]"
        xs.forEach { x ->
            world.getBlockAt(x, 64, 0).blockData = Bukkit.createBlockData(originalData)
        }
        val records = xs.map { x ->
            WorksitePreparedSceneRecord(
                world.name,
                zoneId,
                sequence,
                sceneId,
                x,
                64,
                0,
                originalData,
                activeData,
                "NONE",
                xs.size,
            )
        }
        return WorksitePreparedScene(
            world,
            zoneId,
            sequence,
            sceneId,
            Location(world, xs.minOrNull()!! + 0.5, 65.0, 0.5),
            Location(world, xs.first() + 0.5, 64.0, 0.5),
            Location(world, xs.last() + 1.5, 64.0, 0.5),
            records,
        )
    }

    fun prepareAndBuild(owner: WorksitePreparedSceneOwner, plan: WorksitePreparedScene) {
        owner.prepare(listOf(plan)) shouldBe true
        owner.process(plan.records.size) { true } shouldBe plan.records.size
        owner.isComplete(plan) shouldBe true
    }

    fun journalBytes(namespace: String, chunkX: Int): List<Byte>? = world.getChunkAt(chunkX, 0)
        .persistentDataContainer
        .get(NamespacedKey(plugin, "${namespace}_v1"), PersistentDataType.BYTE_ARRAY)
        ?.toList()

    test("bounded synchronous projection journals first and restores exact originals after owner restart") {
        val prepared = owner()
        val plan = scene()
        prepareAndBuild(prepared, plan)

        val position = Triple(0, 64, 0)
        prepared.project(plan, mapOf(position to "minecraft:gold_block")) shouldBe true
        world.getBlockAt(0, 64, 0).blockData.asString shouldBe "minecraft:gold_block"
        shouldThrow<IllegalArgumentException> {
            prepared.project(
                plan,
                (0..1_024).associate { x -> Triple(x, 64, 0) to "minecraft:air" },
            )
        }

        prepared.clearQueues()
        val restarted = owner()
        val recovered = restarted.scene(world, plan.zoneId, plan.sequence, plan.sceneId, plan.surface, 0, plan.start, plan.end)
        recovered?.records?.single()?.activeData shouldBe "minecraft:gold_block"
        restarted.beginRestore(world, plan.zoneId, plan.sequence, plan.sceneId)
        restarted.process(1) { true } shouldBe 1
        world.getBlockAt(0, 64, 0).blockData.asString shouldBe "minecraft:farmland[moisture=7]"
        leases shouldBe emptySet()
    }

    test("projection rejects a coordinate outside the owned journal without changing block or journal") {
        val prepared = owner()
        val plan = scene()
        prepareAndBuild(prepared, plan)
        val before = world.getBlockAt(0, 64, 0).blockData.asString
        val beforeJournal = journalBytes("projection", 0)

        shouldThrow<IllegalArgumentException> {
            prepared.project(plan, mapOf(Triple(100, 64, 0) to "minecraft:stone"))
        }

        world.getBlockAt(0, 64, 0).blockData.asString shouldBe before
        journalBytes("projection", 0) shouldBe beforeJournal
        prepared.clearQueues()
        val restarted = owner()
        val recovered = restarted.scene(world, plan.zoneId, plan.sequence, plan.sceneId, plan.surface, 0, plan.start, plan.end)
        recovered?.records?.single()?.activeData shouldBe "minecraft:air"
    }

    test("cross chunk journal encode failure rolls back every affected journal and block") {
        val namespace = "projection_cross_chunk"
        val failing = FailingProjectionCodec(FarmMoleBurrowWorksiteSceneCodec(plugin, namespace))
        val prepared = owner(namespace, failing)
        val plan = scene(xs = listOf(0, 16))
        prepareAndBuild(prepared, plan)
        val beforeJournals = mapOf(0 to journalBytes(namespace, 0), 1 to journalBytes(namespace, 1))
        failing.failingChunkX = 1

        prepared.project(
            plan,
            mapOf(
                Triple(0, 64, 0) to "minecraft:gold_block",
                Triple(16, 64, 0) to "minecraft:diamond_block",
            ),
        ) shouldBe false
        world.getBlockAt(0, 64, 0).blockData.asString shouldBe "minecraft:air"
        world.getBlockAt(16, 64, 0).blockData.asString shouldBe "minecraft:air"
        mapOf(0 to journalBytes(namespace, 0), 1 to journalBytes(namespace, 1)) shouldBe beforeJournals

        prepared.clearQueues()
        val restarted = owner(namespace, failing)
        val recovered = restarted.scene(world, plan.zoneId, plan.sequence, plan.sceneId, plan.surface, 0, plan.start, plan.end)
        recovered?.records?.associate { it.x to it.activeData } shouldBe mapOf(0 to "minecraft:air", 16 to "minecraft:air")
    }

    test("incremental readiness stays false until every slice is built and completion remains healthy after projection") {
        val prepared = owner()
        val plan = scene(xs = listOf(0, 1, 2))
        prepared.prepareIncrementally(plan, recordsPerSlice = 1) shouldBe true
        prepared.isComplete(plan) shouldBe false
        plan.ready shouldBe false

        repeat(plan.records.size - 1) {
            prepared.process(1) { true } shouldBe 1
            prepared.isComplete(plan) shouldBe false
            plan.ready shouldBe false
        }
        prepared.process(1) { true } shouldBe 1
        prepared.isBuilding(plan.zoneId, plan.sequence, plan.sceneId) shouldBe false
        prepared.isComplete(plan) shouldBe true
        plan.ready shouldBe true

        prepared.project(plan, mapOf(Triple(1, 64, 0) to "minecraft:gold_block")) shouldBe true
        prepared.isComplete(plan) shouldBe true
        world.getBlockAt(1, 64, 0).blockData.asString shouldBe "minecraft:gold_block"
    }

    test("reprojecting the baseline removes a stale body before scene reconstruction") {
        val prepared = owner()
        val plan = scene(xs = listOf(0, 1))
        prepareAndBuild(prepared, plan)
        prepared.project(
            plan,
            mapOf(
                Triple(0, 64, 0) to "minecraft:gold_block",
                Triple(1, 64, 0) to "minecraft:diamond_block",
            ),
        ) shouldBe true
        world.getBlockAt(0, 64, 0).blockData.asString shouldBe "minecraft:gold_block"
        world.getBlockAt(1, 64, 0).blockData.asString shouldBe "minecraft:diamond_block"

        prepared.clearQueues()
        val restarted = owner()
        val recovered = restarted.scene(world, plan.zoneId, plan.sequence, plan.sceneId, plan.surface, 0, plan.start, plan.end)!!
        restarted.project(
            recovered,
            recovered.records.associate { Triple(it.x, it.y, it.z) to it.originalData },
        ) shouldBe true
        world.getBlockAt(0, 64, 0).blockData.asString shouldBe "minecraft:farmland[moisture=7]"
        world.getBlockAt(1, 64, 0).blockData.asString shouldBe "minecraft:farmland[moisture=7]"

        restarted.clearQueues()
        val reconstructed = owner().scene(world, plan.zoneId, plan.sequence, plan.sceneId, plan.surface, 0, plan.start, plan.end)
        reconstructed?.records?.map { it.activeData } shouldBe listOf(
            "minecraft:farmland[moisture=7]",
            "minecraft:farmland[moisture=7]",
        )
    }
})
