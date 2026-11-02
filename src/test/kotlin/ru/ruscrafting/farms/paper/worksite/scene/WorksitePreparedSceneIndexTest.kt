package ru.ruscrafting.farms.paper.worksite.scene

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class WorksitePreparedSceneIndexTest : FunSpec({
    fun record(world: String, x: Int, y: Int, z: Int, sequence: Long = 1) =
        WorksitePreparedSceneRecord(world, "reserve", sequence, 1, x, y, z,
            "minecraft:stone", "minecraft:air", "NONE", 100)

    test("lookup preserves negative coordinates, chunk boundaries, worlds and the full signed height") {
        val records = listOf(
            record("mine", -1, -64, -1), record("mine", -17, -64, -17),
            record("mine", -16, 319, 16), record("mine", 0, 319, 0),
            record("other", -1, -64, -1), record("mine", -1, Int.MIN_VALUE, -1),
            record("mine", -1, Int.MAX_VALUE, -1), record("mine", Int.MIN_VALUE, 0, Int.MAX_VALUE),
        )
        val index = WorksitePreparedSceneIndex(records)
        index.size shouldBe records.size
        records.forEach { index[it.world, it.x, it.y, it.z] shouldBeSameInstanceAs it }
        index.contains("mine", -1, -64, -1) shouldBe true
        index.contains("mine", -1, -63, -1) shouldBe false
        index.contains("mine", 15, -64, 15) shouldBe false
        index.contains("missing", -1, -64, -1) shouldBe false
    }

    test("duplicate coordinates retain the former last-record lookup and unique position count") {
        val first = record("mine", -1, -64, -1)
        val other = record("mine", -2, 5, -1)
        val last = first.copy(zoneId = "another_journal", sequence = 9)
        val index = WorksitePreparedSceneIndex(listOf(first, other, last))
        index.size shouldBe 2
        index["mine", -1, -64, -1] shouldBeSameInstanceAs last
        index["mine", -2, 5, -1] shouldBeSameInstanceAs other
        WorksitePreparedSceneIndex(emptyList()).contains("mine", 0, 0, 0) shouldBe false
    }
})
