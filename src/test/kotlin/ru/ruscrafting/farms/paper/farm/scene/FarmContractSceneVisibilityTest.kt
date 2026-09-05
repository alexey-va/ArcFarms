package ru.ruscrafting.farms.paper.farm.scene

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.paper.FarmContractSceneRole

class FarmContractSceneVisibilityTest : FunSpec({
    test("enterprise milestone badge uses bounded vanilla materials") {
        FarmEnterpriseBadge.material(0) shouldBe null
        FarmEnterpriseBadge.material(1) shouldBe "WHEAT"
        FarmEnterpriseBadge.material(2) shouldBe "HAY_BLOCK"
        FarmEnterpriseBadge.material(3) shouldBe "GOLDEN_HOE"
        FarmEnterpriseBadge.material(4) shouldBe null
    }
    test("the decorative contract cart is hidden for both moving raid incidents") {
        val expected = listOf(
            FarmContractSceneRole.CART,
            FarmContractSceneRole.CART_INTERACTION,
            FarmContractSceneRole.CART_LOAD,
        )

        FarmContractSceneVisibility.hiddenRoles(FarmPhase.INCIDENT, FarmIncidentType.FOOD_DELIVERY)
            .shouldContainExactlyInAnyOrder(expected)
        FarmContractSceneVisibility.hiddenRoles(FarmPhase.INCIDENT, FarmIncidentType.RIVAL_RAID)
            .shouldContainExactlyInAnyOrder(expected)
        FarmContractSceneVisibility.hiddenRoles(FarmPhase.HARVESTING, FarmIncidentType.RIVAL_RAID).shouldBeEmpty()
    }
})
