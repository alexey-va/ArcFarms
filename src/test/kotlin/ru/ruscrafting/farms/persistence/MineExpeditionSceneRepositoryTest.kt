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
            repository.commit(receipt).join()
            repository.markCompleted("old_shafts", 12, 3, 1_000L).join()
            repository.markRestoring("old_shafts", 12, 3).join()
        }
        MineExpeditionSceneRepository(root).use { repository ->
            repository.ready.join()
            val restored = repository.find("old_shafts", 12, 3)!!
            restored.journalSequence shouldBe 19
            restored.completedAt shouldBe 1_000L
            restored.restoring shouldBe true
            repository.nextJournalSequence() shouldBe 20
            repository.remove("old_shafts", 12, 3).join()
            repository.records() shouldBe emptyList()
            repository.nextJournalSequence() shouldBe 20
        }
    }

    test("a durable reserve claim preserves its block journal across restart and rebuild") {
        val root = Files.createTempDirectory("arcfarms-reserve-restart")
        val reserve = MineExpeditionSceneReceipt("reserve_dead_factory", 0, 29, 29,
            MineExpeditionKind.DEAD_FACTORY, MineExpeditionPlacement("rc_arcfarms_expeditions", 128, 0, 256, Long.MAX_VALUE - 71),
            "world", 0.5, 111.0, 0.5, reserved = true, journalZoneId = "reserve_dead_factory", journalSceneId = 29)
        val claimed = reserve.copy(zoneId = "old_shafts", sequence = 5, objectiveNonce = 100, reserved = false)
        MineExpeditionSceneRepository(root).use { repository -> repository.commit(reserve).join() }
        MineExpeditionSceneRepository(root).use { repository ->
            repository.ready.join()
            repository.records().single() shouldBe reserve
            repository.claim(reserve, claimed).join()
        }
        MineExpeditionSceneRepository(root).use { repository ->
            repository.ready.join()
            val restored = repository.records().single()
            restored shouldBe claimed
            restored.journalOwner shouldBe reserve.journalOwner
            restored.sceneId shouldBe 29
            restored.placement.seed shouldBe Long.MAX_VALUE - 71
            repository.markRestoring("old_shafts", 5, 100).join()
            repository.remove("old_shafts", 5, 100).join()
        }
        MineExpeditionSceneRepository(root).use { repository ->
            repository.ready.join()
            repository.records() shouldBe emptyList()
            repository.allocateJournalSequence() shouldBe 30
        }
    }

    test("failed durable writes never publish a claim and later writes still work") {
        val storage = DeferredExpeditionStorage()
        val repository = MineExpeditionSceneRepository(storage)
        val reserve = MineExpeditionSceneReceipt("reserve", 0, 1, 1, MineExpeditionKind.LAST_DESCENT,
            MineExpeditionPlacement("rc_arcfarms_expeditions", 0, 0, 0, 73), "world", 0.0, 64.0, 0.0,
            reserved = true, journalZoneId = "reserve", journalSceneId = 1)
        val commit = repository.commit(reserve)
        commit.isDone shouldBe false
        repository.records() shouldBe emptyList()
        storage.pending.complete(Unit)
        commit.join()
        val claimed = reserve.copy(zoneId = "old_shafts", sequence = 5, objectiveNonce = 7, reserved = false)
        val claim = repository.claim(reserve, claimed)
        repository.records().single() shouldBe reserve
        storage.pending.completeExceptionally(java.io.IOException("disk unavailable"))
        shouldThrow<java.util.concurrent.CompletionException> { claim.join() }
        repository.records().single() shouldBe reserve
        val retry = repository.claim(reserve, claimed)
        repository.close()
        storage.closed shouldBe false
        storage.pending.complete(Unit)
        retry.join()
        repository.records().single() shouldBe claimed
        storage.closed shouldBe true
    }

    test("built editable site survives release and repository reopen") {
        val root = Files.createTempDirectory("arcfarms-static-site")
        val reserve = MineExpeditionSceneReceipt("reserve",0,1,1,MineExpeditionKind.DEAD_FACTORY,
            MineExpeditionPlacement("world",40,100,-112,73),"world",0.5,111.0,0.5,
            reserved=true,journalZoneId="reserve",journalSceneId=1)
        MineExpeditionSceneRepository(root).use { repository ->
            repository.commit(reserve).join()
            repository.markBuilt(1).join()
            val built=repository.records().single()
            val active=built.copy(zoneId="mine",sequence=1,objectiveNonce=8,reserved=false)
            repository.claim(built,active).join()
            repository.markCompleted("mine",1,8,5000).join()
            repository.release(active,built).join()
        }
        MineExpeditionSceneRepository(root).use { repository ->
            repository.ready.join()
            val site=repository.records().single()
            site.siteBuilt shouldBe true
            site.reserved shouldBe true
            site.restoring shouldBe false
            site.placement shouldBe reserve.placement
        }
    }

    test("conflicting journal identities and invalid world names are rejected") {
        val root = Files.createTempDirectory("arcfarms-expedition-receipt-invalid")
        val placement = MineExpeditionPlacement("rc_arcfarms_expeditions", 0, 0, 0, 1L)
        val first = MineExpeditionSceneReceipt("zone", 1, 1, 7, MineExpeditionKind.LAST_DESCENT, placement, "sp11", 0.0, 64.0, 0.0)
        val conflicting = first.copy(sequence = 2)
        MineExpeditionSceneRepository(root).use { repository ->
            repository.commit(first).join()
            shouldThrow<java.util.concurrent.CompletionException> { repository.commit(conflicting).join() }
        }
        shouldThrow<IllegalArgumentException> {
            MineExpeditionPlacement("rc/arcfarms", 0, 0, 0, 1L)
        }
    }
})

private class DeferredExpeditionStorage : MineExpeditionLedgerStorage {
    var pending = java.util.concurrent.CompletableFuture<Unit>()
    var closed = false
    override fun load() = java.util.concurrent.CompletableFuture.completedFuture(MineExpeditionSceneLedger())
    override fun save(ledger: MineExpeditionSceneLedger) = java.util.concurrent.CompletableFuture<Unit>().also { pending = it }
    override fun shutdown() { closed = true }
}
