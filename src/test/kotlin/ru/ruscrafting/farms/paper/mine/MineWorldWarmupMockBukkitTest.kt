package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Chunk
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.mine.index.MineChunkLoader
import ru.ruscrafting.farms.paper.mine.index.MineChunkTicket
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.concurrent.CompletableFuture

class MineWorldWarmupMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("retains the complete MyWorlds radius once and releases only owned tickets") {
        val world = paper.server.addSimpleWorld("mine")
        val requests = mutableListOf<Pair<Int, Int>>()
        val tickets = RecordingWarmupTickets()
        val warmup = MineWorldWarmup(
            tickets = tickets,
            tasks = immediateTasks(),
            chunkLoader = MineChunkLoader { loadedWorld, chunkX, chunkZ ->
                requests += chunkX to chunkZ
                CompletableFuture.completedFuture(loadedWorld.getChunkAt(chunkX, chunkZ))
            },
        )

        warmup.activate(listOf(world, world))

        val expected = (-3..3).flatMap { x -> (-3..3).map { z -> x to z } }
        requests shouldContainExactlyInAnyOrder expected
        requests.size shouldBe 49
        tickets.retained shouldContainExactlyInAnyOrder expected

        warmup.cleanup()
        tickets.released shouldContainExactlyInAnyOrder expected
    }

    test("does not retain a chunk that completes after cleanup") {
        val world = paper.server.addSimpleWorld("mine")
        val pending = CompletableFuture<Chunk?>()
        val tickets = RecordingWarmupTickets()
        val warmup = MineWorldWarmup(
            tickets = tickets,
            tasks = immediateTasks(),
            chunkLoader = MineChunkLoader { _, _, _ -> pending },
        )

        warmup.activate(listOf(world))
        warmup.cleanup()
        pending.complete(world.getChunkAt(0, 0))

        tickets.retained shouldBe emptyList()
        tickets.released shouldBe emptyList()
    }
})

private fun immediateTasks(): WorksiteTaskPort {
    val token = mockk<RuntimeTaskSupervisor.Token>()
    return mockk {
        every { lifecycleToken() } returns token
        every { runSync(token, any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
    }
}

private class RecordingWarmupTickets : MineChunkTicket {
    val retained = mutableListOf<Pair<Int, Int>>()
    val released = mutableListOf<Pair<Int, Int>>()

    override fun retain(chunk: Chunk): Boolean {
        retained += chunk.x to chunk.z
        return true
    }

    override fun release(chunk: Chunk) {
        released += chunk.x to chunk.z
    }
}
