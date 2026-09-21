package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MineFactoryFloorMigrationTest : FunSpec({
    val former = "minecraft:polished_andesite"
    val desired = "minecraft:polished_deepslate"

    test("default former floor projects and journal-first restart can converge") {
        MineFactoryFloorMigration.decide(former, former, former, desired) shouldBe MineFactoryFloorMigration.Decision.PROJECT
        // If the journal commit won before a process restart, an old live block is
        // still eligible for the same desired projection.
        MineFactoryFloorMigration.decide(desired, former, former, desired) shouldBe MineFactoryFloorMigration.Decision.PROJECT
        MineFactoryFloorMigration.decide(desired, desired, former, desired) shouldBe MineFactoryFloorMigration.Decision.COMPLETE
    }

    test("custom floor data is preserved") {
        MineFactoryFloorMigration.decide(former, "minecraft:gold_block", former, desired) shouldBe
            MineFactoryFloorMigration.Decision.PRESERVE
        MineFactoryFloorMigration.decide("minecraft:gold_block", former, former, desired) shouldBe
            MineFactoryFloorMigration.Decision.PRESERVE
    }

    test("readiness remains blocked while either repair queue is pending") {
        MineFactoryFloorMigration.repairPending(baselinePending = true, floorPending = false) shouldBe true
        MineFactoryFloorMigration.repairPending(baselinePending = false, floorPending = true) shouldBe true
        MineFactoryFloorMigration.repairPending(baselinePending = true, floorPending = true) shouldBe true
        MineFactoryFloorMigration.repairPending(baselinePending = false, floorPending = false) shouldBe false
    }
})
