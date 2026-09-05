package ru.ruscrafting.farms.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmHellGreenhouseTest : FunSpec({
    val first = UUID(0, 1)
    val second = UUID(0, 2)
    val points = (0 until 4).map { FarmPointPosition("world", it + 0.5, 65.0, 0.5) }
    val rules = FarmHellGreenhouseRules(quota = 2, growSeconds = 2)
    fun state() = FarmHellGreenhouseEngine.initialize(FarmHellGreenhouseState(points), rules).state

    test("cooling the quota finishes automatically") {
        var current = state()
        repeat(2) { current = FarmHellGreenhouseEngine.second(current, setOf(first), rules).state }
        current = FarmHellGreenhouseEngine.pick(current, first, 0, rules).state
        FarmHellGreenhouseEngine.cool(current, first, rules).state.let { current = it }
        current = FarmHellGreenhouseEngine.pick(current, first, 1, rules).state
        val result = FarmHellGreenhouseEngine.cool(current, first, rules)
        result.accepted shouldBe true
        result.contribution shouldBe 1
        result.finished shouldBe true
        result.successful shouldBe true
        result.state.finished shouldBe true
    }

    test("finishing quota clears another player's carried pepper") {
        var current = state()
        repeat(2) { current = FarmHellGreenhouseEngine.second(current, setOf(first, second), rules).state }
        current = FarmHellGreenhouseEngine.pick(current, first, 0, rules).state
        current = FarmHellGreenhouseEngine.pick(current, second, 1, rules).state
        current = FarmHellGreenhouseEngine.cool(current, first, rules).state
        current = FarmHellGreenhouseEngine.pick(current, first, 2, rules).state
        val result = FarmHellGreenhouseEngine.cool(current, second, rules)
        result.state.finished shouldBe true
        result.state.carried shouldBe emptyMap()
        FarmHellGreenhouseEngine.validate(result.state)
    }

    test("growth remains available after long idle time and has no timeout") {
        var current = state()
        repeat(200) { current = FarmHellGreenhouseEngine.second(current, setOf(first), rules).state }
        current = FarmHellGreenhouseEngine.pick(current, first, 0, rules).state
        repeat(200) { current = FarmHellGreenhouseEngine.second(current, setOf(first), rules).state }
        current = FarmHellGreenhouseEngine.cool(current, first, rules).state
        current.cooled shouldBe 1
        current.finished shouldBe false
        current.evacuationSeconds shouldBe null
        current.heat shouldBe 0
    }

    test("release returns the plant for another player") {
        var current = state()
        repeat(2) { current = FarmHellGreenhouseEngine.second(current, setOf(first, second), rules).state }
        current = FarmHellGreenhouseEngine.pick(current, first, 0, rules).state
        current = FarmHellGreenhouseEngine.release(current, first, rules).state
        current.harvested shouldBe emptySet()
        FarmHellGreenhouseEngine.pick(current, second, 0, rules).accepted shouldBe true
    }

    test("legacy carried peppers survive and lost plants regrow without losing delivered progress") {
        val legacy = state().copy(
            elapsedSeconds = 10,
            harvested = setOf(0, 1, 2, 3),
            cooled = 1,
            carried = mapOf(first to FarmHellPepper(0, 4)),
            heat = 99,
            evacuationSeconds = 1,
        )
        val result = FarmHellGreenhouseEngine.second(legacy, setOf(first), rules)
        result.state.carried shouldBe mapOf(first to FarmHellPepper(0, Int.MAX_VALUE))
        result.state.harvested shouldBe setOf(0, 1)
        result.state.cooled shouldBe 1
        result.state.heat shouldBe 0
        result.state.evacuationSeconds shouldBe null
    }

    test("legacy quota already delivered completes on the next tick") {
        val result = FarmHellGreenhouseEngine.second(state().copy(cooled = 2, harvested = setOf(0, 1)), setOf(first), rules)
        result.finished shouldBe true
        result.successful shouldBe true
        result.contribution shouldBe 0
        FarmHellGreenhouseEngine.cool(result.state, first, rules).accepted shouldBe false
    }

    test("duplicate and stale pickups are rejected") {
        var current = state()
        FarmHellGreenhouseEngine.pick(current, first, 0, rules).accepted shouldBe false
        repeat(2) { current = FarmHellGreenhouseEngine.second(current, setOf(first), rules).state }
        current = FarmHellGreenhouseEngine.pick(current, first, 0, rules).state
        FarmHellGreenhouseEngine.pick(current, first, 0, rules).accepted shouldBe false
        FarmHellGreenhouseEngine.pick(current, first, 1, rules).accepted shouldBe false
    }

    test("invalid points, quota, indexes, and duplicate ownership remain rejected") {
        shouldThrow<IllegalArgumentException> { FarmHellGreenhouseEngine.initialize(FarmHellGreenhouseState(points.take(3)), rules) }
        shouldThrow<IllegalArgumentException> { FarmHellGreenhouseEngine.initialize(FarmHellGreenhouseState(points), rules.copy(quota = 5)) }
        shouldThrow<IllegalArgumentException> { FarmHellGreenhouseEngine.initialize(FarmHellGreenhouseState(points, harvested = setOf(4)), rules) }
        shouldThrow<IllegalArgumentException> {
            FarmHellGreenhouseEngine.initialize(FarmHellGreenhouseState(points, harvested = setOf(0), carried = mapOf(first to FarmHellPepper(0, 4), second to FarmHellPepper(0, 5))), rules)
        }
        shouldThrow<IllegalArgumentException> { FarmHellGreenhouseEngine.pick(state(), first, 4, rules) }
    }
})
