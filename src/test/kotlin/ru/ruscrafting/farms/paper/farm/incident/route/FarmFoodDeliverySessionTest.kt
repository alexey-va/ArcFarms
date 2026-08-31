package ru.ruscrafting.farms.paper.farm.incident.route

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly

class FarmFoodDeliverySessionTest : FunSpec({
    test("live ambush replan counts waves that already started") {
        val session = FarmFoodDeliverySession(sequence = 7, routeName = "main", ambushesStarted = 2)
        session.pendingAmbushCheckpoints.addAll(listOf(8, 12))

        session.replaceAmbushCheckpoints(
            candidates = listOf(10, 14, 18, 22),
            maximum = 3,
            progress = 9,
        )

        session.pendingAmbushCheckpoints.toList().shouldContainExactly(8)
    }

    test("lowering the live ambush maximum below consumed waves schedules nothing else") {
        val session = FarmFoodDeliverySession(sequence = 8, routeName = "main", ambushesStarted = 3)
        session.pendingAmbushCheckpoints.addAll(listOf(12, 18))

        session.replaceAmbushCheckpoints(
            candidates = listOf(14, 20),
            maximum = 2,
            progress = 10,
        )

        session.pendingAmbushCheckpoints.toList().shouldContainExactly()
    }
})
