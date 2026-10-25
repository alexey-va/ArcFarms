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
            world.getBlockAt(10, 73, 10).type = Material.EMERALD_BLOCK
            cleaning = true
            masks.cleanup()
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
            packets.last() shouldBe Material.AIR
            world.getBlockAt(10, 73, 10).type shouldBe Material.OAK_LOG
            // Another legitimate world edit is never replaced by the old captured tree.
            world.getBlockAt(10, 73, 10).type = Material.EMERALD_BLOCK
            permission = false
            failNext = true
            masks.release(player)
            packets.last() shouldBe Material.AIR
            retries.removeFirst().invoke()
            packets.last() shouldBe Material.EMERALD_BLOCK
            world.getBlockAt(10, 73, 10).type shouldBe Material.EMERALD_BLOCK
        }
    }
})
