package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class FarmEventTypeRegistryTest : FunSpec({
    test("every farm incident has an explicit archetype") {
        FarmIncidentType.entries.all { type ->
            runCatching { FarmEventTypeRegistry.definition(type) }.isSuccess
        } shouldBe true
    }

    test("underground and portal baselines are explicit") {
        FarmEventTypeRegistry.definition(FarmIncidentType.HELL_GREENHOUSE).shouldBeInstanceOf<FarmEventTypeDefinition.UndergroundEvent>()
        FarmEventTypeRegistry.definition(FarmIncidentType.FOOD_DELIVERY).shouldBeInstanceOf<FarmEventTypeDefinition.PortalEvent>()
        FarmEventTypeRegistry.definition(FarmIncidentType.BOAR_BREAKOUT).shouldBeInstanceOf<FarmEventTypeDefinition.FieldEvent>()
    }
})
