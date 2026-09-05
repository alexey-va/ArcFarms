package ru.ruscrafting.farms.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmHellGreenhouseTest : FunSpec({
    val first = UUID(0, 1)
    val second = UUID(0, 2)
    val points = (0 until 4).map { FarmPointPosition("world", it + 0.5, 65.0, 0.5) }
    val rules = FarmHellGreenhouseRules(
        quota = 2,
        growSeconds = 2,
        hotSeconds = 2,
        heatLimit = 100,
        heatPerHarvest = 2,
        evacuationSeconds = 15,
    )
    val overheatRules = rules.copy(heatLimit = 3)

    fun state(using: FarmHellGreenhouseRules = rules) = FarmHellGreenhouseEngine.initialize(
        FarmHellGreenhouseState(points = points), using,
    ).state

    test("pick contributes zero, cooling completes the quota, and evacuation succeeds once") {
        var current = state()
        repeat(2) { current = FarmHellGreenhouseEngine.second(current, setOf(first), rules).state }
        val picked = FarmHellGreenhouseEngine.pick(current, first, 0, rules)
        picked.accepted shouldBe true
        picked.contribution shouldBe 0
        current = picked.state
        FarmHellGreenhouseEngine.cool(current, first, rules).let {
            it.accepted shouldBe true
            it.contribution shouldBe 1
            current = it.state
        }
        current = FarmHellGreenhouseEngine.pick(current, first, 1, rules).state
        FarmHellGreenhouseEngine.cool(current, first, rules).let {
            it.contribution shouldBe 1
            current = it.state
        }
        current.cooled shouldBe 2
        val evacuated = FarmHellGreenhouseEngine.evacuate(current, first, rules)
        evacuated.accepted shouldBe true
        evacuated.finished shouldBe true
        evacuated.state.finished shouldBe true
        FarmHellGreenhouseEngine.evacuate(evacuated.state, first, rules).accepted shouldBe false
    }

    test("stale, duplicate, and occupied pickups are rejected") {
        var current = state()
        FarmHellGreenhouseEngine.pick(current, first, 0, rules).accepted shouldBe false
        repeat(2) { current = FarmHellGreenhouseEngine.second(current, setOf(first), rules).state }
        current = FarmHellGreenhouseEngine.pick(current, first, 0, rules).state
        FarmHellGreenhouseEngine.pick(current, first, 0, rules).accepted shouldBe false
        FarmHellGreenhouseEngine.pick(current, first, 1, rules).accepted shouldBe false
    }

    test("two players can carry separate peppers and expiry reports both without cooling credit") {
        var current = state()
        repeat(2) { current = FarmHellGreenhouseEngine.second(current, setOf(first, second), rules).state }
        current = FarmHellGreenhouseEngine.pick(current, first, 0, rules).state
        current = FarmHellGreenhouseEngine.pick(current, second, 1, rules).state
        val ticking = FarmHellGreenhouseEngine.second(current, setOf(first, second), rules)
        ticking.expiredPlayerIds shouldContainExactly emptySet()
        val expired = FarmHellGreenhouseEngine.second(ticking.state, setOf(first, second), rules)
        expired.expiredPlayerIds shouldContainExactly setOf(first, second)
        expired.state.carried shouldBe emptyMap()
        expired.state.cooled shouldBe 0
    }

    test("unharvested plants remain ripe after the first growth window") {
        var current = state()
        repeat(20) { current = FarmHellGreenhouseEngine.second(current, setOf(first), rules).state }
        FarmHellGreenhouseEngine.pick(current, first, 0, rules).accepted shouldBe true
    }

    test("empty participants pause growth, heat, and carried pepper clocks") {
        var current = state()
        repeat(2) { current = FarmHellGreenhouseEngine.second(current, setOf(first), rules).state }
        current = FarmHellGreenhouseEngine.pick(current, first, 0, rules).state
        val paused = FarmHellGreenhouseEngine.second(current, emptySet(), rules)
        paused.state shouldBe current
        paused.expiredPlayerIds shouldBe emptySet()
    }

    test("active seconds add ambient heat and overheating allows one partial escape") {
        var current = state(overheatRules)
        current = FarmHellGreenhouseEngine.second(current, setOf(first), overheatRules).state
        current.heat shouldBe 1
        current = FarmHellGreenhouseEngine.second(current, setOf(first), overheatRules).state
        current = FarmHellGreenhouseEngine.pick(current, first, 0, overheatRules).state
        current.heat shouldBe 3
        current.evacuationSeconds shouldBe 15
        FarmHellGreenhouseEngine.pick(current, first, 1, overheatRules).accepted shouldBe false
        val escaped = FarmHellGreenhouseEngine.evacuate(current, first, overheatRules)
        escaped.accepted shouldBe true
        escaped.finished shouldBe true
        escaped.state.cooled shouldBe 0
        escaped.state.carried shouldBe emptyMap()
        escaped.state.heat shouldBe 3
        FarmHellGreenhouseEngine.evacuate(escaped.state, first, overheatRules).accepted shouldBe false
    }

    test("overheating timeout is explicit and finishes the state") {
        var current = state(overheatRules)
        repeat(2) { current = FarmHellGreenhouseEngine.second(current, setOf(first), overheatRules).state }
        current = FarmHellGreenhouseEngine.pick(current, first, 0, overheatRules).state
        repeat(14) { current = FarmHellGreenhouseEngine.second(current, setOf(first), overheatRules).state }
        val result = FarmHellGreenhouseEngine.second(current, setOf(first), overheatRules)
        result.timedOut shouldBe true
        result.state.finished shouldBe true
        FarmHellGreenhouseEngine.second(result.state, setOf(first), overheatRules).accepted shouldBe false
    }

    test("release drops a carried pepper without changing cooled quota") {
        var current = state()
        repeat(2) { current = FarmHellGreenhouseEngine.second(current, setOf(first), rules).state }
        current = FarmHellGreenhouseEngine.pick(current, first, 0, rules).state
        val released = FarmHellGreenhouseEngine.release(current, first, rules)
        released.accepted shouldBe true
        released.state.carried shouldBe emptyMap()
        released.state.cooled shouldBe 0
        FarmHellGreenhouseEngine.release(released.state, first, rules).accepted shouldBe false
    }

    test("invalid points, quota, indexes, and duplicate ownership are rejected") {
        shouldThrow<IllegalArgumentException> {
            FarmHellGreenhouseEngine.initialize(FarmHellGreenhouseState(points.take(3)), rules)
        }
        shouldThrow<IllegalArgumentException> {
            FarmHellGreenhouseEngine.initialize(FarmHellGreenhouseState(points), rules.copy(quota = 5))
        }
        shouldThrow<IllegalArgumentException> {
            FarmHellGreenhouseEngine.initialize(FarmHellGreenhouseState(points, harvested = setOf(4)), rules)
        }
        shouldThrow<IllegalArgumentException> {
            FarmHellGreenhouseEngine.initialize(
                FarmHellGreenhouseState(points, carried = mapOf(first to FarmHellPepper(0, 4), second to FarmHellPepper(0, 5))), rules,
            )
        }
        shouldThrow<IllegalArgumentException> {
            FarmHellGreenhouseEngine.pick(state(), first, 4, rules)
        }
    }
})
