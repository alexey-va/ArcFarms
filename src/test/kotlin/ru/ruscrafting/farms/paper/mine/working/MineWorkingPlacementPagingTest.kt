package ru.ruscrafting.farms.paper.mine.working

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MineWorkingPlacementPagingTest : FunSpec({
    test("bounded retries eventually consider a valid entrance beyond the first page") {
        val anchors = (0 until 1_300).toList()
        val attempts = (0..2).map { MineWorkingPlacementService.page(anchors, it, 512) }
        attempts.all { it.size == 512 } shouldBe true
        (1_200 in attempts.first()) shouldBe false
        (1_200 in attempts.last()) shouldBe true
        attempts.flatten().toSet() shouldBe anchors.toSet()
    }

    test("small floor inventories are visited once per attempt without duplicated anchors") {
        val anchors = (0..12).toList()
        MineWorkingPlacementService.page(anchors, 7, 512) shouldBe anchors
        MineWorkingPlacementService.page(emptyList<Int>(), 0, 512) shouldBe emptyList()
    }
})
