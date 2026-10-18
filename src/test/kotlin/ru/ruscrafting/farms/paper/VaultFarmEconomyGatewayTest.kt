package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.OfflinePlayer

class VaultFarmEconomyGatewayTest : FunSpec({
    test("deposit trusts the provider transaction result without reading a balance") {
        val player = mockk<OfflinePlayer>()
        val provider = mockk<Economy>()
        val gateway = VaultFarmEconomyGateway(provider)
        every { provider.depositPlayer(player, 2_900.0) } returns
            EconomyResponse(2_900.0, 0.123456, EconomyResponse.ResponseType.SUCCESS, "")

        gateway.deposit(player, 2_900.0) shouldBe true

        verify(exactly = 1) { provider.depositPlayer(player, 2_900.0) }
        confirmVerified(provider)
    }

    test("a provider rejection is not reported as paid") {
        val player = mockk<OfflinePlayer>()
        val provider = mockk<Economy>()
        every { provider.depositPlayer(player, 2_900.0) } returns
            EconomyResponse(2_900.0, 9_999_999.0, EconomyResponse.ResponseType.FAILURE, "declined")

        VaultFarmEconomyGateway(provider).deposit(player, 2_900.0) shouldBe false

        verify(exactly = 1) { provider.depositPlayer(player, 2_900.0) }
        confirmVerified(provider)
    }
})
