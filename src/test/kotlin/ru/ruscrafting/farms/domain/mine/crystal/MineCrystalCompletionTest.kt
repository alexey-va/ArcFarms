package ru.ruscrafting.farms.domain.mine.crystal

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.domain.worksite.*
import ru.ruscrafting.farms.persistence.ArcFarmsStateRepository
import java.nio.file.Files
import java.util.UUID

class MineCrystalCompletionTest : FunSpec({
    val operator = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val rules = MineRules(12, 2, 1, 0, miningQuota = 12, miningOnly = true)
    fun ready() = MineShiftState(engineVersion = 2, phase = MinePhase.INCIDENT, sequence = 3, orderId = "ore_run",
        resumePhase = MinePhase.MINING, mined = 8, cart = 8, minedByMaterial = mapOf("IRON" to 5, "GOLD" to 3),
        objective = WorksiteObjectiveState(WorksiteObjectiveKey("shafts", "incident_crystal_resonance", 3), 4,
            (0..3).map { ObjectiveTargetState("crystal_$it", WorksitePosition("world", it, 64, 3),
                ObjectiveTargetRole("crystal_node"), it.toLong(), ObjectiveTargetStatus.COMPLETED) }),
        incident = MineIncidentState(MineIncidentType.CRYSTAL_RESONANCE, 4, 4, objectiveNonce = 2,
            crystal = MineCrystalState((0..3).map { MineCrystalCut(WorksitePosition("world", 15, 66 + it / 2, it % 2), "STONE") },
                -1, 0, MineCrystalPhase.EXTRACTING, 5_000, operator)))

    test("four charged crystals keep a bounded quota result in the same restart-safe retirement snapshot") {
        val root = Files.createTempDirectory("mine-crystal-result")
        val initial = ready()
        ArcFarmsStateRepository(root).use { it.saveAsync(ArcFarmsState(mines = mapOf("shafts" to initial))).get() }
        val reloaded = ArcFarmsStateRepository(root).use { it.load().mines.getValue("shafts") }
        reloaded shouldBe initial
        val result = MineCrystalCompletion.finish(reloaded, rules, mapOf("IRON" to 6, "GOLD" to 6))
        result.accepted shouldBe true
        result.state.mined shouldBe 12
        result.state.minedByMaterial shouldBe mapOf("IRON" to 6, "GOLD" to 6)
        result.state.incident shouldBe null
        result.state.incidentCursor shouldBe 1
        result.state.phase shouldBe MinePhase.EXTRACTION
        ArcFarmsStateRepository(root).use { it.saveAsync(ArcFarmsState(mines = mapOf("shafts" to result.state))).get() }
        val finished = ArcFarmsStateRepository(root).use { it.load().mines.getValue("shafts") }
        MineCrystalCompletion.finish(finished, rules, mapOf("IRON" to 6, "GOLD" to 6)).accepted shouldBe false
        finished.mined shouldBe 12
    }

    test("a small bore cannot credit four resources and remaining quota cannot be overfilled") {
        val initial = ready().let { it.copy(incident = it.incident!!.copy(crystal = it.incident.crystal!!.copy(
            cuts = it.incident.crystal.cuts.take(2)))) }
        val result = MineCrystalCompletion.finish(initial, rules, mapOf("IRON" to 6, "GOLD" to 6))
        result.state.mined shouldBe 10
        result.contribution shouldBe 2
        val almost = ready().copy(mined = 11, cart = 11, minedByMaterial = mapOf("IRON" to 6, "GOLD" to 5))
        MineCrystalCompletion.finish(almost, rules, mapOf("IRON" to 6, "GOLD" to 6)).contribution shouldBe 1
    }

    test("incomplete targets and an unfinished drilling phase never produce the resource result") {
        val initial = ready()
        MineCrystalCompletion.finish(initial.copy(incident = initial.incident!!.copy(crystal = initial.incident.crystal!!.copy(
            phase = MineCrystalPhase.DRILLING))), rules, emptyMap()).accepted shouldBe false
        MineCrystalCompletion.finish(initial.copy(objective = initial.objective!!.copy(targets = initial.objective.targets.mapIndexed { i, target ->
            if (i == 3) target.copy(status = ObjectiveTargetStatus.AVAILABLE) else target
        })), rules, emptyMap()).accepted shouldBe false
    }

    test("saved crystal fields are validated after Gson bypasses the Kotlin constructors") {
        val gson = Gson()
        val valid = ready().incident!!.crystal!!
        val encoded = gson.toJsonTree(valid).asJsonObject
        val damaged = listOf(
            encoded.deepCopy().apply { addProperty("phase", "UNKNOWN_PHASE") },
            encoded.deepCopy().apply { add("cuts", com.google.gson.JsonNull.INSTANCE) },
            encoded.deepCopy().apply { getAsJsonArray("cuts").set(0, com.google.gson.JsonNull.INSTANCE) },
            encoded.deepCopy().apply { getAsJsonArray("cuts")[0].asJsonObject.add("position", com.google.gson.JsonNull.INSTANCE) },
            encoded.deepCopy().apply { getAsJsonArray("cuts")[0].asJsonObject.getAsJsonObject("position").addProperty("world", "") },
            encoded.deepCopy().apply { getAsJsonArray("cuts")[0].asJsonObject.getAsJsonObject("position").addProperty("z", Int.MIN_VALUE) },
        )
        damaged.forEach { document ->
            shouldThrow<IllegalArgumentException> { gson.fromJson(document, MineCrystalState::class.java).validate() }
        }
    }
})
