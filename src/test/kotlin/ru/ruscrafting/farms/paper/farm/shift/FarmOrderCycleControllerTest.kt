package ru.ruscrafting.farms.paper.farm.shift

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.ruscrafting.farms.paper.WorksiteRuntimePort

class FarmOrderCycleControllerTest : FunSpec({
    test("persists a pause mutation and exposes an immutable snapshot") {
        var saves = 0
        val controller = FarmOrderCycleController(mockk(relaxed = true)) { saves++ }

        controller.set("communal_farm", true).shouldBeTrue()
        controller.isPaused("communal_farm").shouldBeTrue()
        controller.snapshot().shouldContainExactlyInAnyOrder("communal_farm")
        saves shouldBe 1
    }

    test("rolls the mutation back when durable persistence fails") {
        val controller = FarmOrderCycleController(mockk<WorksiteRuntimePort>(relaxed = true)) {
            error("disk unavailable")
        }

        controller.set("communal_farm", true).shouldBeFalse()
        controller.isPaused("communal_farm").shouldBeFalse()
    }

    test("retains only zones present after configuration reload") {
        val controller = FarmOrderCycleController(mockk(relaxed = true)) {}
        controller.replace(listOf("one", "two", "removed"))

        controller.retain(setOf("one", "two"))

        controller.snapshot().shouldContainExactlyInAnyOrder("one", "two")
    }
})
