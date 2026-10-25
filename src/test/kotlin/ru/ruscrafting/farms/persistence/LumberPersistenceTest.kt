package ru.ruscrafting.farms.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.lumber.LumberPhase
import ru.ruscrafting.farms.domain.lumber.LumberState
import java.nio.file.Files

class LumberPersistenceTest : FunSpec({
    test("retired schema1 lumber shape is removed while exact farm stats and resolved rewards survive") {
        val root = Files.createTempDirectory("lumber-retired")
        val player = java.util.UUID(0, 41)
        val expected = ArcFarmsState(
            farms = mapOf("farm" to ru.ruscrafting.farms.domain.FarmShiftState()),
            stats = mapOf(player to ru.ruscrafting.farms.domain.PlayerActivityStats(
                contributions = mapOf(ru.ruscrafting.farms.domain.ActivityKind.FARM to 123L),
                completedShifts = mapOf(ru.ruscrafting.farms.domain.ActivityKind.FARM to 4))),
            pendingFarmRewards = listOf(ru.ruscrafting.farms.domain.PendingFarmReward(
                id = "farm:7:$player", zoneId = "farm", sequence = 7, playerId = player,
                contribution = 3, experience = 75, moneyCents = 50_000,
                items = listOf(ru.ruscrafting.farms.domain.FarmRewardItem("BREAD", 8)))),
        )
        val gson = com.google.gson.Gson()
        val document = gson.toJsonTree(expected).asJsonObject
        document.add("lumbermills", com.google.gson.JsonParser.parseString(
            """{"old_forest":{"engineVersion":2,"phase":"SAWING","sequence":9,"felled":12,"sawCuts":1}}"""))
        Files.createDirectories(root.resolve("data"))
        Files.writeString(root.resolve("data/state.json"), document.toString())
        ArcFarmsStateRepository(root).use { repo ->
            val migrated = repo.load()
            migrated shouldBe expected
            val normalized = gson.toJsonTree(migrated).asJsonObject
            listOf("farms", "stats", "pendingFarmRewards", "claimedFarmRewardSequences", "worksiteEnterprise").forEach { key ->
                normalized.get(key) shouldBe document.get(key)
            }
        }
    }

    test("unknown and malformed new lumber shape cannot be mistaken for retired runtime data") {
        val root = Files.createTempDirectory("lumber-unknown")
        val document = com.google.gson.Gson().toJsonTree(ArcFarmsState()).asJsonObject
        document.add("lumbermills", com.google.gson.JsonParser.parseString("""{"cedar_sawmill":{"roundId":1,"phase":"SAWING"}}"""))
        Files.createDirectories(root.resolve("data"))
        Files.writeString(root.resolve("data/state.json"), document.toString())
        ArcFarmsStateRepository(root).use { repo -> shouldThrow<IllegalArgumentException> { repo.load() } }
    }

    test("exact pre-lumber schema1 snapshot normalizes additive nullable lumber state and preserves statistics") {
        val root = Files.createTempDirectory("lumber-legacy")
        Files.createDirectories(root.resolve("data"))
        Files.writeString(root.resolve("data/state.json"), """{"schemaVersion":1,"farms":{},"mines":{},"stats":{},"pendingFarmRewards":[],"claimedFarmRewardSequences":{}}""")
        ArcFarmsStateRepository(root).use { repo ->
            val migrated = repo.load()
            migrated.lumbermills shouldBe emptyMap()
            migrated.schemaVersion shouldBe 1
            repo.saveBlocking(migrated)
        }
        ArcFarmsStateRepository(root).use { it.load().lumbermills shouldBe emptyMap() }
    }

    test("local-only completed beta rounds round trip without any reward or global contribution") {
        val root = Files.createTempDirectory("lumber-state")
        val expected = ArcFarmsState(lumbermills = mapOf("cedar_sawmill" to LumberState(roundId = 4, completedRounds = 3)))
        ArcFarmsStateRepository(root).use { repo -> repo.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { repo ->
            repo.load() shouldBe expected
            repo.load().stats shouldBe emptyMap()
            repo.load().pendingFarmRewards shouldBe emptyList()
        }
    }

    test("malformed active lumber snapshot is rejected at the real storage boundary") {
        ArcFarmsStateRepository(Files.createTempDirectory("lumber-invalid")).use { repo ->
            val failure = shouldThrow<java.util.concurrent.ExecutionException> {
                repo.saveBlocking(ArcFarmsState(lumbermills = mapOf("cedar_sawmill" to LumberState(phase = LumberPhase.SAWING))))
            }
            (failure.cause is IllegalArgumentException) shouldBe true
        }
    }
})
