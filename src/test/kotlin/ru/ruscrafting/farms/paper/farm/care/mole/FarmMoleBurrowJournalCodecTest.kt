package ru.ruscrafting.farms.paper.farm.care.mole

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class FarmMoleBurrowJournalCodecTest : FunSpec({
    val record = FarmMoleBurrowJournalRecord(
        world = "sp11",
        zoneId = "communal_farm",
        sequence = 19,
        x = 207,
        y = 38,
        z = 463,
        originalData = "minecraft:stone",
        burrowData = "minecraft:air",
        marker = FarmMoleBurrowMarker.START,
        totalRecords = 2,
    )

    test("chunk journal round trips exact tunnel ownership") {
        val records = listOf(record, record.copy(x = 206, marker = FarmMoleBurrowMarker.LAIR))
        val encoded = FarmMoleBurrowJournalCodec.encode(records, "sp11", 12, 28, -64, 320)

        FarmMoleBurrowJournalCodec.decode(encoded, "sp11", 12, 28, -64, 320) shouldBe records
    }

    test("chunk journal rejects duplicate block ownership") {
        shouldThrow<IllegalArgumentException> {
            FarmMoleBurrowJournalCodec.encode(listOf(record, record.copy(marker = FarmMoleBurrowMarker.LAIR)), "sp11", 12, 28, -64, 320)
        }
    }

    test("chunk journal rejects records assigned to another chunk") {
        shouldThrow<IllegalArgumentException> {
            FarmMoleBurrowJournalCodec.encode(listOf(record), "sp11", 13, 28, -64, 320)
        }
    }

    test("chunk journal rejects trailing bytes") {
        val encoded = FarmMoleBurrowJournalCodec.encode(listOf(record), "sp11", 12, 28, -64, 320)
        shouldThrow<IllegalArgumentException> {
            FarmMoleBurrowJournalCodec.decode(encoded + byteArrayOf(1), "sp11", 12, 28, -64, 320)
        }
    }

    test("version one journals remain readable as burrow zero") {
        val encoded = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(1)
                output.writeInt(1)
                output.writeUTF(record.zoneId)
                output.writeLong(record.sequence)
                output.writeInt(record.x)
                output.writeInt(record.y)
                output.writeInt(record.z)
                output.writeUTF(record.originalData)
                output.writeUTF(record.burrowData)
                output.writeByte(record.marker.ordinal)
                output.writeInt(record.totalRecords)
            }
            bytes.toByteArray()
        }

        FarmMoleBurrowJournalCodec.decode(encoded, "sp11", 12, 28, -64, 320).single().burrowId shouldBe 0
    }
})
