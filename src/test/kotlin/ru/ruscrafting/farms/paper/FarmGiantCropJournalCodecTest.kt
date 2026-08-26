package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

class FarmGiantCropJournalCodecTest : FunSpec({
    val record = FarmGiantCropJournalRecord(
        world = "world",
        zoneId = "farm",
        sequence = 9L,
        x = 17,
        y = 64,
        z = -17,
        originalData = "minecraft:wheat[age=7]",
        incidentData = "minecraft:hay_block[axis=y]",
        broken = false,
    )

    test("journal round trips exact recovery state") {
        val encoded = FarmGiantCropJournalCodec.encode(listOf(record), "world", 1, -2, -64, 320)

        FarmGiantCropJournalCodec.decode(encoded, "world", 1, -2, -64, 320) shouldBe listOf(record)
    }

    test("journal rejects trailing bytes") {
        val encoded = FarmGiantCropJournalCodec.encode(listOf(record), "world", 1, -2, -64, 320)

        shouldThrow<IllegalArgumentException> {
            FarmGiantCropJournalCodec.decode(encoded + byteArrayOf(1), "world", 1, -2, -64, 320)
        }
    }

    test("journal rejects a record assigned to another chunk") {
        shouldThrow<IllegalArgumentException> {
            FarmGiantCropJournalCodec.encode(listOf(record), "world", 2, -2, -64, 320)
        }
    }

    test("journal rejects an invalid zone identity") {
        shouldThrow<IllegalArgumentException> {
            FarmGiantCropJournalCodec.encode(listOf(record.copy(zoneId = "../farm")), "world", 1, -2, -64, 320)
        }
    }

    test("journal rejects oversized block data before persistence") {
        shouldThrow<IllegalArgumentException> {
            FarmGiantCropJournalCodec.encode(
                listOf(record.copy(originalData = "x".repeat(513))),
                "world",
                1,
                -2,
                -64,
                320,
            )
        }
    }

    test("journal rejects duplicate ownership of one block") {
        shouldThrow<IllegalArgumentException> {
            FarmGiantCropJournalCodec.encode(listOf(record, record.copy(zoneId = "other")), "world", 1, -2, -64, 320)
        }
    }

    test("journal rejects unusable block data before it can strand recovery") {
        shouldThrow<IllegalArgumentException> {
            FarmGiantCropJournalCodec.encode(listOf(record.copy(originalData = "")), "world", 1, -2, -64, 320)
        }
    }
})
