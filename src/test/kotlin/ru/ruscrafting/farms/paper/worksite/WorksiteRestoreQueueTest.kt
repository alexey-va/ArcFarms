package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class WorksiteRestoreQueueTest : FunSpec({
    data class Repair(val position: String, val restoreAt: Long, val material: String)

    test("one key keeps the earliest deadline and replaces equal-deadline payloads") {
        val queue = WorksiteRestoreQueue<String, Repair>(Repair::position, Repair::restoreAt)
        queue.schedule(Repair("world:1:2:3", 2_000L, "STONE"))
        queue.schedule(Repair("world:1:2:3", 3_000L, "LATER"))
        queue.schedule(Repair("world:1:2:3", 2_000L, "RETRY"))

        queue.size shouldBe 1
        queue.pollDue(1_999L, 4) shouldBe emptyList()
        queue.pollDue(2_000L, 4) shouldContainExactly listOf(Repair("world:1:2:3", 2_000L, "RETRY"))
        queue.size shouldBe 0
    }

    test("repeated retries do not grow stale entries and preserve due ordering") {
        val queue = WorksiteRestoreQueue<String, Repair>(Repair::position, Repair::restoreAt)
        repeat(100) { attempt ->
            queue.schedule(Repair("blocked", 1_000L, "due-$attempt"))
            queue.pollDue(1_000L, 1)
            queue.schedule(Repair("blocked", 2_000L + attempt, "retry-$attempt"))
        }
        queue.schedule(Repair("ready", 1_050L, "ready"))

        queue.size shouldBe 2
        queue.pollDue(1_050L, 1).map(Repair::position) shouldContainExactly listOf("ready")
        queue.pollDue(1_999L, 4) shouldBe emptyList()
        queue.pollDue(2_099L, 4).map(Repair::material) shouldContainExactly listOf("retry-99")
        queue.size shouldBe 0
    }
})
