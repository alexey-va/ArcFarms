package ru.ruscrafting.farms.domain

import ru.ruscrafting.farms.config.FarmRewardSettings
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class FarmRewardRecipient(
    val playerId: UUID,
    val playerName: String,
    val contribution: Int,
    val rank: Int,
)

object FarmRewardPlanner {
    fun plan(
        settings: FarmRewardSettings,
        zoneId: String,
        sequence: Long,
        recipient: FarmRewardRecipient,
        rewardMultiplierPercent: Int = 100,
        moneyMultiplierPercent: Int = 100,
    ): PendingFarmReward {
        require(rewardMultiplierPercent in 100..300) { "Farm reward multiplier is invalid" }
        require(moneyMultiplierPercent in 100..300) { "Farm money reward multiplier is invalid" }
        require(recipient.contribution > 0) { "Farm rewards require positive player contribution" }
        require(recipient.rank > 0) { "Farm reward rank must be positive" }
        val grantId = "$zoneId:$sequence:${recipient.playerId}"
        val items = mutableListOf<FarmRewardItem>()
        val bundleIds = mutableListOf<String>()
        var fixedItemUnits = 0

        settings.items.forEach { reward ->
            if (passes(grantId, "item:${reward.id}", reward.chancePercent)) {
                val amount = scaledInt(reward.amount, rewardMultiplierPercent)
                items += FarmRewardItem(reward.material, amount)
                fixedItemUnits += amount
            }
        }

        val pool = settings.randomBundles
        repeat(pool.rolls) { roll ->
            if (passes(grantId, "bundle:$roll", pool.chancePercent)) {
                val selected = weighted(pool.entries.sumOf { it.weight }, grantId, "bundle-choice:$roll") { index ->
                    pool.entries[index].weight
                }
                val bundle = pool.entries[selected]
                bundleIds += bundle.id
                bundle.items.mapTo(items) { item ->
                    FarmRewardItem(item.material, scaledInt(item.amount, rewardMultiplierPercent))
                }
            }
        }

        val commands = settings.commands.filter { reward ->
            passes(grantId, "command:${reward.id}", reward.chancePercent)
        }.map { reward ->
            TrustedFarmCommandTemplate.parse(reward.command).resolve(
                FarmRewardCommandContext(
                    playerName = recipient.playerName,
                    playerId = recipient.playerId,
                    zoneId = zoneId,
                    sequence = sequence,
                    contribution = recipient.contribution,
                    rank = recipient.rank,
                    grantId = grantId,
                ),
            ).value
        }

        return PendingFarmReward(
            id = grantId,
            zoneId = zoneId,
            sequence = sequence,
            playerId = recipient.playerId,
            contribution = recipient.contribution,
            experience = scaledInt(settings.experience.amount, rewardMultiplierPercent).takeIf {
                passes(grantId, "experience", settings.experience.chancePercent)
            } ?: 0,
            moneyCents = scaledMoney(
                scaledMoney(settings.money.amountCents, rewardMultiplierPercent),
                moneyMultiplierPercent,
            ).takeIf {
                passes(grantId, "money", settings.money.chancePercent)
            } ?: 0,
            items = items,
            fixedItemUnits = fixedItemUnits,
            commands = commands,
            bundleIds = bundleIds,
        )
    }

    private fun passes(grantId: String, key: String, chancePercent: Int): Boolean = when (chancePercent) {
        0 -> false
        100 -> true
        else -> unsigned(hash(grantId, key)) % 100L < chancePercent
    }

    private fun weighted(totalWeight: Int, grantId: String, key: String, weightAt: (Int) -> Int): Int {
        var target = (unsigned(hash(grantId, key)) % totalWeight.toLong()).toInt()
        var index = 0
        while (true) {
            val weight = weightAt(index)
            if (target < weight) return index
            target -= weight
            index++
        }
    }

    private fun hash(grantId: String, key: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$grantId|$key".toByteArray(StandardCharsets.UTF_8))
        return ByteBuffer.wrap(digest).long
    }

    private fun unsigned(value: Long): Long = value and Long.MAX_VALUE

    private fun scaledMoney(amountCents: Long, multiplierPercent: Int): Long =
        Math.multiplyExact(amountCents, multiplierPercent.toLong()) / 100L

    private fun scaledInt(amount: Int, multiplierPercent: Int): Int =
        (Math.multiplyExact(amount.toLong(), multiplierPercent.toLong()) / 100L).toInt()
}
