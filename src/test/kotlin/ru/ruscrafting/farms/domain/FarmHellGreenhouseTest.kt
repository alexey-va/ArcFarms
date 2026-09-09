package ru.ruscrafting.farms.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmHellGreenhouseTest : FunSpec({
    val player = UUID(0, 1)
    val points = listOf(
        FarmPointPosition("world", -5.0, 55.0, -6.0), FarmPointPosition("world", 5.0, 55.0, -6.0),
        FarmPointPosition("world", -5.0, 55.0, 6.0), FarmPointPosition("world", 5.0, 55.0, 6.0),
    )
    val rules = FarmHellGreenhouseRules(quota = 2)
    fun state() = FarmHellGreenhouseEngine.initialize(FarmHellGreenhouseState(points), rules).state

    test("initialization creates four cold plots and preserves legacy cooled quota") {
        val legacy = FarmHellGreenhouseState(points + FarmPointPosition("world", 9.0, 55.0, 9.0), cooled = 1, harvested = setOf(0))
        val migrated = FarmHellGreenhouseEngine.initialize(legacy, rules).state
        migrated.layoutVersion shouldBe 2
        migrated.points shouldBe points
        migrated.plots shouldBe List(4) { FarmHellPlantationPlot() }
        migrated.cooled shouldBe 1
        migrated.harvested shouldBe emptySet()
        migrated.carried shouldBe emptyMap()
    }

    test("heated plot grows for eight seconds, cools for two, then harvests once") {
        var current = state()
        current = FarmHellGreenhouseEngine.toggleHeat(current, 0, rules).state
        repeat(8) { current = FarmHellGreenhouseEngine.second(current, setOf(player), rules).state }
        FarmHellGreenhouseEngine.phase(current.plots[0]) shouldBe FarmHellPlantationPhase.HOT
        current = FarmHellGreenhouseEngine.toggleHeat(current, 0, rules).state
        repeat(2) { current = FarmHellGreenhouseEngine.second(current, setOf(player), rules).state }
        FarmHellGreenhouseEngine.phase(current.plots[0]) shouldBe FarmHellPlantationPhase.READY
        current = FarmHellGreenhouseEngine.toggleHeat(current, 0, rules).state
        current.plots[0].coolingSeconds shouldBe 0
        FarmHellGreenhouseEngine.phase(current.plots[0]) shouldBe FarmHellPlantationPhase.HOT
        current = FarmHellGreenhouseEngine.toggleHeat(current, 0, rules).state
        repeat(2) { current = FarmHellGreenhouseEngine.second(current, setOf(player), rules).state }
        val harvested = FarmHellGreenhouseEngine.harvest(current, 0, rules)
        harvested.contribution shouldBe 1
        harvested.state.cooled shouldBe 1
        harvested.state.plots[0] shouldBe FarmHellPlantationPlot()
        FarmHellGreenhouseEngine.harvest(harvested.state, 0, rules).accepted shouldBe false
    }

    test("closing during every warning second leaves a valid cooling state") {
        for (delay in 1..3) {
            var current = FarmHellGreenhouseEngine.toggleHeat(state(), 0, rules).state
            repeat(8 + delay) { current = FarmHellGreenhouseEngine.second(current, setOf(player), rules).state }
            current.plots[0].overheatSeconds shouldBe delay
            current = FarmHellGreenhouseEngine.toggleHeat(current, 0, rules).state
            FarmHellGreenhouseEngine.validate(current)
            current.plots[0].overheatSeconds shouldBe 0
            repeat(2) { current = FarmHellGreenhouseEngine.second(current, setOf(player), rules).state }
            FarmHellGreenhouseEngine.phase(current.plots[0]) shouldBe FarmHellPlantationPhase.READY
            FarmHellGreenhouseEngine.harvest(current, 0, rules).contribution shouldBe 1
        }
    }

    test("four hot seconds scorch and reset the plot") {
        var current = state()
        current = FarmHellGreenhouseEngine.toggleHeat(current, 0, rules).state
        repeat(8) { current = FarmHellGreenhouseEngine.second(current, setOf(player), rules).state }
        repeat(3) { current = FarmHellGreenhouseEngine.second(current, setOf(player), rules).state }
        val burned = FarmHellGreenhouseEngine.second(current, setOf(player), rules)
        burned.scorchedPlots shouldContain 0
        burned.state.plots[0] shouldBe FarmHellPlantationPlot()
    }

    test("zero participants pause all timers") {
        var current = state()
        current = FarmHellGreenhouseEngine.toggleHeat(current, 0, rules).state
        repeat(3) { current = FarmHellGreenhouseEngine.second(current, setOf(player), rules).state }
        FarmHellGreenhouseEngine.second(current, emptySet(), rules).state shouldBe current
    }

    test("closing a partially grown plot makes it cold and reopening resumes growth") {
        var current = FarmHellGreenhouseEngine.toggleHeat(state(), 0, rules).state
        repeat(3) { current = FarmHellGreenhouseEngine.second(current, setOf(player), rules).state }
        current = FarmHellGreenhouseEngine.toggleHeat(current, 0, rules).state
        FarmHellGreenhouseEngine.phase(current.plots[0]) shouldBe FarmHellPlantationPhase.COLD
        FarmHellGreenhouseEngine.second(current, setOf(player), rules).state.plots shouldBe current.plots
        current = FarmHellGreenhouseEngine.toggleHeat(current, 0, rules).state
        repeat(5) { current = FarmHellGreenhouseEngine.second(current, setOf(player), rules).state }
        current.plots[0].growthSeconds shouldBe 8
        FarmHellGreenhouseEngine.phase(current.plots[0]) shouldBe FarmHellPlantationPhase.HOT
    }

    test("initializing an existing plantation preserves active plots") {
        var current = FarmHellGreenhouseEngine.toggleHeat(state(), 0, rules).state
        repeat(2) { current = FarmHellGreenhouseEngine.second(current, setOf(player), rules).state }
        val initialized = FarmHellGreenhouseEngine.initialize(current, rules)
        initialized.state shouldBe current
    }

    test("invalid layout and timer values are rejected") {
        shouldThrow<IllegalArgumentException> { FarmHellGreenhouseEngine.validate(state().copy(layoutVersion = 9)) }
        shouldThrow<IllegalArgumentException> { FarmHellGreenhouseEngine.validate(state().copy(plots = List(4) { FarmHellPlantationPlot(growthSeconds = 9) })) }
        shouldThrow<IllegalArgumentException> { FarmHellGreenhouseEngine.validate(state().copy(carried = mapOf(player to FarmHellPepper(0, 1)))) }
        shouldThrow<IllegalArgumentException> { FarmHellGreenhouseEngine.validate(state().copy(plots = List(4) { FarmHellPlantationPlot(growthSeconds = 3, coolingSeconds = 1) })) }
        shouldThrow<IllegalArgumentException> { FarmHellGreenhouseEngine.initialize(FarmHellGreenhouseState(points.take(3)), rules) }
    }
})
