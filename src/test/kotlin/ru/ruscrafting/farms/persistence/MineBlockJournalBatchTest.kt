package ru.ruscrafting.farms.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.PendingMineBlock
import kotlin.io.path.createTempDirectory

class MineBlockJournalBatchTest : FunSpec({
    test("a complete sixty-block incident footprint is persisted in one snapshot") {
        val root = createTempDirectory("mine-journal-batch")
        try {
            val records = (0 until 60).map { index ->
                PendingMineBlock(
                    id = "mine-incident:old_shafts:7:cave_in:$index",
                    zoneId = "old_shafts",
                    world = "world",
                    x = index % 10,
                    y = 64 + index / 20,
                    z = index / 10,
                    originalMaterial = "AIR",
                    temporaryMaterial = "COBBLESTONE",
                    nextMaterial = "AIR",
                    restoreAt = Long.MAX_VALUE,
                )
            }
            MineBlockJournal(root).use { journal ->
                journal.prepareAll(records).join()
                journal.pendingCount() shouldBe 60
            }
            MineBlockJournal(root).use { reopened ->
                reopened.records().map { it.id }.toSet() shouldBe records.map { it.id }.toSet()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
