package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmLocationOverridesTest : FunSpec({
    test("removing the last override also removes the empty zone entry") {
        val overrides = FarmLocationOverrides(
            zones = mapOf(
                "communal_farm" to mapOf(
                    FarmPointKind.SCARECROWS to FarmPointPosition("sp11", 10.5, 64.0, 20.5),
                ),
            ),
        )

        overrides.without("communal_farm", FarmPointKind.SCARECROWS).zones shouldBe emptyMap()
    }

    test("removing one override preserves the other farm points") {
        val tool = FarmPointPosition("sp11", 5.5, 64.0, 6.5)
        val overrides = FarmLocationOverrides(
            zones = mapOf(
                "communal_farm" to mapOf(
                    FarmPointKind.TOOL to tool,
                    FarmPointKind.COVERS to FarmPointPosition("sp11", 10.5, 64.0, 20.5),
                ),
            ),
        )

        overrides.without("communal_farm", FarmPointKind.COVERS).zones shouldBe mapOf(
            "communal_farm" to mapOf(FarmPointKind.TOOL to tool),
        )
        overrides.without("unknown", FarmPointKind.COVERS) shouldBe overrides
    }
})
