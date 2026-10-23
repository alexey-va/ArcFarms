package ru.ruscrafting.farms.persistence

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.domain.mine.crystal.*
import ru.ruscrafting.farms.domain.worksite.*
import java.nio.file.Files

class MineCrystalStateRepositoryTest : FunSpec({
    fun state(): ArcFarmsState = ArcFarmsState(mines = mapOf("shafts" to MineShiftState(
        engineVersion = 2, phase = MinePhase.INCIDENT, sequence = 3, orderId = "ore_run", resumePhase = MinePhase.MINING,
        objective = WorksiteObjectiveState(WorksiteObjectiveKey("shafts", "incident_crystal_resonance", 3), 4,
            (0..3).map { ObjectiveTargetState("crystal_$it", WorksitePosition("world", it, 64, 3),
                ObjectiveTargetRole("crystal_node"), it.toLong()) }),
        incident = MineIncidentState(MineIncidentType.CRYSTAL_RESONANCE, 4, 0,
            crystal = MineCrystalState(listOf(MineCrystalCut(WorksitePosition("world", 15, 66, 0), "STONE")), -1, 0)),
    )))
    fun load(transform: (com.google.gson.JsonObject) -> Unit): ArcFarmsState {
        val root = Files.createTempDirectory("mine-crystal-recovery")
        val json = Gson().toJsonTree(state()).asJsonObject
        transform(json.getAsJsonObject("mines").getAsJsonObject("shafts"))
        Files.createDirectories(root.resolve("data"))
        Files.writeString(root.resolve("data/state.json"), json.toString())
        return ArcFarmsStateRepository(root).use(ArcFarmsStateRepository::load)
    }

    test("legacy crystal incidents without beam state remain recoverable") {
        load { it.getAsJsonObject("incident").remove("crystal") }.mines.getValue("shafts").incident?.crystal shouldBe null
    }

    test("recovery rejects a beam before the four targets are charged") {
        shouldThrow<IllegalArgumentException> {
            load { mine -> mine.getAsJsonObject("incident").getAsJsonObject("crystal").apply {
                addProperty("phase", "DRILLING")
                addProperty("operator", "00000000-0000-0000-0000-000000000001")
            } }
        }
    }

    test("recovery rejects an unrelated incident carrying crystal state") {
        shouldThrow<IllegalArgumentException> {
            load { it.getAsJsonObject("incident").addProperty("type", "GAS_LEAK") }
        }
    }

    test("recovery rechecks constructors bypassed by Gson for targets and wall cuts") {
        shouldThrow<IllegalArgumentException> {
            load { it.getAsJsonObject("objective").getAsJsonArray("targets")[1].asJsonObject
                .addProperty("id", "crystal_0") }
        }
        shouldThrow<IllegalArgumentException> {
            load { it.getAsJsonObject("incident").getAsJsonObject("crystal").getAsJsonArray("cuts")[0]
                .asJsonObject.addProperty("material", "CHEST") }
        }
    }
})
