package ru.ruscrafting.farms.paper.farm.point

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.every
import io.mockk.mockk
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.FarmDeliverySettings
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.persistence.FarmLocationRepository
import java.nio.file.Files

class FarmPointServiceTest : FunSpec({
    test("food delivery portal defaults to receiving and accepts an exact override") {
        val delivery = mockk<FarmDeliverySettings> {
            every { world } returns "sp11"
            every { x } returns 201.5
            every { y } returns 49.0
            every { z } returns 453.5
        }
        val zone = mockk<FarmZoneSettings> {
            every { id } returns "communal_farm"
            every { this@mockk.delivery } returns delivery
        }
        val runtime = mockk<FarmRuntime> { every { settings } returns zone }
        val root = Files.createTempDirectory("arcfarms-portal-point-test")

        FarmLocationRepository(root).use { repository ->
            val service = FarmPointService({ mockk<ArcFarmsConfig>(relaxed = true) }, repository)
            service.load()
            service.resolveBase(runtime, FarmPointKind.FOOD_DELIVERY_PORTAL) shouldBe
                FarmPointPosition("sp11", 201.5, 49.0, 453.5)

            val override = FarmPointPosition("sp11", 208.5, 49.0, 457.5, 90f, 0f)
            service.save("communal_farm", FarmPointKind.FOOD_DELIVERY_PORTAL, override) {}
            service.resolveBase(runtime, FarmPointKind.FOOD_DELIVERY_PORTAL) shouldBe override
        }
    }

    test("firewood has no implicit fallback and resolves only an exact override") {
        val zone = mockk<FarmZoneSettings> { every { id } returns "communal_farm" }
        val runtime = mockk<FarmRuntime> { every { settings } returns zone }
        val root = Files.createTempDirectory("arcfarms-firewood-point-test")

        FarmLocationRepository(root).use { repository ->
            val service = FarmPointService({ mockk<ArcFarmsConfig>(relaxed = true) }, repository)
            service.load()

            service.configured("communal_farm", FarmPointKind.FIREWOOD) shouldBe null
            shouldThrow<IllegalStateException> { service.resolveBase(runtime, FarmPointKind.FIREWOOD) }

            val configured = FarmPointPosition("sp11", 190.5, 49.0, 472.5)
            service.save("communal_farm", FarmPointKind.FIREWOOD, configured) {}
            service.resolveBase(runtime, FarmPointKind.FIREWOOD) shouldBe configured
        }
    }
})
