package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LumberBlockMaskTest : FunSpec({
    test("readiness waits for all bounded hide batches and stays stable during an ordinary refresh") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = MaskReadinessFixture(paper, 49)
            f.masks.apply(f.player, f.points)
            f.masks.ready(f.player, f.points) shouldBe false
            f.step()
            f.packets.size shouldBe 24
            f.masks.ready(f.player, f.points) shouldBe false
            f.step()
            f.packets.size shouldBe 48
            f.masks.ready(f.player, f.points) shouldBe false
            f.step()
            f.packets.size shouldBe 49
            f.masks.ready(f.player, f.points) shouldBe true
            f.masks.apply(f.player, f.points, refresh = true)
            f.masks.ready(f.player, f.points) shouldBe true
            f.step()
            f.masks.ready(f.player, f.points) shouldBe true
            f.finish()
            f.masks.ready(f.player, f.points) shouldBe true
            f.points.forEach { f.world.getBlockAt(it.x, it.y, it.z).type shouldBe Material.OAK_LOG }
        }
    }

    test("uncertain hide and restore sends never approve a new hide before its successful packet") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = MaskReadinessFixture(paper, 1)
            f.failNext = true
            f.masks.apply(f.player, f.points)
            f.step()
            f.masks.ready(f.player, f.points) shouldBe false
            f.world.getBlockAt(0, 73, 10).type = Material.EMERALD_BLOCK
            f.permission = false
            f.masks.release(f.player)
            f.finish()
            f.packets.last() shouldBe Material.EMERALD_BLOCK
            f.permission = true
            f.masks.apply(f.player, f.points)
            f.finish()
            f.masks.ready(f.player, f.points) shouldBe true
            f.permission = false
            f.failNext = true
            f.masks.release(f.player)
            f.step()
            f.permission = true
            f.masks.apply(f.player, f.points)
            f.masks.ready(f.player, f.points) shouldBe false
            f.finish()
            f.masks.ready(f.player, f.points) shouldBe true
            f.masks.cleanup()
            f.masks.ready(f.player, f.points) shouldBe false
            f.packets.last() shouldBe Material.EMERALD_BLOCK
        }
    }

    test("unloaded chunks world changes and a new connection invalidate old hide confirmations") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = MaskReadinessFixture(paper, 49)
            f.masks.apply(f.player, f.points)
            f.finish()
            f.masks.ready(f.player, f.points) shouldBe true
            f.world.unloadChunk(1, 0, false) shouldBe true
            f.masks.ready(f.player, f.points) shouldBe false
            f.world.getChunkAt(1, 0)
            f.masks.ready(f.player, f.points) shouldBe false
            f.masks.apply(f.player, f.points, refresh = true)
            f.finish()
            f.masks.ready(f.player, f.points) shouldBe true
            f.currentWorld = paper.server.addSimpleWorld("other_mask_world")
            f.masks.ready(f.player, f.points) shouldBe false
            f.currentWorld = f.world
            f.masks.ready(f.player, f.points) shouldBe false
            f.masks.apply(f.player, f.points, refresh = true)
            f.finish()
            val nextConnection = f.viewer()
            f.masks.ready(nextConnection, f.points) shouldBe false
            f.masks.apply(nextConnection, f.points)
            f.masks.release(f.player) // A stale disconnect cannot retire the new connection's job.
            f.masks.ready(nextConnection, f.points) shouldBe false
            f.finish()
            f.masks.ready(nextConnection, f.points) shouldBe true
            f.masks.ready(f.player, f.points) shouldBe false
            f.masks.cleanup()
            f.masks.ready(nextConnection, f.points) shouldBe false
        }
    }

    test("an uncertain AIR send remains restorable after permission loss and a failed restore retries current data") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("cedar")
            world.getChunkAt(0, 0)
            val point = WorksitePosition("cedar", 10, 73, 10)
            world.getBlockAt(10, 73, 10).type = Material.OAK_LOG
            val packets = mutableListOf<Material>()
            var permission = true
            var throwAfterHide = true
            var throwAfterRestore = true
            val player = mockk<Player>(relaxed = true) {
                every { uniqueId } returns UUID.randomUUID()
                every { isOnline } returns true
                every { this@mockk.world } returns world
                every { hasPermission(any<String>()) } answers { permission }
                every { sendBlockChange(any<Location>(), any<BlockData>()) } answers {
                    val material = secondArg<BlockData>().material
                    packets += material
                    if (material == Material.AIR && throwAfterHide) {
                        throwAfterHide = false
                        throw IllegalStateException("AIR was written before the connection failed")
                    }
                    if (material == Material.EMERALD_BLOCK && throwAfterRestore) {
                        throwAfterRestore = false
                        throw IllegalStateException("Restore was written before the connection failed")
                    }
                    Unit
                }
            }
            val retries = ArrayDeque<() -> Unit>()
            val token = mockk<RuntimeTaskSupervisor.Token>()
            val tasks = mockk<WorksiteTaskPort>(relaxed = true) {
                every { lifecycleToken() } returns token
                every { capture(token, any(), any()) } answers {
                    val valid = secondArg<() -> Boolean>()
                    val step = thirdArg<() -> Boolean>()
                    runCatching { while (valid() && !step()) Unit }.fold(
                        { CompletableFuture.completedFuture(Unit) }, { CompletableFuture.failedFuture(it) })
                }
                every { runSync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
                every { runLater(token, any(), any()) } answers { retries.addLast(thirdArg()); true }
            }
            val masks = LumberBlockMask(tasks, mockk(relaxed = true))
            masks.apply(player, listOf(point))
            masks.ready(player, listOf(point)) shouldBe false
            packets.last() shouldBe Material.AIR
            world.getBlockAt(10, 73, 10).type = Material.EMERALD_BLOCK
            permission = false
            masks.release(player)
            packets.last() shouldBe Material.EMERALD_BLOCK
            world.getBlockAt(10, 73, 10).type = Material.DIAMOND_BLOCK
            while (retries.isNotEmpty()) retries.removeFirst().invoke()
            packets.last() shouldBe Material.DIAMOND_BLOCK
            world.getBlockAt(10, 73, 10).type shouldBe Material.DIAMOND_BLOCK
        }
    }

    test("one cleanup connection failure does not prevent other viewers restoring or make cleanup retry forever") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("cedar")
            world.getChunkAt(0, 0)
            val point = WorksitePosition("cedar", 10, 73, 10)
            world.getBlockAt(10, 73, 10).type = Material.OAK_LOG
            var cleaning = false
            val packets = mutableMapOf<UUID, MutableList<Material>>()
            fun viewer(fails: Boolean): Player {
                val id = UUID.randomUUID()
                packets[id] = mutableListOf()
                return mockk<Player>(relaxed = true) {
                    every { uniqueId } returns id
                    every { isOnline } returns true
                    every { this@mockk.world } returns world
                    every { hasPermission(any<String>()) } returns true
                    every { sendBlockChange(any<Location>(), any<BlockData>()) } answers {
                        if (cleaning && fails) throw IllegalStateException("one viewer connection failed")
                        packets.getValue(id) += secondArg<BlockData>().material
                        Unit
                    }
                }
            }
            val failing = viewer(true)
            val healthy = viewer(false)
            val token = mockk<RuntimeTaskSupervisor.Token>()
            val tasks = mockk<WorksiteTaskPort>(relaxed = true) {
                every { lifecycleToken() } returns token
                every { capture(token, any(), any()) } answers {
                    val valid = secondArg<() -> Boolean>(); val step = thirdArg<() -> Boolean>()
                    while (valid() && !step()) Unit
                    CompletableFuture.completedFuture(Unit)
                }
                every { runSync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
            }
            val storage = mockk<WorksiteStatePort>(relaxed = true)
            val masks = LumberBlockMask(tasks, storage)
            masks.apply(failing, listOf(point)); masks.apply(healthy, listOf(point))
            masks.ready(healthy, listOf(point)) shouldBe true
            world.getBlockAt(10, 73, 10).type = Material.EMERALD_BLOCK
            cleaning = true
            masks.cleanup()
            masks.ready(healthy, listOf(point)) shouldBe false
            packets.getValue(healthy.uniqueId).last() shouldBe Material.EMERALD_BLOCK
            val delivered = packets.getValue(healthy.uniqueId).size
            masks.cleanup()
            packets.getValue(healthy.uniqueId).size shouldBe delivered
            verify(exactly = 1) { storage.log(java.util.logging.Level.WARNING, match { "cleanup" in it && "failures=1" in it }, any()) }
        }
    }

    test("private mask leaves terrain intact and permission loss restores current world data") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("cedar")
            world.getChunkAt(0, 0)
            val point = WorksitePosition("cedar", 10, 73, 10)
            world.getBlockAt(10, 73, 10).type = Material.OAK_LOG
            val packets = mutableListOf<Material>()
            var permission = true
            var failNext = false
            val retries = ArrayDeque<() -> Unit>()
            val player = mockk<Player>(relaxed = true) {
                every { uniqueId } returns UUID.randomUUID()
                every { isOnline } returns true
                every { this@mockk.world } returns world
                every { hasPermission(any<String>()) } answers { permission }
                every { sendBlockChange(any<Location>(), any<BlockData>()) } answers { packets += secondArg<BlockData>().material; Unit }
            }
            val token = mockk<RuntimeTaskSupervisor.Token>()
            val tasks = mockk<WorksiteTaskPort>(relaxed = true) {
                every { lifecycleToken() } returns token
                every { capture(token, any(), any()) } answers {
                    if (failNext) {
                        failNext = false
                        CompletableFuture.failedFuture(IllegalStateException("simulated connection capture failure"))
                    } else {
                        val valid = secondArg<() -> Boolean>()
                        val step = thirdArg<() -> Boolean>()
                        while (valid() && !step()) Unit
                        CompletableFuture.completedFuture(Unit)
                    }
                }
                every { runSync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
                every { runLater(token, any(), any()) } answers { retries.addLast(thirdArg()); true }
            }
            val masks = LumberBlockMask(tasks, mockk(relaxed = true))
            masks.apply(player, listOf(point))
            masks.ready(player, listOf(point)) shouldBe true
            packets.last() shouldBe Material.AIR
            world.getBlockAt(10, 73, 10).type shouldBe Material.OAK_LOG
            // Another legitimate world edit is never replaced by the old captured tree.
            world.getBlockAt(10, 73, 10).type = Material.EMERALD_BLOCK
            permission = false
            masks.ready(player, listOf(point)) shouldBe false
            failNext = true
            masks.release(player)
            packets.last() shouldBe Material.AIR
            retries.removeFirst().invoke()
            packets.last() shouldBe Material.EMERALD_BLOCK
            world.getBlockAt(10, 73, 10).type shouldBe Material.EMERALD_BLOCK
        }
    }
})

