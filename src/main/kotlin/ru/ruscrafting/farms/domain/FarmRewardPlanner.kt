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
    ): PendingFarmReward {
        val grantId = "$zoneId:$sequence:${recipient.playerId}"
        val items = mutableListOf<FarmRewardItem>()
        val bundleIds = mutableListOf<String>()
        var fixedItemUnits = 0

        settings.items.forEach { reward ->
            if (passes(grantId, "item:${reward.id}", reward.chancePercent)) {
                items += FarmRewardItem(reward.material, reward.amount)
                fixedItemUnits += reward.amount
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
                bundle.items.mapTo(items) { item -> FarmRewardItem(item.material, item.amount) }
            }
        }

        val commands = settings.commands.filter { reward ->
            passes(grantId, "command:${reward.id}", reward.chancePercent)
        }.map { reward -> expandCommand(reward.command, grantId, zoneId, sequence, recipient) }

        return PendingFarmReward(
            id = grantId,
            zoneId = zoneId,
            sequence = sequence,
            playerId = recipient.playerId,
            contribution = recipient.contribution,
            experience = settings.experience.amount.takeIf {
                passes(grantId, "experience", settings.experience.chancePercent)
            } ?: 0,
            moneyCents = settings.money.amountCents.takeIf {
                passes(grantId, "money", settings.money.chancePercent)
            } ?: 0,
            items = items,
            fixedItemUnits = fixedItemUnits,
            commands = commands,
            bundleIds = bundleIds,
        )
    }

    private fun expandCommand(
        template: String,
        grantId: String,
        zoneId: String,
        sequence: Long,
        recipient: FarmRewardRecipient,
    ): String = template
        .replace("%player%", recipient.playerName)
        .replace("%uuid%", recipient.playerId.toString())
        .replace("%zone%", zoneId)
        .replace("%sequence%", sequence.toString())
        .replace("%contribution%", recipient.contribution.toString())
        .replace("%rank%", recipient.rank.toString())
        .replace("%grant_id%", grantId)

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
}
