package ru.ruscrafting.farms.paper.mine.incident.rescue

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.ZoneReference
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.mineV2Settings
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmBlockDataDecoder

class MineLostMinerMazeWorldMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var plugin: Plugin

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("world")
        for (chunkX in -8..8) for (chunkZ in -8..8) world.getChunkAt(chunkX, chunkZ).load()
        for (x in -48..48) for (z in -48..48) for (y in 61..66) world.getBlockAt(x, y, z).type = org.bukkit.Material.STONE
        plugin = paper.createSimplePlugin("LostMinerMaze")
    }

    afterEach { paper.close() }

    test("inactive legacy cave restores exact originals before the replacement is prepared") {
        val runtime=runtime(world)
        val target=ru.ruscrafting.farms.domain.worksite.WorksitePosition(world.name,10,63,10)
        val port=ru.ruscrafting.farms.paper.mine.immediateMinePort()
        val first=MineLostMinerMazeWorld(plugin,ArcFarmsDebug({false}) {},RecordingMazeRetention(),MockBukkitFarmBlockDataDecoder,port)
        first.ensure(runtime,target)
        repeat(256) { first.process(128) {true} }
        val scene=first.ensure(runtime,target).second!!
        scene.ready shouldBe true
        scene.records.groupBy { it.x shr 4 to (it.z shr 4) }.forEach { (chunk,records) ->
            world.getChunkAt(chunk.first,chunk.second).persistentDataContainer.set(org.bukkit.NamespacedKey(plugin,"mine_lost_miner_maze_v1"),
                org.bukkit.persistence.PersistentDataType.BYTE_ARRAY,MineLostMinerMazeJournalCodec.encode(records.map { it.copy(geometryVersion=1) },world.name,chunk.first,chunk.second,world.minHeight,world.maxHeight))
        }
        first.clearQueues()
        val next=MineLostMinerMazeWorld(plugin,ArcFarmsDebug({false}) {},RecordingMazeRetention(),MockBukkitFarmBlockDataDecoder,port)
        next.reconcileLoaded(retainLegacy={_,_->false}) {_,_->true}
        repeat(128) { next.process(128) {true} }
        scene.records.forEach { world.getBlockAt(it.x,it.y,it.z).blockData.asString shouldBe it.originalData }
        next.ensure(runtime,target)
        repeat(256) { next.process(128) {true} }
        val rebuilt=next.ensure(runtime,target).second!!
        rebuilt.records.all { it.geometryVersion==2 } shouldBe true
        val late=scene.records.first().copy(x=194,z=-63,originalData="minecraft:stone",mazeData="minecraft:cobblestone",
            marker=MineLostMinerMazeMarker.NONE,totalRecords=1,geometryVersion=1)
        val chunk=world.getChunkAt(late.x shr 4,late.z shr 4)
        world.getBlockAt(late.x,late.y,late.z).type=org.bukkit.Material.COBBLESTONE
        chunk.persistentDataContainer.set(org.bukkit.NamespacedKey(plugin,"mine_lost_miner_maze_v1"),
            org.bukkit.persistence.PersistentDataType.BYTE_ARRAY,MineLostMinerMazeJournalCodec.encode(listOf(late),world.name,chunk.x,chunk.z,world.minHeight,world.maxHeight))
        next.onChunkLoad(chunk,retainLegacy={_,_->true}) {_,_->true}
        next.process(128) {true}
        world.getBlockAt(late.x,late.y,late.z).type shouldBe org.bukkit.Material.STONE
        next.isRestoring(runtime) shouldBe false
        next.scene(runtime) shouldBe rebuilt
        rebuilt.ready shouldBe true
    }

    test("temporary maze builds from exact BlockData and restores after restart") {
        val runtime = runtime(world)
        val target = ru.ruscrafting.farms.domain.worksite.WorksitePosition(world.name, 10, 63, 10)
        val retention = RecordingMazeRetention()
        val first = MineLostMinerMazeWorld(plugin, ArcFarmsDebug({ false }) {}, retention, MockBukkitFarmBlockDataDecoder, ru.ruscrafting.farms.paper.mine.immediateMinePort())

        first.ensure(runtime, target).first shouldBe MineLostMinerMazeEnsureResult.BUILDING
        // Capturing originals is now spread over ticks before the durable build is exposed.
        repeat(256) { first.process(128) { true } }
        val scene = requireNotNull(first.ensure(runtime, target).second)
        scene.records.shouldNotBeEmpty()
        scene.records.count { it.mazeData.startsWith("minecraft:lantern") }.let { it in 1..6 } shouldBe true
        scene.records.all { it.geometryVersion == 2 } shouldBe true
        scene.records.all { it.x !in 0..20 || it.z !in 0..20 } shouldBe true
        scene.surface.blockX shouldBe target.x
        scene.surface.blockZ shouldBe target.z
        (scene.targetPosition().x !in 0..20) shouldBe true
        val originals = scene.records.associate { Triple(it.x, it.y, it.z) to it.originalData }
        first.process(scene.records.size) { true }
        scene.ready shouldBe true

        // A new owner sees the PDC journal and reconstructs the same entrance/target.
        first.clearQueues()
        val restarted = MineLostMinerMazeWorld(plugin, ArcFarmsDebug({ false }) {}, retention, MockBukkitFarmBlockDataDecoder, ru.ruscrafting.farms.paper.mine.immediateMinePort())
        val recovered = requireNotNull(restarted.ensure(runtime, target).second)
        recovered.start shouldBe scene.start
        recovered.target shouldBe scene.target
        restarted.beginRestore(world, runtime.settings.id, runtime.state.sequence)
        restarted.process(scene.records.size) { false } shouldBe scene.records.size
        originals.forEach { (position, data) -> world.getBlockAt(position.first, position.second, position.third).blockData.asString shouldBe data }
        retention.retained shouldBe 0
    }
})

private fun runtime(world: World): MineRuntime {
    val settings = mineV2Settings().copy(
        reference = ZoneReference(world.name, null, CuboidBounds(0, 50, 0, 20, 90, 20)),
    )
    return MineRuntime(
        settings,
        CuboidActivityRegion(world, "mine", settings.reference.bounds!!),
        cooldownMillis = 5_000L,
        state = MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 17, orderId = "ore_run"),
    )
}

private class RecordingMazeRetention : MineLostMinerMazeChunkRetention {
    var retained: Int = 0
        private set

    override fun retain(chunk: Chunk): MineLostMinerMazeChunkLease {
        retained++
        return MineLostMinerMazeChunkLease { retained-- }
    }
}
