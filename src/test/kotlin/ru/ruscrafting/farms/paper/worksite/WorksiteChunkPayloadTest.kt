package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

class WorksiteChunkPayloadTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("farm and mine payloads round trip beyond the NBT string limit and replace legacy strings") {
        val world = paper.server.addSimpleWorld("world")
        val plugin = paper.createSimplePlugin("WorksitePayloadTest")
        val container = world.getChunkAt(0, 0).persistentDataContainer
        val key = NamespacedKey(plugin, "large_payload")
        container.set(key, PersistentDataType.STRING, "legacy")
        WorksiteChunkPayload.read(container, key)?.toString(Charsets.UTF_8) shouldBe "legacy"
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        WorksiteChunkPayload.write(container, key, bytes)
        container.has(key, PersistentDataType.STRING) shouldBe false
        WorksiteChunkPayload.read(container, key)?.contentEquals(bytes) shouldBe true
    }
    test("farm ledger restores a dense chunk from the shared byte payload") {
        val world = paper.server.addSimpleWorld("farm")
        val plugin = paper.createSimplePlugin("DenseFarmPayloadTest")
        val soils = buildList {
            for (y in listOf(64, 66, 68, 70)) for (x in 0..15) for (z in 0..15) {
                val soil = world.getBlockAt(x, y, z).also { it.type = org.bukkit.Material.FARMLAND }
                soil.getRelative(org.bukkit.block.BlockFace.UP).type = org.bukkit.Material.WHEAT
                add(soil)
            }
        }
        ru.ruscrafting.farms.paper.FarmBlockLedger(plugin).captureAll(soils, "farm")
        val key = NamespacedKey(plugin, "farm_managed_blocks_v1")
        val payload = requireNotNull(WorksiteChunkPayload.read(soils.first().chunk.persistentDataContainer, key))
        (payload.size > 65_535) shouldBe true
        val rebuilt = ru.ruscrafting.farms.paper.FarmBlockLedger(plugin)
        rebuilt.blockRecords(soils.first().chunk).size shouldBe soils.size
    }

})
