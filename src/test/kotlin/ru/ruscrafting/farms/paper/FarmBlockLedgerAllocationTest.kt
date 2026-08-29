package ru.ruscrafting.farms.paper

import com.sun.management.ThreadMXBean
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.FarmPlotPosition
import java.lang.management.ManagementFactory

class FarmBlockLedgerAllocationTest : FunSpec({
    test("cached maintenance lookup stays within its per-pass allocation budget") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("farm")
            world.getChunkAt(0, 0).load()
            val soils = buildList {
                for (x in 0 until 16) {
                    for (z in 0 until 16) {
                        add(world.getBlockAt(x, 64, z).apply { type = Material.FARMLAND })
                    }
                }
            }
            val ledger = FarmBlockLedger(paper.createSimplePlugin("FarmBlockLedgerAllocationTest"))
            ledger.captureAll(soils, "farm")
            repeat(10) { ledger.records(soils).size shouldBe soils.size }

            val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
            bean.isThreadAllocatedMemoryEnabled = true
            val threadId = Thread.currentThread().threadId()
            val before = bean.getThreadAllocatedBytes(threadId)
            var observed = 0
            repeat(100) { observed = ledger.records(soils).size }
            val averageBytes = (bean.getThreadAllocatedBytes(threadId) - before) / 100

            observed shouldBe soils.size
            averageBytes shouldBeLessThan 32L * 1_024L
        }
    }

    test("reusable maintenance lookup does not allocate per farm bed") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("farm")
            world.getChunkAt(0, 0).load()
            val soils = buildList {
                for (x in 0 until 16) {
                    for (z in 0 until 16) {
                        add(world.getBlockAt(x, 64, z).apply { type = Material.FARMLAND })
                    }
                }
            }
            val ledger = FarmBlockLedger(paper.createSimplePlugin("FarmBlockReusableLookupAllocationTest"))
            ledger.captureAll(soils, "farm")
            val positionedSoils = soils.map { soil ->
                FarmPlotPosition(soil.world.name, soil.x, soil.y, soil.z) to soil
            }
            val lookup = ledger.recordLookup()
            repeat(10) {
                lookup.reset()
                positionedSoils.count { (position, soil) -> lookup.record(position, soil) != null } shouldBe soils.size
            }

            val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
            bean.isThreadAllocatedMemoryEnabled = true
            val threadId = Thread.currentThread().threadId()
            val before = bean.getThreadAllocatedBytes(threadId)
            var observed = 0
            repeat(100) {
                lookup.reset()
                observed = positionedSoils.count { (position, soil) -> lookup.record(position, soil) != null }
            }
            val averageBytes = (bean.getThreadAllocatedBytes(threadId) - before) / 100

            observed shouldBe soils.size
            averageBytes shouldBeLessThan 2L * 1_024L
        }
    }
})
