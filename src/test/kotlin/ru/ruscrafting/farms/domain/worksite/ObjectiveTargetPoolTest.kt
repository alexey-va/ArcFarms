package ru.ruscrafting.farms.domain.worksite

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.UUID

class ObjectiveTargetPoolTest : FunSpec({
    val key = WorksiteObjectiveKey("sawmill", "felling", 7L)
    val player = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val other = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val candidates = (1..8).map { index ->
        ObjectiveTargetCandidate(
            id = "target-$index",
            position = WorksitePosition("world", index, 64, 0),
            role = ObjectiveTargetRole("log"),
            score = index.toLong(),
        )
    }

    test("generated objectives expose twice the quota and cap accepted completion") {
        val planned = ObjectiveTargetPool.plan(key, required = 3, candidates = candidates.shuffled())

        planned.targets shouldHaveSize 6
        planned.targets.map(ObjectiveTargetState::id) shouldContainExactly (1..6).map { "target-$it" }

        var completed = planned
        val results = planned.targets.take(4).map { target ->
            ObjectiveTargetPool.complete(completed, target.id, player).also { completed = it.state }
        }

        completed.completed shouldBe 3
        completed.contributions[player] shouldBe 3
        results.map(ObjectiveTargetResult::accepted) shouldContainExactly listOf(true, true, true, false)
    }

    test("leases reject another player and release returns the target to the pool") {
        val state = ObjectiveTargetPool.plan(key, required = 2, candidates = candidates)
        val leased = ObjectiveTargetPool.lease(state, "target-1", player, now = 1_000L, leaseMillis = 45_000L)

        leased.accepted shouldBe true
        ObjectiveTargetPool.complete(leased.state, "target-1", other).accepted shouldBe false

        val released = ObjectiveTargetPool.release(leased.state, player)
        released.accepted shouldBe true
        released.state.target("target-1")?.status shouldBe ObjectiveTargetStatus.AVAILABLE
        released.state.target("target-1")?.leasedBy shouldBe null
    }

    test("expired leases can be reclaimed without resetting objective progress") {
        val state = ObjectiveTargetPool.plan(key, required = 2, candidates = candidates)
        val leased = ObjectiveTargetPool.lease(state, "target-1", player, now = 1_000L, leaseMillis = 45_000L).state

        ObjectiveTargetPool.lease(leased, "target-1", other, now = 45_999L).accepted shouldBe false
        val reclaimed = ObjectiveTargetPool.lease(leased, "target-1", other, now = 46_000L)

        reclaimed.accepted shouldBe true
        reclaimed.state.target("target-1")?.leasedBy shouldBe other
        reclaimed.state.completed shouldBe 0
    }

    test("invalidation consumes one deterministic reserve and preserves completed work") {
        var state = ObjectiveTargetPool.plan(key, required = 2, candidates = candidates)
        state = ObjectiveTargetPool.complete(state, "target-1", player).state

        val replaced = ObjectiveTargetPool.invalidate(state, "target-2")

        replaced.accepted shouldBe true
        replaced.state.completed shouldBe 1
        replaced.state.target("target-2") shouldBe null
        replaced.state.target("target-5")?.status shouldBe ObjectiveTargetStatus.AVAILABLE
        replaced.state.reserve.map(ObjectiveTargetCandidate::id) shouldContainExactly listOf("target-6", "target-7", "target-8")
    }

    test("planning rejects a pool that cannot satisfy the required quota") {
        shouldThrow<IllegalArgumentException> {
            ObjectiveTargetPool.plan(key, required = 3, candidates = candidates.take(2))
        }
    }

    test("objective state survives a real Gson round trip") {
        val state = ObjectiveTargetPool.complete(
            ObjectiveTargetPool.lease(
                ObjectiveTargetPool.plan(key, required = 2, candidates = candidates),
                "target-1",
                player,
                now = 1_000L,
            ).state,
            "target-1",
            player,
        ).state

        Gson().fromJson(Gson().toJson(state), WorksiteObjectiveState::class.java) shouldBe state
    }
})
