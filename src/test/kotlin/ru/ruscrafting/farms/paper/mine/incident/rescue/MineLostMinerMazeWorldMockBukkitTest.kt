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
        for (chunkX in -3..3) for (chunkZ in -3..3) world.getChunkAt(chunkX, chunkZ).load()
        for (x in -48..48) for (z in -48..48) for (y in 61..66) world.getBlockAt(x, y, z).type = org.bukkit.Material.STONE
        plugin = paper.createSimplePlugin("LostMinerMaze")
    }

    afterEach { paper.close() }

    test("temporary maze builds from exact BlockData and restores after restart") {
        val runtime = runtime(world)
        val target = ru.ruscrafting.farms.domain.worksite.WorksitePosition(world.name, 10, 63, 10)
        val retention = RecordingMazeRetention()
        val first = MineLostMinerMazeWorld(plugin, ArcFarmsDebug({ false }) {}, retention, MockBukkitFarmBlockDataDecoder)

        val (_, planned) = first.ensure(runtime, target)
        val scene = requireNotNull(planned)
        scene.records.shouldNotBeEmpty()
        scene.records.any { it.mazeData == org.bukkit.Material.OCHRE_FROGLIGHT.createBlockData().asString } shouldBe true
        scene.records.all { it.x !in 0..20 || it.z !in 0..20 } shouldBe true
        scene.surface.blockX shouldBe target.x
        scene.surface.blockZ shouldBe target.z
        (scene.targetPosition().x !in 0..20) shouldBe true
        val originals = scene.records.associate { Triple(it.x, it.y, it.z) to it.originalData }
        first.process(scene.records.size) { true } shouldBe scene.records.size
        scene.ready shouldBe true

        // A new owner sees the PDC journal and reconstructs the same entrance/target.
        first.clearQueues()
        val restarted = MineLostMinerMazeWorld(plugin, ArcFarmsDebug({ false }) {}, retention, MockBukkitFarmBlockDataDecoder)
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
