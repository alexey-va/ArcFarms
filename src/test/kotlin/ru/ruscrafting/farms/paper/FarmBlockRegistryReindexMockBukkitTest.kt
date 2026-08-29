package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmCropLayoutSettings

class FarmBlockRegistryReindexMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        for (chunkX in 0..1) {
            for (chunkZ in 0..1) world.getChunkAt(chunkX, chunkZ).load()
        }
    }

    afterEach {
        Tasks.reset()
        paper.close()
    }

    test("loaded chunks share one scan budget without a tick gap per chunk") {
        failOnUnsupportedMockOperation {
            val plugin = paper.createSimplePlugin("FarmReindexBatchTest")
            val scheduler = TestTaskScheduler()
            Tasks.install(scheduler)
            val registry = FarmBlockRegistry(
                plugin,
                FarmBlockLedger(plugin),
                addChunkTicket = { true },
                removeChunkTicket = {},
            )
            var completed: FarmBlockReindexResult? = null

            val start = registry.startReindex(
                definition = FarmBlockIndexDefinition(
                    zoneId = "farm",
                    region = CuboidActivityRegion(
                        world,
                        "farm",
                        CuboidBounds(0, 64, 0, 31, 65, 31),
                    ),
                    crops = setOf("WHEAT"),
                    blocksPerTick = 2_048,
                    maxBlocks = 10_000,
                    maxOrchardLeaves = 1_024,
                    cropLayout = FarmCropLayoutSettings(
                        enabled = true,
                        weights = mapOf("WHEAT" to 1),
                        smallComponentMaxSize = 0,
                        smallComponentMergeDistance = 0,
                    ),
                ),
                onProgress = {},
                onComplete = { completed = it },
                onFailure = { throw it },
            )

            (start is FarmBlockReindexStart.Started) shouldBe true
            completed?.status?.scannedBlocks shouldBe 2_048L
            completed?.status?.appliedChunks shouldBe 4
        }
    }
})

private fun <T> failOnUnsupportedMockOperation(block: () -> T): T = try {
    block()
} catch (failure: Throwable) {
    val unsupported = generateSequence(failure as Throwable?) { it.cause }
        .firstOrNull { it.javaClass.name == "org.mockbukkit.mockbukkit.exception.UnimplementedOperationException" }
    if (unsupported != null) {
        throw AssertionError("Reindex test reached an unsupported MockBukkit operation", unsupported)
    }
    throw failure
}
