package ru.ruscrafting.farms.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture

class CoalescingAsyncWriterTest : FunSpec({
    test("bursts retain only the latest pending snapshot without completing callers early") {
        val writes = mutableListOf<Int>()
        val gates = mutableListOf<CompletableFuture<Unit>>()
        val writer = CoalescingAsyncWriter<Int> { value ->
            writes += value
            CompletableFuture<Unit>().also(gates::add)
        }

        val first = writer.submit(1)
        val replaced = writer.submit(2)
        val latest = writer.submit(3)

        writes.shouldContainExactly(1)
        first.isDone shouldBe false
        replaced.isDone shouldBe false
        latest.isDone shouldBe false

        gates[0].complete(Unit)
        writes.shouldContainExactly(1, 3)
        first.isDone shouldBe true
        replaced.isDone shouldBe false
        latest.isDone shouldBe false

        gates[1].complete(Unit)
        replaced.isDone shouldBe true
        latest.isDone shouldBe true
        writer.close().isDone shouldBe true
    }

    test("a newer successful snapshot recovers callers from a failed older write") {
        val writes = mutableListOf<Int>()
        val gates = mutableListOf<CompletableFuture<Unit>>()
        val writer = CoalescingAsyncWriter<Int> { value ->
            writes += value
            CompletableFuture<Unit>().also(gates::add)
        }

        val failed = writer.submit(1)
        val recovered = writer.submit(2)
        gates[0].completeExceptionally(IllegalStateException("disk unavailable"))

        writes.shouldContainExactly(1, 2)
        failed.isDone shouldBe false
        recovered.isDone shouldBe false

        gates[1].complete(Unit)
        failed.isCompletedExceptionally shouldBe false
        failed.isDone shouldBe true
        recovered.isCompletedExceptionally shouldBe false
        recovered.isDone shouldBe true
        writer.close().isDone shouldBe true
    }

    test("close waits for queued work and rejects later submissions") {
        val gates = mutableListOf<CompletableFuture<Unit>>()
        val writer = CoalescingAsyncWriter<Int> { CompletableFuture<Unit>().also(gates::add) }

        writer.submit(1)
        writer.submit(2)
        val closed = writer.close()
        closed.isDone shouldBe false
        writer.submit(3).isCompletedExceptionally shouldBe true

        gates[0].complete(Unit)
        closed.isDone shouldBe false
        gates[1].complete(Unit)
        closed.isDone shouldBe true
    }

    test("close reports failure when the final snapshot was not written") {
        val gate = CompletableFuture<Unit>()
        val writer = CoalescingAsyncWriter<Int> { gate }

        writer.submit(1)
        val closed = writer.close()
        gate.completeExceptionally(IllegalStateException("disk unavailable"))

        closed.isCompletedExceptionally shouldBe true
    }
})
