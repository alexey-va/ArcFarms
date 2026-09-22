package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MineDieselGeneratorInspectionCatalogTest : FunSpec({
    test("generator parts expose every stable inspection category and keep frame parts") {
        val parts = MineDieselGeneratorModel.model()
        val requiredIds = setOf(
            "cylinder", "piston", "connecting-rod", "crankshaft", "camshaft",
            "inlet-valve", "exhaust-valve", "injector", "timing-belt", "flywheel",
            "alternator", "radiator", "fan", "cooling", "turbo", "intake", "exhaust",
            "battery", "control-panel",
        )
        val ids = parts.mapNotNull { it.inspection }.toSet()

        ids shouldBe requiredIds
        // Unlabelled frame remains present for ray occlusion rather than vanishing from the model.
        parts.any { it.inspection == null } shouldBe true
    }

    test("inspection labels include the moving mechanisms and leave stationary systems fixed") {
        val parts = MineDieselGeneratorModel.model()
        val movingSystems = mapOf(
            "diesel_piston_0" to "piston",
            "diesel_rod_0" to "connecting-rod",
            "diesel_valve_inlet_0" to "inlet-valve",
            "diesel_valve_exhaust_0" to "exhaust-valve",
            "diesel_spring_inlet_0" to "inlet-valve",
            "diesel_spring_exhaust_0" to "exhaust-valve",
        )
        movingSystems.forEach { (motion, inspection) ->
            val movingParts = parts.filter { it.motion == motion }
            movingParts.isNotEmpty() shouldBe true
            movingParts.all { it.moving && it.inspection == inspection } shouldBe true
        }
        val camshaftParts = parts.filter { it.motion == "diesel_cam" && it.inspection == "camshaft" }
        camshaftParts.isNotEmpty() shouldBe true
        camshaftParts.all { it.moving } shouldBe true

        val injectorTips = parts.filter { it.motion == "diesel_injector_tip_0" }
        injectorTips.isNotEmpty() shouldBe true
        injectorTips.all { !it.moving && it.inspection == "injector" } shouldBe true
        parts.filter { it.inspection == "cylinder" }
            .all { !it.moving } shouldBe true
        parts.filter { it.inspection == "radiator" }
            .all { !it.moving } shouldBe true
        parts.filter { it.inspection == "fan" }
            .any { it.moving } shouldBe true

        val timingBelt = parts.filter { it.inspection == "timing-belt" }
        timingBelt.any { it.moving } shouldBe true
        timingBelt.any { !it.moving } shouldBe true
    }
})
