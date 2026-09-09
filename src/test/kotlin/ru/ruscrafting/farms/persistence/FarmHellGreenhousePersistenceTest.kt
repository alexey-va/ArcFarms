package ru.ruscrafting.farms.persistence

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.FarmHellGreenhouseState
import ru.ruscrafting.farms.domain.FarmHellPepper
import ru.ruscrafting.farms.domain.FarmHellPlantationPlot
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ExecutionException

class FarmHellGreenhousePersistenceTest : FunSpec({
    val player = UUID(0, 901)
    val points = (0 until 4).map { FarmPointPosition("world", it + 0.5, 65.0, 0.5) }

    fun greenhouse() = FarmHellGreenhouseState(
        points = points,
        elapsedSeconds = 2,
        heat = 3,
        harvested = setOf(0),
        cooled = 0,
        carried = mapOf(player to FarmHellPepper(0, 8)),
    )

    fun farm(
        greenhouse: FarmHellGreenhouseState? = greenhouse(),
        phase: FarmPhase = FarmPhase.INCIDENT,
        type: FarmIncidentType? = FarmIncidentType.HELL_GREENHOUSE,
        progress: Int = greenhouse?.cooled ?: 0,
        required: Int = 2,
    ) = FarmShiftState(
        phase = phase,
        sequence = 1,
        orderId = "farm_order",
        incidentType = type,
        incidentCrop = if (type == FarmIncidentType.HELL_GREENHOUSE) "WHEAT" else null,
        incidentProgress = progress,
        incidentRequired = required,
        hellGreenhouse = greenhouse,
    )

    test("mid-carry greenhouse state survives an atomic round trip") {
        val root = Files.createTempDirectory("arcfarms-hell-greenhouse-roundtrip")
        val expected = ArcFarmsState(farms = mapOf("farm" to farm()))
        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("plantation plot timers survive an atomic restart round trip") {
        val root = Files.createTempDirectory("arcfarms-hell-plantation-roundtrip")
        val plantation = FarmHellGreenhouseState(
            points = points,
            layoutVersion = 2,
            plots = listOf(
                FarmHellPlantationPlot(heating = true, growthSeconds = 8),
                FarmHellPlantationPlot(growthSeconds = 8, coolingSeconds = 2),
                FarmHellPlantationPlot(), FarmHellPlantationPlot(heating = true, growthSeconds = 8, overheatSeconds = 3),
            ),
            cooled = 2,
        )
        val expected = ArcFarmsState(farms = mapOf("farm" to farm(greenhouse = plantation, progress = 2, required = 4)))
        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("four reusable beds preserve progress beyond their physical count") {
        val root = Files.createTempDirectory("arcfarms-plantation-repeat-quota")
        val plantation = FarmHellGreenhouseState(points = points, layoutVersion = 2,
            plots = List(4) { FarmHellPlantationPlot() }, cooled = 7)
        val expected = ArcFarmsState(farms = mapOf("farm" to farm(greenhouse = plantation, progress = 7).copy(incidentRequired = 16)))
        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }

    test("legacy farm state without greenhouse field loads as null") {
        val root = Files.createTempDirectory("arcfarms-hell-greenhouse-legacy")
        ArcFarmsStateRepository(root).use { it.saveBlocking(ArcFarmsState(farms = mapOf("farm" to farm()))) }
        val path = root.resolve("data/state.json")
        val json = JsonParser.parseString(Files.readString(path)).asJsonObject
        json.getAsJsonObject("farms").getAsJsonObject("farm").remove("hellGreenhouse")
        Files.writeString(path, Gson().toJson(json))
        ArcFarmsStateRepository(root).use { it.load().farms.getValue("farm").hellGreenhouse.shouldBeNull() }
    }

    test("invalid greenhouse indexes, duplicate held plants, phase and quota consistency are rejected") {
        val invalid = listOf(
            farm(greenhouse = greenhouse().copy(harvested = setOf(4))),
            farm(greenhouse = greenhouse().copy(carried = mapOf(player to FarmHellPepper(0, 8), UUID(0, 902) to FarmHellPepper(0, 9)))),
            farm(phase = FarmPhase.HARVESTING),
            farm(greenhouse = greenhouse().copy(harvested = emptySet(), cooled = 1), progress = 1),
        )
        invalid.forEach { candidate ->
            val root = Files.createTempDirectory("arcfarms-hell-greenhouse-invalid")
            ArcFarmsStateRepository(root).use { repository ->
                val failure = shouldThrow<ExecutionException> { repository.saveBlocking(ArcFarmsState(farms = mapOf("farm" to candidate))) }
                (failure.cause is IllegalArgumentException) shouldBe true
            }
        }
    }

    test("raw persisted invalid greenhouse state is rejected after JSON decoding") {
        val root = Files.createTempDirectory("arcfarms-hell-greenhouse-raw")
        ArcFarmsStateRepository(root).use { it.saveBlocking(ArcFarmsState(farms = mapOf("farm" to farm()))) }
        val path = root.resolve("data/state.json")
        val json = JsonParser.parseString(Files.readString(path)).asJsonObject
        json.getAsJsonObject("farms").getAsJsonObject("farm").getAsJsonObject("hellGreenhouse")
            .addProperty("elapsedSeconds", -1)
        Files.writeString(path, Gson().toJson(json))
        ArcFarmsStateRepository(root).use { repository -> shouldThrow<Exception> { repository.load() } }
    }

    test("raw persisted invalid plantation timer is rejected after JSON decoding") {
        val root = Files.createTempDirectory("arcfarms-hell-plantation-raw")
        val plantation = FarmHellGreenhouseState(points = points, layoutVersion = 2, plots = List(4) { FarmHellPlantationPlot() })
        ArcFarmsStateRepository(root).use { it.saveBlocking(ArcFarmsState(farms = mapOf("farm" to farm(greenhouse = plantation)))) }
        val path = root.resolve("data/state.json")
        val json = JsonParser.parseString(Files.readString(path)).asJsonObject
        json.getAsJsonObject("farms").getAsJsonObject("farm").getAsJsonObject("hellGreenhouse")
            .getAsJsonArray("plots").get(0).asJsonObject.addProperty("growthSeconds", 9)
        Files.writeString(path, Gson().toJson(json))
        ArcFarmsStateRepository(root).use { repository -> shouldThrow<Exception> { repository.load() } }
    }
})
