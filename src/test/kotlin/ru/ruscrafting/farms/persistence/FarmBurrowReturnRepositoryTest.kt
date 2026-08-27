package ru.ruscrafting.farms.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import ru.arc.persistence.DurableAcknowledgementOutcome
import java.nio.file.Files
import java.util.UUID

class FarmBurrowReturnRepositoryTest : FunSpec({
    test("safe surface return survives a repository reopen and exact acknowledgement") {
        val root = Files.createTempDirectory("arcfarms-burrow-return")
        val expected = FarmBurrowReturn(
            playerId = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
            zoneId = "communal_farm",
            sequence = 23,
            world = "sp11",
            x = 203.5,
            y = 51.0,
            z = 455.5,
            yaw = 90f,
            pitch = 0f,
            enteredAt = 12_345,
        )

        FarmBurrowReturnRepository(root).commit(expected) shouldBe expected
        val reopened = FarmBurrowReturnRepository(root)
        reopened.load(expected.playerId) shouldBe expected
        reopened.acknowledge(expected) shouldBe DurableAcknowledgementOutcome.ACKNOWLEDGED
        reopened.load(expected.playerId) shouldBe null
        reopened.acknowledge(expected) shouldBe DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED
    }

    test("record identity cannot be replayed under another player id") {
        val root = Files.createTempDirectory("arcfarms-burrow-identity")
        val owner = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
        val victim = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210")
        val record = FarmBurrowReturn(owner, "communal_farm", 3, "sp11", 1.5, 64.0, 2.5, 0f, 0f, 10)
        val repository = FarmBurrowReturnRepository(root)
        repository.commit(record)
        val directory = root.resolve("data/recovery/farm-burrow-returns")
        Files.move(directory.resolve("$owner.json"), directory.resolve("$victim.json"))

        shouldThrow<IllegalArgumentException> { repository.load(victim) }
    }
})
