package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

class FarmContractSceneReconcilerTest : FunSpec({
    val cart = FarmContractSceneIdentity("farm", 7L, FarmContractSceneRole.CART)
    val target = FarmContractSceneTarget(cart, "world", 10.5, 64.15, -3.5)

    test("keeps one nearest current entity and removes duplicate persistent orphans") {
        val plan = FarmContractSceneReconciler.plan(
            targets = listOf(target),
            candidates = listOf(
                candidate("nearest", cart, FarmContractSceneRole.CART, 10.5, 64.15, -3.5),
                candidate("duplicate", cart, FarmContractSceneRole.CART, 10.7, 64.15, -3.5),
                candidate("old-shift", cart.copy(sequence = 6L), FarmContractSceneRole.CART, 10.5, 64.15, -3.5),
            ),
        )

        plan.keep shouldContainExactly mapOf(cart to "nearest")
        plan.remove.shouldContainExactlyInAnyOrder("duplicate", "old-shift")
    }

    test("removes malformed wrong-type and displaced scene entities") {
        val plan = FarmContractSceneReconciler.plan(
            targets = listOf(target),
            candidates = listOf(
                candidate("malformed", null, FarmContractSceneRole.CART, 10.5, 64.15, -3.5),
                candidate("wrong-type", cart, FarmContractSceneRole.CUSTOMER, 10.5, 64.15, -3.5),
                candidate("moved", cart, FarmContractSceneRole.CART, 30.5, 64.15, -3.5),
            ),
        )

        plan.keep shouldBe emptyMap()
        plan.remove.shouldContainExactlyInAnyOrder("malformed", "wrong-type", "moved")
    }

    test("removes every owned entity when the scene is inactive") {
        val plan = FarmContractSceneReconciler.plan(
            targets = emptyList(),
            candidates = listOf(candidate("orphan", cart, FarmContractSceneRole.CART, 10.5, 64.15, -3.5)),
        )

        plan.keep shouldBe emptyMap()
        plan.remove shouldBe setOf("orphan")
    }

    test("collapses a crash-sized duplicate scene to one entity") {
        val candidates = List(21_000) { index ->
            candidate("entity-${index.toString().padStart(5, '0')}", cart, FarmContractSceneRole.CART, 10.5, 64.15, -3.5)
        }

        val plan = FarmContractSceneReconciler.plan(listOf(target), candidates)

        plan.keep shouldBe mapOf(cart to "entity-00000")
        plan.remove.size shouldBe 20_999
    }
})

private fun candidate(
    id: String,
    identity: FarmContractSceneIdentity?,
    role: FarmContractSceneRole?,
    x: Double,
    y: Double,
    z: Double,
): FarmContractSceneCandidate = FarmContractSceneCandidate(id, identity, role, "world", x, y, z)
