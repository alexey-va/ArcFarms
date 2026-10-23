package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.bukkit.Material
import org.bukkit.block.BlockFace
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.FarmPlotPosition

class FarmBlockLedgerLookupTest : FunSpec({
    test("maintenance passes reuse records and observe updates without mutating earlier results") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("farm")
            world.getChunkAt(0, 0).load()
            val soils = listOf(1, 2).map { x ->
                world.getBlockAt(x, 64, 3).apply { type = Material.FARMLAND }
            }
            val positions = soils.map { FarmPlotPosition(world.name, it.x, it.y, it.z) }
            val ledger = FarmBlockLedger(paper.createSimplePlugin("FarmBlockLedgerLookupTest"))
            ledger.captureAll(soils, "farm")
            val before = ledger.records(soils)
            before.keys shouldBe positions.toSet()

            val repeated = ledger.records(soils)
            positions.forEach { position ->
                repeated.getValue(position) shouldBeSameInstanceAs before.getValue(position)
            }

            ledger.remove(soils.first()) shouldBe true
            val crop = soils.last().getRelative(BlockFace.UP).apply { type = Material.WHEAT }
            val updated = ledger.captureActiveCrop(soils.last(), "farm")
            val after = ledger.records(soils)
            after.keys shouldBe setOf(positions.last())
            after.getValue(positions.last()) shouldBeSameInstanceAs updated
            after.getValue(positions.last()).activeCropData shouldBe crop.blockData.asString
            before.keys shouldBe positions.toSet()
            before.getValue(positions.last()).activeCropData shouldBe null
        }
    }

    test("maintenance lookup separates matching local coordinates across chunks and worlds") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("farm")
            val otherWorld = paper.server.addSimpleWorld("other_farm")
            world.getChunkAt(0, 0).load()
            world.getChunkAt(1, 0).load()
            otherWorld.getChunkAt(0, 0).load()
            val soils = listOf(
                world.getBlockAt(1, 64, 2),
                world.getBlockAt(17, 64, 2),
                otherWorld.getBlockAt(1, 64, 2),
            ).onEach { it.type = Material.FARMLAND }
            val ledger = FarmBlockLedger(paper.createSimplePlugin("FarmBlockLedgerWorldLookupTest"))
            val expected = soils.zip(listOf("first", "next_chunk", "other_world")).associate { (soil, zone) ->
                ledger.capture(soil, zone)
                FarmPlotPosition(soil.world.name, soil.x, soil.y, soil.z) to zone
            }
            val uncaptured = world.getBlockAt(2, 64, 2).apply { type = Material.FARMLAND }

            val records = ledger.records(soils + uncaptured)
            records.keys shouldBe expected.keys
            expected.forEach { (position, zone) ->
                records.getValue(position).zoneId shouldBe zone
            }
            ledger.records(emptyList()) shouldBe emptyMap()
            val reversed = ledger.records(soils.reversed())
            expected.forEach { (position, zone) ->
                reversed.getValue(position).zoneId shouldBe zone
                reversed.getValue(position) shouldBeSameInstanceAs records.getValue(position)
            }
        }
    }
})
