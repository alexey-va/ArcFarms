package ru.ruscrafting.farms.persistence

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.lumber.*
import java.nio.file.Files
import java.util.UUID

class LumberIncidentPersistenceTest : FunSpec({
    val actor = UUID(0, 7)
    val positions = lumberSmolderPositions()

    test("the exact 0.46.3 saved board round defaults all optional incident fields without changing its progress") {
        val root = Files.createTempDirectory("lumber-0463-incidents")
        Files.createDirectories(root.resolve("data"))
        requireNotNull(javaClass.classLoader.getResourceAsStream("lumber-state-0.46.3.json")).use {
            Files.copy(it, root.resolve("data/state.json"))
        }
        ArcFarmsStateRepository(root).use { repo ->
            val state = requireNotNull(repo.load().lumbermills).getValue(LumberEngine.ZONE_ID)
            state.roundId shouldBe 7L
            state.completedRounds shouldBe 4L
            state.sawPasses shouldBe 2
            state.boardCartLoaded shouldBe true
            state.incident shouldBe null
            state.incidentGeneration shouldBe 0L
            state.incidentHandledRoundId shouldBe 0L
        }
    }

    test("incident checkpoints and an active crane round trip and restart preserves completed work") {
        var jam = LumberIncidentEngine.startCrank(LumberIncidentEngine.start(lumberIncidentBoundary(), LumberIncidentType.FEED_JAM), actor)
        jam = LumberIncidentEngine.completeTurn(jam, actor, jam.incidentGeneration)
        var smolder = LumberIncidentEngine.start(lumberIncidentBoundary(passes = 2), LumberIncidentType.SAWDUST_SMOLDER, positions)
        smolder = LumberIncidentEngine.extinguish(smolder, "pile_1", actor, smolder.incidentGeneration)
        val crane = lumberIncidentBoundary(passes = 2).copy(phase = LumberPhase.CRANE_TRANSFER)
        for (snapshot in listOf(jam, smolder, crane)) {
            val root = Files.createTempDirectory("lumber-incident-roundtrip")
            val expected = ArcFarmsState(lumbermills = mapOf(LumberEngine.ZONE_ID to snapshot))
            ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
            ArcFarmsStateRepository(root).use { repo ->
                repo.load() shouldBe expected
                val restored = LumberEngine.recover(requireNotNull(repo.load().lumbermills).getValue(LumberEngine.ZONE_ID),
                    snapshot.cart ?: LumberPose(positions.first().world, 193.5, 73.0, 185.5), 99_000)
                restored.incident?.crankOperator shouldBe null
                restored.incident?.crankTurns shouldBe snapshot.incident?.crankTurns
                restored.incident?.hotspots shouldBe snapshot.incident?.hotspots
                restored.incidentGeneration shouldBe snapshot.incidentGeneration
                restored.incidentHandledRoundId shouldBe snapshot.incidentHandledRoundId
                restored.tree shouldBe snapshot.tree
                restored.sawPasses shouldBe snapshot.sawPasses
                restored.completedRounds shouldBe snapshot.completedRounds
                if (snapshot.phase == LumberPhase.CRANE_TRANSFER) {
                    restored.phase shouldBe LumberPhase.CRANE_TRANSFER
                    restored.phaseStartedAt shouldBe 99_000L
                    restored.cart shouldBe null
                }
            }
        }
    }

    test("the real codec rejects unknown events unbounded targets stale generations and unsafe baseline phases") {
        val current = LumberIncidentEngine.start(lumberIncidentBoundary(passes = 2), LumberIncidentType.SAWDUST_SMOLDER, positions)
        val cases = listOf<(JsonObject) -> Unit>(
            { it.getAsJsonObject("incident").addProperty("type", "UNKNOWN") },
            { it.getAsJsonObject("incident").addProperty("crankTurns", 99) },
            { it.getAsJsonObject("incident").addProperty("crankTurns", Double.NaN) },
            { it.addProperty("incidentGeneration", current.incidentGeneration + 1) },
            { it.addProperty("incidentHandledRoundId", current.roundId + 1) },
            { it.addProperty("phase", "HAULING") },
            { state ->
                val targets = state.getAsJsonObject("incident").getAsJsonObject("hotspots").getAsJsonArray("targets")
                targets.add(targets[0].deepCopy())
            },
            { state ->
                val targets = state.getAsJsonObject("incident").getAsJsonObject("hotspots").getAsJsonArray("targets")
                targets[1].asJsonObject.addProperty("id", "pile_0")
            },
            { state ->
                val targets = state.getAsJsonObject("incident").getAsJsonObject("hotspots").getAsJsonArray("targets")
                targets[0].asJsonObject.getAsJsonObject("position").addProperty("world", "other")
            },
        )
        for (mutate in cases) {
            val root = Files.createTempDirectory("lumber-incident-invalid")
            val document = Gson().toJsonTree(ArcFarmsState(lumbermills = mapOf(LumberEngine.ZONE_ID to current))).asJsonObject
            mutate(document.getAsJsonObject("lumbermills").getAsJsonObject(LumberEngine.ZONE_ID))
            Files.createDirectories(root.resolve("data"))
            Files.writeString(root.resolve("data/state.json"), document.toString())
            ArcFarmsStateRepository(root).use { repo -> shouldThrowAny { repo.load() } }
        }
    }
})
