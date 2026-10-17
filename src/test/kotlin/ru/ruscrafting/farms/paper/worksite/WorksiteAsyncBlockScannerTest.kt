package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor

class WorksiteAsyncBlockScannerTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("captures chunks in bounded slices and plans only from the detached snapshot") {
        val world = paper.server.addSimpleWorld("world")
        val positions = (0..2).map { chunkX ->
            world.getChunkAt(chunkX, 0)
            WorksitePosition("world", chunkX * 16 + 1, 64, 1).also { position ->
                world.getBlockAt(position.x, position.y, position.z).type = Material.STONE
            }
        }
        val delayed = ArrayDeque<() -> Unit>()
        val token = mockk<RuntimeTaskSupervisor.Token>()
        var asyncCalls = 0
        var onWorker = false
        val tasks = mockk<WorksiteTaskPort>(relaxed = true) {
            every { lifecycleToken() } returns token
            every { capture(token, any(), any()) } answers {
                val current = secondArg<() -> Boolean>()
                val step = thirdArg<() -> Boolean>()
                val result = java.util.concurrent.CompletableFuture<Unit>()
                fun slice() {
                    if (!current()) result.cancel(false)
                    else if (step()) result.complete(Unit)
                    else delayed.addLast(::slice)
                }
                delayed.addLast(::slice)
                result
            }
            every { runAsync(token, any()) } answers {
                asyncCalls++
                onWorker = true
                try { secondArg<() -> Unit>().invoke() } finally { onWorker = false }
                true
            }
            every { runSync(token, any()) } answers {
                val previous = onWorker
                onWorker = false
                try { secondArg<() -> Unit>().invoke() } finally { onWorker = previous }
                true
            }
        }
        var result: Result<List<Material>>? = null
        val scanner = WorksiteAsyncBlockScanner(tasks)

        scanner.submit(
            world = world,
            chunkCoordinates = positions.map { WorksiteChunkCoordinate(it.x shr 4, it.z shr 4) },
            stillValid = { true },
            plan = { snapshot -> onWorker shouldBe true; positions.map { snapshot.type(it) ?: Material.AIR } },
            complete = { result = it },
        ) shouldBe true

        var captureSlices = 0
        while (delayed.isNotEmpty()) {
            captureSlices++
            delayed.removeFirst().invoke()
        }

        captureSlices shouldBe 3
        asyncCalls shouldBe 2
        result!!.getOrThrow().shouldContainExactly(Material.STONE, Material.STONE, Material.STONE)
    }
})
