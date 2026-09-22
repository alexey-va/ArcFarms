package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryExperiment
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryExperiments

class MineFactoryExperimentPresetsTest : FunSpec({
    test("forced next run is scoped to a zone and consumed exactly once") {
        val presets = MineFactoryExperimentPresets()
        presets.configure("mine", "all") shouldBe true
        presets.consume("other", 17) shouldBe MineFactoryExperiments.select(17)
        presets.consume("mine", 17).selected shouldBe MineFactoryExperiments.supported
        presets.consume("mine", 17) shouldBe MineFactoryExperiments.select(17)
    }
    test("none is explicit and invalid input never overwrites a pending override") {
        val presets = MineFactoryExperimentPresets()
        presets.configure("mine", "none") shouldBe true
        presets.configure("mine", "typo") shouldBe false
        presets.consume("mine", 17).selected shouldBe emptySet()
        presets.configure("mine", "rock") shouldBe true
        presets.consume("mine", 19).selected shouldBe setOf(MineFactoryExperiment.ROCK_JAM)
        presets.configure("mine", "mould") shouldBe false
        presets.configure("mine", "route") shouldBe false
        presets.configure("mine", "gear") shouldBe false
    }
    test("current admin presets expose rock, crane and cooling only") {
        val presets = MineFactoryExperimentPresets()
        for ((name, experiment) in listOf(
            "rock" to MineFactoryExperiment.ROCK_JAM,
            "crane" to MineFactoryExperiment.MANUAL_CRANE,
            "cooling" to MineFactoryExperiment.COOLING,
        )) {
            presets.configure("mine", name) shouldBe true
            presets.consume("mine", 17).selected shouldBe setOf(experiment)
        }
    }
    test("random and lifecycle cleanup discard pending admin overrides") {
        val presets = MineFactoryExperimentPresets()
        presets.configure("mine", "all")
        presets.configure("mine", "random") shouldBe true
        presets.consume("mine", 17) shouldBe MineFactoryExperiments.select(17)
        presets.configure("mine", "all")
        presets.clear()
        presets.consume("mine", 17) shouldBe MineFactoryExperiments.select(17)
    }
})
