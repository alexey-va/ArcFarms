package ru.ruscrafting.farms.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.PendingMineBlock
import java.nio.file.Files
import java.util.concurrent.CompletionException

class MineBlockJournalTest : FunSpec({
    test("prepared mine recovery survives process restart and can be retired") {
        val root = Files.createTempDirectory("arcfarms-journal-test")
        val record = record("first", 10)
        MineBlockJournal(root).use { journal ->
            journal.prepare(record).join()
            journal.records() shouldContainExactly listOf(record)
            journal.containsPosition(record.positionKey) shouldBe true
        }

        MineBlockJournal(root).use { journal ->
            journal.records() shouldContainExactly listOf(record)
            journal.remove(record.id).join()
        }
        MineBlockJournal(root).use { journal -> journal.records() shouldBe emptyList() }
    }

    test("journal rejects two pending replacements for the same block") {
        val root = Files.createTempDirectory("arcfarms-journal-duplicate-test")
        MineBlockJournal(root).use { journal ->
            journal.prepare(record("first", 10)).join()
            shouldThrow<IllegalArgumentException> { journal.prepare(record("second", 10)) }
        }
    }

    test("corrupt journal fails closed") {
        val root = Files.createTempDirectory("arcfarms-journal-corrupt-test")
        val data = root.resolve("data")
        Files.createDirectories(data)
        Files.writeString(data.resolve("mine-blocks.json"), "{not-json")

        shouldThrow<RuntimeException> { MineBlockJournal(root) }
    }
}) {
    companion object {
        private fun record(id: String, x: Int) = PendingMineBlock(
            id = id,
            zoneId = "test_mine",
            world = "world",
            x = x,
            y = 64,
            z = 20,
            originalMaterial = "STONE",
            temporaryMaterial = "COBBLESTONE",
            nextMaterial = "IRON_ORE",
            restoreAt = 1_000,
        )
    }
}
