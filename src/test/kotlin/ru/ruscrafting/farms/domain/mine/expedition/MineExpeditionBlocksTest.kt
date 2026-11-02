package ru.ruscrafting.farms.domain.mine.expedition

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MineExpeditionBlocksTest : FunSpec({
    test("frozen recipe preserves sparse cells, negative bounds, insertion order and map equality") {
        val bounds = ExpeditionBounds(ExpeditionPoint(-2, -3, -4), ExpeditionPoint(2, 3, 4))
        val source = linkedMapOf(
            ExpeditionPoint(2, 3, 4) to "minecraft:light[level=14]",
            ExpeditionPoint(-2, -3, -4) to "minecraft:stone",
            ExpeditionPoint(0, -1, 3) to "minecraft:air",
            ExpeditionPoint(-1, 3, -4) to "minecraft:oak_log[axis=x]",
        )
        val frozen = MineExpeditionBlocks(bounds, source)
        frozen shouldBe source
        source shouldBe frozen
        frozen.entries.toList() shouldBe source.entries.toList()
        frozen.keys.toList() shouldBe source.keys.toList()
        frozen.values.toList() shouldBe source.values.toList()
        frozen.size shouldBe 4
        for (x in -3..3) for (y in -4..4) for (z in -5..5) {
            val point = ExpeditionPoint(x, y, z)
            frozen[point] shouldBe source[point]
            frozen.containsKey(point) shouldBe source.containsKey(point)
        }
        source.clear()
        frozen.size shouldBe 4
        frozen[ExpeditionPoint(-2, -3, -4)] shouldBe "minecraft:stone"
        frozen[ExpeditionPoint(Int.MAX_VALUE, 0, Int.MIN_VALUE)] shouldBe null
    }

    test("empty recipes and exhausted iterators follow the map contract") {
        val bounds = ExpeditionBounds(ExpeditionPoint(0, 0, 0), ExpeditionPoint(0, 0, 0))
        val frozen = MineExpeditionBlocks(bounds, emptyMap())
        frozen.isEmpty() shouldBe true
        frozen[ExpeditionPoint(0, 0, 0)] shouldBe null
        frozen.entries.iterator().hasNext() shouldBe false
        shouldThrow<NoSuchElementException> { frozen.entries.iterator().next() }
    }
})
