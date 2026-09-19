package ru.ruscrafting.farms.paper.worksite.scene

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorksiteSceneCodec

class WorksitePreparedSceneOwnerMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var plugin: Plugin
    lateinit var leases: MutableSet<String>

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("sp11")
        world.getChunkAt(0, 0).load()
        plugin = paper.createSimplePlugin("PreparedSceneOwnerTest")
        leases = linkedSetOf()
    }

    afterEach { paper.close() }

    fun owner(namespace: String = "mine_working"): WorksitePreparedSceneOwner = WorksitePreparedSceneOwner(
        plugin = plugin,
        namespace = namespace,
        codec = FarmMoleBurrowWorksiteSceneCodec(plugin, namespace),
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

    fun scene(sceneId: Int, x: Int, zoneId: String = "mine_zone"): WorksitePreparedScene {
        val original = "minecraft:farmland[moisture=7]"
        val block = world.getBlockAt(x, 64, 0)
        block.blockData = Bukkit.createBlockData(original)
        val records = listOf(
            WorksitePreparedSceneRecord(
                world.name, zoneId, 12, sceneId, x, 64, 0,
                original, "minecraft:air", "NONE", 1,
            ),
        )
        return WorksitePreparedScene(
            world,
            zoneId,
            12,
            sceneId,
            Location(world, x + 0.5, 65.0, 0.5),
            Location(world, x + 0.5, 64.0, 0.5),
            Location(world, x + 1.5, 64.0, 0.5),
            records,
        )
    }

    test("prepare builds exact BlockData and restart restores the durable scene") {
        val first = owner()
        val plan = scene(1, 0)

        first.prepare(listOf(plan)) shouldBe true
        first.isBuilding("mine_zone", 12, 1) shouldBe true
        first.process(1) { true } shouldBe 1
        world.getBlockAt(0, 64, 0).blockData.asString shouldBe "minecraft:air"
        first.isBuilding("mine_zone", 12, 1) shouldBe false

        first.clearQueues()
        val restarted = owner()
        val loaded = restarted.scene(
            world,
            "mine_zone",
            12,
            1,
            plan.surface,
            0,
            start = plan.start,
            end = plan.end,
        )
        loaded?.records?.single()?.originalData shouldBe "minecraft:farmland[moisture=7]"
        restarted.onChunkLoad(world.getChunkAt(0, 0), { zoneId, sequence ->
            zoneId == "mine_zone" && sequence == 12L
        })
        restarted.isBuilding("mine_zone", 12, 1) shouldBe false

        restarted.beginRestore(world, "mine_zone", 12)
        restarted.process(1) { true } shouldBe 1
        world.getBlockAt(0, 64, 0).blockData.asString shouldBe "minecraft:farmland[moisture=7]"
        restarted.protects(plan.start) shouldBe false
    }

    test("chunk reconciliation restores an orphaned scene with the same sequence") {
        val first = owner()
        val active = scene(1, 0)
        val orphan = scene(2, 1)

        first.prepare(listOf(active, orphan)) shouldBe true
        first.process(2) { true } shouldBe 2
        first.clearQueues()
        world.getBlockAt(0, 64, 0).type = Material.STONE
        world.getBlockAt(1, 64, 0).type = Material.STONE

        val restarted = owner()
        restarted.onChunkLoad(world.getChunkAt(0, 0), { zoneId, sequence ->
            zoneId == "mine_zone" && sequence == 12L
        }) { record ->
            record.sceneId == 1
        }
        restarted.isBuilding("mine_zone", 12, 1) shouldBe true
        restarted.process(2) { true } shouldBe 2
        world.getBlockAt(0, 64, 0).blockData.asString shouldBe "minecraft:air"
        world.getBlockAt(1, 64, 0).blockData.asString shouldBe "minecraft:farmland[moisture=7]"
    }

    test("chunk reconciliation does not requeue a healthy block in a progressing scene") {
        val prepared = owner()
        val first = scene(1, 0)
        val second = scene(1, 1)
        val combined = first.copy(
            end = second.end,
            records = listOf(first.records.single(), second.records.single()).map { it.copy(totalRecords = 2) },
        )

        prepared.prepare(listOf(combined)) shouldBe true
        prepared.process(1) { true } shouldBe 1
        prepared.hasPendingBlock(first.start) shouldBe false

        prepared.onChunkLoad(world.getChunkAt(0, 0), { zoneId, sequence ->
            zoneId == "mine_zone" && sequence == 12L
        })
        prepared.hasPendingBlock(first.start) shouldBe false
        prepared.hasPendingBlock(second.start) shouldBe true
    }

    test("restore rejection is deferred and retried without dropping the journal") {
        val prepared = owner()
        val plan = scene(1, 0)
        prepared.prepare(listOf(plan)) shouldBe true
        prepared.process(1) { true } shouldBe 1
        prepared.beginRestore(world, "mine_zone", 12)

        prepared.process(1) { false } shouldBe 0
        prepared.hasPendingBlock(plan.start) shouldBe true
        world.getBlockAt(0, 64, 0).blockData.asString shouldBe "minecraft:air"

        prepared.process(1) { true } shouldBe 1
        world.getBlockAt(0, 64, 0).blockData.asString shouldBe "minecraft:farmland[moisture=7]"
        prepared.hasPendingBlock(plan.start) shouldBe false
    }
})
