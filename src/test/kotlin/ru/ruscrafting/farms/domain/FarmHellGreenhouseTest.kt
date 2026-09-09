package ru.ruscrafting.farms.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmHellGreenhouseTest : FunSpec({
    val first = UUID(0, 1)
    val second = UUID(0, 2)
    val points = (0 until 4).map { FarmPointPosition("world", it + 0.5, 65.0, 0.5) }
    val rules = FarmHellGreenhouseRules(quota = 2)
    fun state() = FarmHellGreenhouseEngine.initialize(FarmHellGreenhouseState(points), rules).state

    test("continuous presence takes sixty ticks and leaving or switching player resets it") {
        var charge = FarmHellRiftCharge()
        repeat(59) { charge = charge.tick(setOf(first)) }
        charge.complete shouldBe false
        charge.tick(setOf(first)).complete shouldBe true
        charge.tick(emptySet()) shouldBe FarmHellRiftCharge()
        charge.tick(setOf(second)).ticks shouldBe 1
        charge.tick(setOf(first, second)).playerId shouldBe first
    }

    test("runes complete in order and cannot contribute twice") {
        var current = state()
        FarmHellGreenhouseEngine.seal(current, 1, rules).accepted shouldBe false
        val firstSeal = FarmHellGreenhouseEngine.seal(current, 0, rules)
        firstSeal.contribution shouldBe 1
        current = firstSeal.state
        FarmHellGreenhouseEngine.seal(current, 0, rules).accepted shouldBe false
        val finished = FarmHellGreenhouseEngine.seal(current, 1, rules)
        finished.successful shouldBe true
        finished.state.cooled shouldBe 2
        FarmHellGreenhouseEngine.seal(finished.state, 2, rules).accepted shouldBe false
    }

    test("legacy peppers migrate to seals without replaying credit or losing progress") {
        val legacy = FarmHellGreenhouseState(points, elapsedSeconds = 10, harvested = setOf(0, 1, 2),
            cooled = 1, carried = mapOf(first to FarmHellPepper(2, 50)), heat = 99, evacuationSeconds = 1)
        val migrated = FarmHellGreenhouseEngine.initialize(legacy, rules)
        migrated.state.cooled shouldBe 1
        migrated.state.carried shouldBe emptyMap()
        migrated.state.harvested shouldBe setOf(0)
        migrated.state.layoutVersion shouldBe 1
        migrated.contribution shouldBe 0
        FarmHellGreenhouseEngine.initialize(migrated.state, rules).state shouldBe migrated.state
    }

    test("hazard pauses without participants and alternates warning and active sides") {
        FarmHellGreenhouseEngine.second(state(), emptySet(), rules).state shouldBe state()
        FarmHellGreenhouseEngine.hazard(state().copy(elapsedSeconds = 0)) shouldBe FarmHellHazard(FarmHellHazardPhase.WARNING, FarmHellHazardSide.LEFT, 3)
        FarmHellGreenhouseEngine.hazard(state().copy(elapsedSeconds = 3)) shouldBe FarmHellHazard(FarmHellHazardPhase.ACTIVE, FarmHellHazardSide.LEFT, 2)
        FarmHellGreenhouseEngine.hazard(state().copy(elapsedSeconds = 5)) shouldBe FarmHellHazard(FarmHellHazardPhase.REST, FarmHellHazardSide.NONE, 3)
        FarmHellGreenhouseEngine.hazard(state().copy(elapsedSeconds = 8)) shouldBe FarmHellHazard(FarmHellHazardPhase.WARNING, FarmHellHazardSide.RIGHT, 3)
    }

    test("invalid legacy indexes and unknown layout remain rejected") {
        shouldThrow<IllegalArgumentException> { FarmHellGreenhouseEngine.initialize(FarmHellGreenhouseState(points.take(3)), rules) }
        shouldThrow<IllegalArgumentException> { FarmHellGreenhouseEngine.initialize(FarmHellGreenhouseState(points), rules.copy(quota = 5)) }
        shouldThrow<IllegalArgumentException> { FarmHellGreenhouseEngine.initialize(state().copy(harvested = setOf(4)), rules) }
        shouldThrow<IllegalArgumentException> { FarmHellGreenhouseEngine.validate(state().copy(layoutVersion = 9)) }
    }
})
