package ru.ruscrafting.farms.paper

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmBlockLedgerRecordTest : FunSpec({
    val gson = Gson()

    test("legacy farm block records remain transient when the index flag is absent") {
        val legacy = """{"zoneId":"farm","x":1,"y":64,"z":2,"originalSoilData":"minecraft:farmland[moisture=7]","originalCropData":null,"activeCropData":null}"""

        gson.fromJson(legacy, ManagedFarmBlockRecord::class.java).indexed shouldBe false
    }

    test("persistent index flag survives the chunk payload round trip") {
        val indexed = ManagedFarmBlockRecord(
            zoneId = "farm",
            x = 1,
            y = 64,
            z = 2,
            originalSoilData = "minecraft:farmland[moisture=7]",
            originalCropData = "minecraft:wheat[age=7]",
            activeCropData = "minecraft:wheat[age=7]",
            indexed = true,
        )

        gson.fromJson(gson.toJson(indexed), ManagedFarmBlockRecord::class.java) shouldBe indexed
    }
})
