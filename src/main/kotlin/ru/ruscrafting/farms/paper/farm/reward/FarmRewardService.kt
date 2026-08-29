package ru.ruscrafting.farms.paper.farm.reward

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmRewardItem
import ru.ruscrafting.farms.domain.FarmRewardDifficulty
import ru.ruscrafting.farms.domain.FarmRewardLedger
import ru.ruscrafting.farms.domain.FarmRewardLedgerSnapshot
import ru.ruscrafting.farms.domain.FarmRewardPlanner
import ru.ruscrafting.farms.domain.FarmRewardRecipient
import ru.ruscrafting.farms.domain.PendingFarmReward
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmEconomyGateway
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import java.math.BigDecimal
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Owns durable reward planning, claim-before-delivery and every reward side effect. */
internal class FarmRewardService(
    private val plugin: Plugin,
    private val locale: ArcFarmsLocale,
    private val economy: FarmEconomyGateway,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val supervisor: RuntimeTaskSupervisor,
    private val persistAsync: () -> CompletableFuture<Unit>,
    private val operational: () -> Boolean,
    private val playerMultiplier: (UUID, FarmRuntime) -> Int = { _, _ -> 100 },
) {
    private sealed interface LedgerOperation {
        data class Enqueue(val rewards: List<PendingFarmReward>) : LedgerOperation
        data class Claim(val players: Map<UUID, Player>) : LedgerOperation
    }

    private data class ActiveOperation(
        val operation: LedgerOperation,
        val before: FarmRewardLedgerSnapshot,
        val committedRewards: List<PendingFarmReward>,
        var attempts: Int = 0,
    )

    private val ledger = FarmRewardLedger()
    private val operations = ArrayDeque<LedgerOperation>()
    private var activeOperation: ActiveOperation? = null
    private var operationGeneration = 0L

    fun replace(pending: Collection<PendingFarmReward>, claimed: Map<String, Long>) {
        operationGeneration++
        activeOperation = null
        operations.clear()
        ledger.replace(pending, claimed)
    }

    fun snapshot(): FarmRewardLedgerSnapshot = ledger.snapshot()

    fun queueCompletion(runtime: FarmRuntime, contributors: Map<UUID, Int>) {
        val order = runtime.state.orderId?.let(runtime.orders::get)
        val difficultyMultiplier = order?.let {
            FarmRewardDifficulty.multiplierPercent(it, runtime.rules, runtime.state.sequence)
        } ?: 125
        val ranked = contributors.entries.filter { it.value > 0 }.sortedWith(
            compareByDescending<Map.Entry<UUID, Int>> { it.value }.thenBy { it.key.toString() },
        )
        val planned = ranked.mapIndexedNotNull { index, (playerId, contribution) ->
            val claimKey = "${runtime.settings.id}:$playerId"
            if (ledger.isClaimed(claimKey, runtime.state.sequence)) return@mapIndexedNotNull null
            FarmRewardPlanner.plan(
                settings = runtime.settings.rewards,
                zoneId = runtime.settings.id,
                sequence = runtime.state.sequence,
                recipient = FarmRewardRecipient(
                    playerId = playerId,
                    playerName = Bukkit.getOfflinePlayer(playerId).name ?: playerId.toString(),
                    contribution = contribution,
                    rank = index + 1,
                ),
                rewardMultiplierPercent = difficultyMultiplier,
                moneyMultiplierPercent = (playerMultiplier(playerId, runtime) + runtime.state.rewardMoneyBonusPercent)
                    .coerceIn(100, 300),
            ).takeUnless { grant -> ledger.contains(grant.id) }
        }
        enqueue(LedgerOperation.Enqueue(planned))
    }

    fun deliverPending(player: Player) = deliverPending(listOf(player))

    fun deliverPending(players: Collection<Player>) {
        val online = players.filter(Player::isOnline).distinctBy(Player::getUniqueId).associateBy(Player::getUniqueId)
        if (online.isEmpty()) return
        enqueue(LedgerOperation.Claim(online))
    }

    /**
     * A lifecycle boundary must not persist an applied-but-undelivered claim.
     * Enqueued grants stay pending; claims are restored so the next runtime can
     * durably claim and deliver them again.
     */
    fun prepareForLifecycleBoundary() {
        operationGeneration++
        activeOperation?.let { active ->
            if (active.operation is LedgerOperation.Claim) {
                ledger.replace(active.before.pending, active.before.claimed)
            }
        }
        operations.filterIsInstance<LedgerOperation.Enqueue>().forEach { queued -> ledger.enqueue(queued.rewards) }
        activeOperation = null
        operations.clear()
    }

    private fun enqueue(operation: LedgerOperation) {
        val tail = operations.peekLast()
        when {
            tail is LedgerOperation.Enqueue && operation is LedgerOperation.Enqueue -> {
                operations.removeLast()
                operations.addLast(LedgerOperation.Enqueue((tail.rewards + operation.rewards).distinctBy(PendingFarmReward::id)))
            }
            tail is LedgerOperation.Claim && operation is LedgerOperation.Claim -> {
                operations.removeLast()
                operations.addLast(LedgerOperation.Claim(tail.players + operation.players))
            }
            else -> operations.addLast(operation)
        }
        drainOperations()
    }

    private fun drainOperations() {
        if (activeOperation != null || !operational()) return
        while (operations.isNotEmpty()) {
            val operation = operations.removeFirst()
            val before = ledger.snapshot()
            val committed = when (operation) {
                is LedgerOperation.Enqueue -> ledger.enqueue(operation.rewards)
                is LedgerOperation.Claim -> ledger.claim(operation.players.keys) {}.rewards
            }
            if (committed.isEmpty() && ledger.snapshot() == before) continue
            ActiveOperation(operation, before, committed).also {
                activeOperation = it
                persistActive(it)
            }
            return
        }
    }

    private fun persistActive(active: ActiveOperation) {
        if (activeOperation !== active || !operational()) return
        active.attempts++
        val generation = operationGeneration
        val token = supervisor.token()
        val future = runCatching(persistAsync).getOrElse(CompletableFuture<Unit>::failedFuture)
        future.whenComplete { _, failure ->
            supervisor.runSync(token) {
                if (generation != operationGeneration || activeOperation !== active) return@runSync
                if (failure != null) {
                    val reportFailure = active.attempts == 1 || active.attempts and (active.attempts - 1) == 0
                    if (reportFailure) {
                        plugin.logger.log(
                            Level.SEVERE,
                            "Could not persist farm reward ledger operation ${active.operation.javaClass.simpleName}; " +
                                "attempt=${active.attempts}",
                            failure,
                        )
                        active.committedRewards.forEach { reward ->
                            debug.event("farm_reward_persist_failed", "grant" to reward.id, "player" to reward.playerId)
                        }
                    }
                    val retryTicks = (20L shl active.attempts.coerceAtMost(6)).coerceAtMost(1_200L)
                    supervisor.runLater(token, retryTicks) { persistActive(active) }
                    return@runSync
                }
                activeOperation = null
                when (val operation = active.operation) {
                    is LedgerOperation.Enqueue -> {
                        active.committedRewards.forEach { reward ->
                            debug.event(
                                "farm_reward_planned",
                                "zone" to reward.zoneId,
                                "sequence" to reward.sequence,
                                "player" to reward.playerId,
                                "experience" to reward.experience,
                                "money_cents" to reward.moneyCents,
                                "items" to reward.items.size,
                                "commands" to reward.commands.size,
                                "bundles" to reward.bundleIds.joinToString(","),
                                "persisted" to true,
                            )
                        }
                        deliverPending(
                            active.committedRewards.mapNotNull { Bukkit.getPlayer(it.playerId) }
                                .filter(Player::isOnline),
                        )
                    }
                    is LedgerOperation.Claim -> active.committedRewards.forEach { reward ->
                        operation.players[reward.playerId]?.takeIf(Player::isOnline)?.let { deliverClaimed(it, reward) }
                    }
                }
                drainOperations()
            }
        }
    }

    private fun deliverClaimed(player: Player, reward: PendingFarmReward) {
        if (reward.experience > 0) player.giveExp(reward.experience)
        val moneySuccess = reward.moneyCents == 0L || runCatching {
            economy.deposit(player, reward.moneyCents / 100.0)
        }.onFailure { failure ->
            plugin.logger.log(Level.SEVERE, "Farm reward ${reward.id} economy provider failed", failure)
        }.getOrDefault(false)
        if (!moneySuccess) plugin.logger.severe("Farm reward ${reward.id} could not deposit ${reward.moneyCents} cents")

        var overflow = false
        reward.items.forEach { item ->
            val material = MaterialRules.material(item.material)
            var remaining = item.amount
            while (remaining > 0) {
                val stack = ItemStack(material, minOf(remaining, material.maxStackSize))
                remaining -= stack.amount
                player.inventory.addItem(stack).values.forEach { leftover ->
                    overflow = true
                    player.world.dropItemNaturally(player.location, leftover)
                }
            }
        }

        var successfulCommands = 0
        reward.commands.forEachIndexed { index, command ->
            val commandRoot = command.substringBefore(' ').take(64)
            val success = runCatching { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command) }
                .onFailure { failure ->
                    plugin.logger.log(Level.SEVERE, "Farm reward ${reward.id} command #$index failed", failure)
                }.getOrDefault(false)
            debug.event(
                "farm_reward_command",
                "grant" to reward.id,
                "player" to player.name,
                "index" to index,
                "root" to commandRoot,
                "success" to success,
            )
            if (success) successfulCommands++
        }

        val parts = mutableListOf<Component>()
        if (reward.experience > 0) {
            parts += locale.render(MessageKey.FARM_REWARD_EXPERIENCE, player, mapOf("amount" to locale.text(reward.experience)))
        }
        if (reward.moneyCents > 0 && moneySuccess) {
            parts += locale.render(
                MessageKey.FARM_REWARD_MONEY,
                player,
                mapOf("amount" to locale.text(BigDecimal.valueOf(reward.moneyCents, 2).stripTrailingZeros().toPlainString())),
            )
        }
        reward.bundleIds.forEach { bundleId ->
            parts += locale.render(
                MessageKey.FARM_REWARD_BUNDLE,
                player,
                mapOf("bundle" to locale.renderPath("reward.bundle.$bundleId", player)),
            )
        }
        fixedRewardItems(reward).forEach { item ->
            parts += locale.render(
                MessageKey.FARM_REWARD_ITEMS,
                player,
                mapOf(
                    "amount" to locale.text(item.amount),
                    "item" to MaterialRules.itemComponent(MaterialRules.material(item.material)),
                ),
            )
        }
        if (successfulCommands > 0) parts += locale.render(MessageKey.FARM_REWARD_SPECIAL, player)
        val summary = if (parts.isEmpty()) {
            locale.render(MessageKey.FARM_REWARD_MISSED, player)
        } else {
            Component.join(JoinConfiguration.commas(true), parts)
        }
        if (parts.isEmpty()) {
            port.sendActionBar(player, MessageKey.FARM_REWARD_MISSED)
        } else {
            port.sendActionBar(player, MessageKey.FARM_REWARD_RECEIVED, mapOf("reward" to summary))
        }
        port.sendChat(player, MessageKey.FARM_REWARD_CHAT, mapOf("reward" to summary))
        if (overflow) port.sendChat(player, MessageKey.FARM_REWARD_OVERFLOW)
        debug.event(
            "farm_reward_delivered",
            "grant" to reward.id,
            "zone" to reward.zoneId,
            "player" to player.name,
            "experience" to reward.experience,
            "money_cents" to reward.moneyCents,
            "money_success" to moneySuccess,
            "item_units" to reward.items.sumOf { it.amount },
            "commands" to reward.commands.size,
            "overflow" to overflow,
        )
    }

    private fun fixedRewardItems(reward: PendingFarmReward): List<FarmRewardItem> {
        var remaining = reward.fixedItemUnits
        return buildList {
            reward.items.forEach { item ->
                if (remaining <= 0) return@forEach
                val amount = minOf(item.amount, remaining)
                add(item.copy(amount = amount))
                remaining -= amount
            }
        }
    }
}
