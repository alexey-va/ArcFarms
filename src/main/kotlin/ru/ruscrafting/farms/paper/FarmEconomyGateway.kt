package ru.ruscrafting.farms.paper

import net.milkbowl.vault.economy.Economy
import org.bukkit.OfflinePlayer

interface FarmEconomyGateway {
    val available: Boolean
    fun deposit(player: OfflinePlayer, amount: Double): Boolean
    fun withdraw(player: OfflinePlayer, amount: Double): Boolean
}

object NoOpFarmEconomyGateway : FarmEconomyGateway {
    override val available: Boolean = false
    override fun deposit(player: OfflinePlayer, amount: Double): Boolean = false
    override fun withdraw(player: OfflinePlayer, amount: Double): Boolean = false
}

class VaultFarmEconomyGateway(
    private val economy: Economy,
) : FarmEconomyGateway {
    override val available: Boolean = true

    override fun deposit(player: OfflinePlayer, amount: Double): Boolean =
        economy.depositPlayer(player, amount).transactionSuccess()

    override fun withdraw(player: OfflinePlayer, amount: Double): Boolean =
        economy.withdrawPlayer(player, amount).transactionSuccess()
}
