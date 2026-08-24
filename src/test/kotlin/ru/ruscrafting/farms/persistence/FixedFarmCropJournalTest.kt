package ru.ruscrafting.farms.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.PendingFixedFarmCrop
import java.nio.file.Files

class FixedFarmCropJournalTest : FunSpec({
    test("pending fruit recovery survives process restart and can be retired") {
        val root = Files.createTempDirectory("arcfarms-fixed-crop-journal-test")
        val record = record(10)
        FixedFarmCropJournal(root).use { journal ->
            journal.prepare(record).join()
            journal.records() shouldContainExactly listOf(record)
            journal.contains(record.positionKey) shouldBe true
        }

        FixedFarmCropJournal(root).use { journal ->
            journal.record(record.positionKey) shouldBe record
            journal.remove(record.positionKey).join()
        }
        FixedFarmCropJournal(root).use { journal -> journal.records() shouldBe emptyList() }
    }

    test("journal rejects two pending harvests for one fruit block") {
        val root = Files.createTempDirectory("arcfarms-fixed-crop-duplicate-test")
        FixedFarmCropJournal(root).use { journal ->
            journal.prepare(record(10)).join()
            shouldThrow<IllegalArgumentException> { journal.prepare(record(10)) }
        }
    }

    test("corrupt fixed crop journal fails closed") {
        val root = Files.createTempDirectory("arcfarms-fixed-crop-corrupt-test")
        val data = root.resolve("data")
        Files.createDirectories(data)
        Files.writeString(data.resolve("fixed-farm-crops.json"), "{not-json")

        shouldThrow<RuntimeException> { FixedFarmCropJournal(root) }
    }
}) {
    companion object {
        private fun record(x: Int) = PendingFixedFarmCrop(
            zoneId = "communal_farm",
            world = "world",
            x = x,
            y = 64,
            z = 20,
            originalBlockData = "minecraft:melon",
            restoreAt = 1_000L,
        )
    }
}
