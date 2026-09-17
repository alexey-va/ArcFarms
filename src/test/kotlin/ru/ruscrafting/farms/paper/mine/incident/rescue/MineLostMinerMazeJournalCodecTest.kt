package ru.ruscrafting.farms.paper.mine.incident.rescue

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MineLostMinerMazeJournalCodecTest : FunSpec({
    val record = MineLostMinerMazeJournalRecord(
        world = "world",
        zoneId = "old_shafts",
        sequence = 9,
        x = 12,
        y = 63,
        z = 12,
        originalData = "minecraft:stone",
        mazeData = "minecraft:air",
        marker = MineLostMinerMazeMarker.START,
        totalRecords = 2,
    )

    test("journal round trips the exact original BlockData and marker") {
        val records = listOf(record, record.copy(x = 13, marker = MineLostMinerMazeMarker.TARGET))
        val encoded = MineLostMinerMazeJournalCodec.encode(records, "world", 0, 0, -64, 320)

        MineLostMinerMazeJournalCodec.decode(encoded, "world", 0, 0, -64, 320) shouldBe records
    }

    test("journal rejects duplicate positions and trailing bytes") {
        shouldThrow<IllegalArgumentException> {
            MineLostMinerMazeJournalCodec.encode(listOf(record, record), "world", 0, 0, -64, 320)
        }
        val encoded = MineLostMinerMazeJournalCodec.encode(listOf(record), "world", 0, 0, -64, 320)
        shouldThrow<IllegalArgumentException> {
            MineLostMinerMazeJournalCodec.decode(encoded + byteArrayOf(1), "world", 0, 0, -64, 320)
        }
    }
})