private class MaskReadinessFixture(paper: MockBukkitTestRuntime, size: Int) {
    val world = paper.server.addSimpleWorld("cedar")
    var currentWorld = world
    val points = (0 until size).map { WorksitePosition(world.name, it, 73, 10) }.also { positions ->
        positions.forEach { world.getChunkAt(it.x shr 4, it.z shr 4); world.getBlockAt(it.x, it.y, it.z).type = Material.OAK_LOG }
    }
    private val id = UUID.randomUUID()
    var permission = true
    var failNext = false
    val packets = mutableListOf<Material>()
    fun viewer(): Player = mockk(relaxed = true) {
        every { uniqueId } returns id
        every { isOnline } returns true
        every { this@mockk.world } answers { currentWorld }
        every { hasPermission(any<String>()) } answers { permission }
        every { sendBlockChange(any<Location>(), any<BlockData>()) } answers {
            packets += secondArg<BlockData>().material
            if (failNext) { failNext = false; throw IllegalStateException("write may have reached the connection") }
            Unit
        }
    }
    val player = viewer()
    private data class Capture(val valid: () -> Boolean, val step: () -> Boolean, val result: CompletableFuture<Unit>)
    private val captures = ArrayDeque<Capture>()
    private val token = mockk<RuntimeTaskSupervisor.Token>()
    private val tasks = mockk<WorksiteTaskPort>(relaxed = true) {
        every { lifecycleToken() } returns token
        every { capture(token, any(), any()) } answers {
            CompletableFuture<Unit>().also { captures += Capture(secondArg(), thirdArg(), it) }
        }
        every { runSync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
    }
    val masks = LumberBlockMask(tasks, mockk(relaxed = true))
    fun step() {
        val capture = captures.first()
        if (!capture.valid()) { captures.removeFirst(); capture.result.cancel(false); return }
        runCatching(capture.step).onSuccess { complete ->
            if (complete) { captures.removeFirst(); capture.result.complete(Unit) }
        }.onFailure { failure -> captures.removeFirst(); capture.result.completeExceptionally(failure) }
    }
    fun finish() {
        var steps = 0
        while (captures.isNotEmpty() && steps++ < 100) step()
        captures.isEmpty() shouldBe true
    }
}
