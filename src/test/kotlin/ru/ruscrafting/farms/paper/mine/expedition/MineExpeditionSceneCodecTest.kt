package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneRecord

class MineExpeditionSceneCodecTest : FunSpec({
    fun record(x: Int = -1, y: Int = -63, z: Int = 15) = WorksitePreparedSceneRecord(
        "expeditions", "old_shafts", 42, 3, x, y, z,
        "minecraft:oak_log[axis=z]", "minecraft:air", "NONE", 120_000)
    fun encode(records: List<WorksitePreparedSceneRecord>) = MineExpeditionSceneCodec.encode(records, "expeditions", -1, 0, -64, 320)

    test("palette round trip preserves negative positions, BlockData and multiple scene identities") {
        val records = listOf(record(), record(-16, 319, 0).copy(sequence = 43, sceneId = 5,
            originalData = "minecraft:water[level=7]", activeData = "minecraft:polished_deepslate"))
        MineExpeditionSceneCodec.decode(encode(records), "expeditions", -1, 0, -64, 320) shouldBe records
    }
    test("invalid coordinates and duplicate ownership fail before encoding") {
        shouldThrowAny { encode(listOf(record(x = 0))) }
        shouldThrowAny { encode(listOf(record(y = 320))) }
        shouldThrowAny { encode(listOf(record(), record().copy(sequence = 99))) }
    }
    test("truncated and trailing journal data cannot produce a partial valid scene") {
        val bytes = encode(listOf(record()))
        shouldThrowAny { MineExpeditionSceneCodec.decode(bytes.dropLast(1).toByteArray(), "expeditions", -1, 0, -64, 320) }
        shouldThrowAny { MineExpeditionSceneCodec.decode(bytes + byteArrayOf(0), "expeditions", -1, 0, -64, 320) }
    }
})
