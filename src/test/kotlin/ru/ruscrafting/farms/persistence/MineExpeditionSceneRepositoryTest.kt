package ru.ruscrafting.farms.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlacement
import java.nio.file.Files

class MineExpeditionSceneRepositoryTest : FunSpec({
    test("receipt round trip preserves nonce, journal identity and lifecycle markers") {
        val root = Files.createTempDirectory("arcfarms-expedition-receipt")
        val receipt = MineExpeditionSceneReceipt(
            zoneId = "old_shafts",
            sequence = 12,
            objectiveNonce = 3,
            journalSequence = 19,
            kind = MineExpeditionKind.DRILLING_ARK,
            placement = MineExpeditionPlacement("rc_arcfarms_expeditions", 128, 0, -256, 73L),
            surfaceWorld = "sp11",
            surfaceX = 10.5,
            surfaceY = 49.0,
            surfaceZ = 20.5,
        )
        MineExpeditionSceneRepository(root).use { repository ->
            repository.commit(receipt)
            repository.markCompleted("old_shafts", 12, 3, 1_000L)
            repository.markRestoring("old_shafts", 12, 3)
        }
        MineExpeditionSceneRepository(root).use { repository ->
            val restored = repository.find("old_shafts", 12, 3)!!
            restored.journalSequence shouldBe 19
            restored.completedAt shouldBe 1_000L
            restored.restoring shouldBe true
            repository.nextJournalSequence() shouldBe 20
            repository.remove("old_shafts", 12, 3)
            repository.records() shouldBe emptyList()
            repository.nextJournalSequence() shouldBe 20
        }
    }

    test("conflicting journal identities and invalid world names are rejected") {
        val root = Files.createTempDirectory("arcfarms-expedition-receipt-invalid")
        val placement = MineExpeditionPlacement("rc_arcfarms_expeditions", 0, 0, 0, 1L)
        val first = MineExpeditionSceneReceipt("zone", 1, 1, 7, MineExpeditionKind.LAST_DESCENT, placement, "sp11", 0.0, 64.0, 0.0)
        val conflicting = first.copy(sequence = 2)
        MineExpeditionSceneRepository(root).use { repository ->
            repository.commit(first)
            shouldThrow<IllegalArgumentException> { repository.commit(conflicting) }
        }
        shouldThrow<IllegalArgumentException> {
            MineExpeditionPlacement("rc/arcfarms", 0, 0, 0, 1L)
        }
    }
})
