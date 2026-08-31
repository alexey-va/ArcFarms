package ru.ruscrafting.farms.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.config.LumberZoneSettings
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.paper.farm.FarmRuntimeRegistry
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry

class HotReloadRuntimeRegistryTest : FunSpec({
    test("farm reload mutates the active aggregate instead of invalidating incident references") {
        val currentSettings = farmSettings("farm")
        val candidateSettings = farmSettings("farm")
        val region = mockk<ActivityRegion>()
        val currentState = FarmShiftState(sequence = 12)
        val candidateState = FarmShiftState(sequence = 12, incidentProgress = 7)
        val current = FarmRuntime(currentSettings, region, emptyMap(), emptyList(), FarmRules(listOf(50), 1, 1_000), currentState)
        val candidate = FarmRuntime(candidateSettings, region, emptyMap(), emptyList(), FarmRules(listOf(60), 2, 2_000), candidateState)
        val registry = FarmRuntimeRegistry().also { it.replace(listOf(current)) }

        registry.reconfigure(listOf(candidate))

        registry.snapshot().single() shouldBeSameInstanceAs current
        current.settings shouldBeSameInstanceAs candidateSettings
        current.state shouldBe candidateState
        current.rules.cooldownMillis shouldBe 2_000
    }

    test("lumber and mine reload keep controller-held runtime references alive") {
        val region = mockk<ActivityRegion>()
        val station = mockk<ActivityRegion>()
        val lumberCurrent = LumberRuntime(lumberSettings("lumber"), region, station, 1_000, LumberShiftState(sequence = 3))
        val lumberCandidate = LumberRuntime(lumberSettings("lumber"), region, station, 2_000, LumberShiftState(sequence = 3))
        val lumber = LumberRuntimeRegistry().also { it.replace(listOf(lumberCurrent)) }
        val mineCurrent = MineRuntime(mineSettings("mine"), region, 1_000, MineShiftState(sequence = 4))
        val mineCandidate = MineRuntime(mineSettings("mine"), region, 2_000, MineShiftState(sequence = 4))
        val mine = MineRuntimeRegistry().also { it.replace(listOf(mineCurrent)) }

        lumber.reconfigure(listOf(lumberCandidate))
        mine.reconfigure(listOf(mineCandidate))

        lumber.snapshot().single() shouldBeSameInstanceAs lumberCurrent
        lumberCurrent.cooldownMillis shouldBe 2_000
        mine.snapshot().single() shouldBeSameInstanceAs mineCurrent
        mineCurrent.cooldownMillis shouldBe 2_000
    }

    test("live reload rejects zone topology changes instead of leaking active entities") {
        val region = mockk<ActivityRegion>()
        val registry = FarmRuntimeRegistry().also {
            it.replace(
                listOf(
                    FarmRuntime(
                        farmSettings("farm"), region, emptyMap(), emptyList(), FarmRules(listOf(50), 1, 1_000),
                        FarmShiftState(sequence = 1),
                    ),
                ),
            )
        }

        shouldThrow<IllegalArgumentException> { registry.reconfigure(emptyList()) }
    }
})

private fun farmSettings(id: String): FarmZoneSettings = mockk {
    every { this@mockk.id } returns id
}

private fun lumberSettings(id: String): LumberZoneSettings = mockk {
    every { this@mockk.id } returns id
    every { orders } returns emptyList()
}

private fun mineSettings(id: String): MineZoneSettings = mockk {
    every { this@mockk.id } returns id
    every { orders } returns emptyList()
}
