package ru.ruscrafting.farms.paper.farm.perk

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.FarmPerkOfferSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmPerkLedgerState
import ru.ruscrafting.farms.domain.FarmPerkPurchaseResult
import ru.ruscrafting.farms.domain.FarmPerkType
import ru.ruscrafting.farms.domain.FarmPlayerPerks
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.purchasePerk
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.ArcFarmsMenuPlatform
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.arc.menu.MenuElementId
import ru.ruscrafting.farms.paper.FarmMenuCategory
import ru.ruscrafting.farms.paper.FarmMenuContent
import ru.ruscrafting.farms.paper.FarmMenuEntry
import ru.ruscrafting.farms.paper.FarmMenuSession
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.math.floor

/** Durable weekly-point ledger and farm-only perk NPC/UI. */
internal class FarmPerkController(
    private val plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val tasks: WorksiteTaskPort,
    private val points: FarmPointProvider,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val weeklyContribution: (UUID) -> Long,
    private val currentWeekStart: () -> Long,
    private val clock: () -> Long,
    private val persistAsync: () -> CompletableFuture<Unit>,
    private val menus: ArcFarmsMenuPlatform,
) {
    private val foodTransactions = mutableMapOf<UUID, FarmPlayerPerks>()
    private val foodShop = FarmFoodShop(locale, menus, object : FarmFoodWallet {
        override fun read(playerId: UUID) = normalized(playerId)
        override fun available(playerId: UUID) = this@FarmPerkController.available(playerId)
        override fun busy(playerId: UUID) = playerId in pendingPurchases
        override fun update(playerId: UUID, next: FarmPlayerPerks, committed: () -> Unit) {
            if (busy(playerId)) return
            val before = normalized(playerId)
            ledger = FarmPerkLedgerState(ledger.values + (playerId to next))
            pendingPurchases[playerId] = next
            foodTransactions[playerId] = before
            val generation = persistenceGeneration
            val token = tasks.lifecycleToken()
            runCatching(persistAsync).getOrElse { CompletableFuture.failedFuture(it) }.whenComplete { _, failure ->
                tasks.runSync(token) {
                    if (generation != persistenceGeneration || pendingPurchases[playerId] != next) return@runSync
                    pendingPurchases.remove(playerId)
                    foodTransactions.remove(playerId)
                    if (failure != null) {
                        ledger = FarmPerkLedgerState(ledger.values + (playerId to before))
                        state.log(Level.SEVERE, "Could not persist farm food purchase for $playerId", failure)
                        Bukkit.getPlayer(playerId)?.let { audience.sendChat(it, MessageKey.FARM_PERK_SAVE_FAILED) }
                    } else committed()
                }
            }
        }
    })
    private val zoneKey = NamespacedKey(plugin, "farm_perk_vendor_zone")
    private val vendorIds = mutableMapOf<String, UUID>()
    private val feedbackTasks = mutableMapOf<UUID, Long>()
    private val menuRefreshTasks = mutableMapOf<UUID, Long>()
    private val pendingPurchases = mutableMapOf<UUID, FarmPlayerPerks>()
    private var feedbackSequence = 0L
    private var menuRefreshSequence = 0L
    private var persistenceGeneration = 0L
    private var ledger = FarmPerkLedgerState()

    fun replace(values: Map<UUID, FarmPlayerPerks>) {
        persistenceGeneration++
        pendingPurchases.clear()
        foodTransactions.clear()
        menuRefreshTasks.clear()
        ledger = FarmPerkLedgerState(values)
    }

    fun snapshot(): Map<UUID, FarmPlayerPerks> = ledger.values

    fun active(playerId: UUID, type: FarmPerkType, now: Long = clock()): Boolean =
        normalized(playerId).activeUntil[type]?.let { it > now } == true

    fun rewardMultiplier(playerId: UUID, bonusPercent: Int): Int {
        if (!active(playerId, FarmPerkType.REWARD_BOOST)) return 100
        return 100 + bonusPercent
    }

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)

    fun ensure(runtime: FarmRuntime) {
        val existing = vendorIds[runtime.settings.id]?.let(Bukkit::getEntity) as? Villager
        if (existing?.isValid == true) return
        val point = points.resolve(runtime, FarmPointKind.PERK_VENDOR)
        val world = Bukkit.getWorld(point.world) ?: return
        val chunkX = floor(point.x).toInt() shr 4
        val chunkZ = floor(point.z).toInt() shr 4
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            vendorIds.remove(runtime.settings.id)
            return
        }
        val retained = world.getChunkAt(chunkX, chunkZ).entities.filterIsInstance<Villager>().filter { entity ->
            entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) == runtime.settings.id
        }
        if (retained.isNotEmpty()) {
            retained.drop(1).forEach(Entity::remove)
            vendorIds[runtime.settings.id] = retained.first().uniqueId
            return
        }
        Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }.filter { entity ->
            entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) == runtime.settings.id
        }.forEach(Entity::remove)
        val vendor = world.spawn(org.bukkit.Location(world, point.x, point.y, point.z, point.yaw, point.pitch), Villager::class.java) {
            it.isPersistent = false
            it.removeWhenFarAway = false
            it.isInvulnerable = true
            it.isSilent = true
            it.setAI(false)
            it.isCollidable = false
            it.profession = Villager.Profession.FARMER
            it.customName(locale.render(MessageKey.FARM_PERK_VENDOR_NAME))
            it.isCustomNameVisible = true
            it.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
        }
        vendorIds[runtime.settings.id] = vendor.uniqueId
    }

    fun interact(event: PlayerInteractEntityEvent): Boolean {
        if (!owns(event.rightClicked)) return false
        event.isCancelled = true
        val zoneId = event.rightClicked.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return true
        val runtime = runtimes().firstOrNull { it.settings.id == zoneId } ?: return true
        if (!access.hasAccess(event.player, runtime.settings.permission)) {
            audience.sendChat(event.player, MessageKey.ZONE_LOCKED)
            return true
        }
        menus.beginFlow(event.player)
        open(event.player, runtime)
        return true
    }

    fun handleClick(event: InventoryClickEvent): Boolean {
        return menus.owns(event, setOf(MENU))
    }

    fun handleDrag(event: InventoryDragEvent): Boolean = menus.owns(event, setOf(MENU))

    fun tick(runtime: FarmRuntime) {
        val now = clock()
        ensure(runtime)
        audience.players(runtime.region).forEach { player ->
            if (!access.hasAccess(player, runtime.settings.permission) || player.isDead) return@forEach
            foodShop.deliver(player)
            val perk = runtime.settings.perks
            fun effect(type: FarmPerkType, potion: PotionEffectType, amplifier: Int) {
                if (!active(player.uniqueId, type, now)) return
                val current = player.getPotionEffect(potion)
                if (current != null && (current.amplifier > amplifier ||
                        current.amplifier == amplifier && (current.isInfinite || current.duration >= perk.speedRefreshTicks))) return
                player.addPotionEffect(PotionEffect(potion, perk.speedRefreshTicks, amplifier, true, false, true))
            }
            effect(FarmPerkType.SPEED, PotionEffectType.SPEED, perk.speedAmplifier)
            effect(FarmPerkType.STRENGTH, PotionEffectType.STRENGTH, perk.strengthAmplifier)
            effect(FarmPerkType.RESISTANCE, PotionEffectType.RESISTANCE, perk.resistanceAmplifier)
            effect(FarmPerkType.FIRE_RESISTANCE, PotionEffectType.FIRE_RESISTANCE, 0)
            effect(FarmPerkType.JUMP_BOOST, PotionEffectType.JUMP_BOOST, perk.jumpAmplifier)
            effect(FarmPerkType.IRON_FARMER, PotionEffectType.STRENGTH, 2)
            effect(FarmPerkType.IRON_FARMER, PotionEffectType.RESISTANCE, 2)
            effect(FarmPerkType.SKY_COURIER, PotionEffectType.SPEED, 4)
            effect(FarmPerkType.SKY_COURIER, PotionEffectType.SLOW_FALLING, 0)
            if (active(player.uniqueId, FarmPerkType.SUSTENANCE, now) &&
                access.allowInteraction("farm-perk-sustain:${player.uniqueId}", runtime.settings.perks.sustainIntervalSeconds * 1_000L)
            ) {
                player.foodLevel = (player.foodLevel + perk.sustenanceFood).coerceAtMost(20)
                player.saturation = (player.saturation + perk.sustenanceSaturation).coerceAtMost(20.0f)
                val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                if (player.health > 0.0) player.health = (player.health + perk.sustenanceHealth).coerceAtMost(maxHealth)
            }
        }
    }

    fun refresh(runtime: FarmRuntime, reason: String) {
        vendorIds.remove(runtime.settings.id)?.let(Bukkit::getEntity)?.remove()
        ensure(runtime)
        debug.event("farm_perk_vendor_refreshed", "zone" to runtime.settings.id, "reason" to reason)
    }

    fun beforeReload() {
        // No food side effect has run for these callbacks; retain recoverable intent.
        foodTransactions.forEach { (playerId, before) ->
            ledger = FarmPerkLedgerState(ledger.values + (playerId to before))
        }
        foodTransactions.clear()
        persistenceGeneration++
        pendingPurchases.clear()
    }

    fun cleanup(reason: String) {
        beforeReload()
        Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }.filter(::owns).forEach(Entity::remove)
        vendorIds.clear()
        feedbackTasks.clear()
        menuRefreshTasks.clear()
        debug.event("farm_perk_vendor_cleanup", "reason" to reason)
    }

    internal fun open(player: Player, runtime: FarmRuntime) {
        foodShop.deliver(player)
        feedbackTasks.remove(player.uniqueId)
        menuRefreshTasks.remove(player.uniqueId)
        val session = menus.open(player, MENU, {
            val live = runtimes().firstOrNull { it.settings.id == runtime.settings.id }
            if (live == null) menus.close(player) else open(player, live)
        }) { content(player, runtime) }
        scheduleMenuRefresh(player, runtime, session)
    }

    private fun content(player: Player, runtime: FarmRuntime): FarmMenuContent = FarmMenuContent(
        title = locale.render(MessageKey.FARM_PERK_MENU_TITLE, player),
        background = menus.background(MENU),
        elements = mapOf(
            BALANCE to FarmMenuEntry(
                menus.item(
                    MENU,
                    BALANCE,
                    locale.render(MessageKey.FARM_PERK_BALANCE, player, mapOf("points" to locale.text(available(player.uniqueId)))),
                    emptyList(),
                ),
                enabled = false,
            ),
        ),
        summary = listOf(
            locale.renderPath("shop-table.balance", player) to locale.text(available(player.uniqueId)),
            locale.renderPath("shop-table.currency", player) to locale.renderPath("shop-table.farm-points", player),
            locale.renderPath("shop-table.active", player) to locale.text(normalized(player.uniqueId).activeUntil.count { it.value > clock() }),
        ),
        regions = mapOf(
            ArcFarmsMenuPlatform.PERK_OFFERS to FarmPerkType.entries.map { type -> offerEntry(player, runtime, type) } +
                foodShop.entries(player, runtime.settings.perks.food) { access.hasAccess(player, runtime.settings.permission) },
        ),
    )

    private fun scheduleMenuRefresh(player: Player, runtime: FarmRuntime, session: FarmMenuSession) {
        val now = clock()
        val until = normalized(player.uniqueId).activeUntil.values.filter { it > now }.minOrNull()
        if (until == null) {
            menuRefreshTasks.remove(player.uniqueId)
            return
        }
        val refreshId = ++menuRefreshSequence
        menuRefreshTasks[player.uniqueId] = refreshId
        val delayTicks = ((until - now - 1L) / MILLIS_PER_TICK) + 2L
        if (!tasks.runLater(delayTicks) {
                if (!menuRefreshTasks.remove(player.uniqueId, refreshId)) return@runLater
                if (!player.isOnline) return@runLater
                val current = menus.session(player) ?: return@runLater
                if (current !== session || current.menuId != MENU) return@runLater
                val liveRuntime = runtimes().firstOrNull { it.settings.id == runtime.settings.id } ?: return@runLater
                session.requestRefresh()
                scheduleMenuRefresh(player, liveRuntime, session)
            }
        ) {
            menuRefreshTasks.remove(player.uniqueId, refreshId)
        }
    }

    private fun offerEntry(
        player: Player,
        runtime: FarmRuntime,
        type: FarmPerkType,
    ): FarmMenuEntry = FarmMenuEntry(
        item = offerItem(player, runtime, type),
        catalogValue = locale.renderPath("food.price", player, mapOf("price" to locale.text(offer(runtime, type).price))),
        selected = active(player.uniqueId, type),
        category = if (type in setOf(FarmPerkType.IRON_FARMER, FarmPerkType.SKY_COURIER)) FarmMenuCategory.PREMIUM_PERK else FarmMenuCategory.PERK,
        details = listOf(
            locale.renderPath("shop-table.effect", player) to locale.renderPath("perk.${type.name.lowercase()}.description", player, descriptionValues(runtime)),
            locale.renderPath("shop-table.scope", player) to locale.renderPath("perk.${type.name.lowercase()}.detail", player, descriptionValues(runtime)),
            locale.renderPath("shop-table.price", player) to locale.renderPath("food.price", player, mapOf("price" to locale.text(offer(runtime, type).price))),
            locale.renderPath("shop-table.duration", player) to locale.renderPath("shop-table.hours", player, mapOf("hours" to locale.text(offer(runtime, type).durationHours))),
            locale.renderPath("shop-table.state", player) to if (active(player.uniqueId, type)) {
                locale.render(MessageKey.FARM_PERK_ACTIVE, player, mapOf("hours" to locale.text(remainingHours(normalized(player.uniqueId).activeUntil.getValue(type), clock()))))
            } else if (available(player.uniqueId) < offer(runtime, type).price) locale.renderPath("food.not-enough", player)
            else locale.renderPath("shop-table.available", player),
        ),
        enabled = normalized(player.uniqueId).activeUntil[type]?.let { it <= clock() } != false &&
            available(player.uniqueId) >= offer(runtime, type).price,
        acceptedClicks = setOf(ClickType.LEFT),
        onClick = { context ->
            when (val result = purchase(player, runtime, type, context.session)) {
                FarmPerkPurchaseUiResult.PENDING -> Unit
                is FarmPerkPurchaseUiResult.REJECTED -> showRejectedOffer(
                    player, context.session, context.slot, type, result.name, result.lore,
                )
            }
        },
    )

    private fun offerItem(
        player: Player,
        runtime: FarmRuntime,
        type: FarmPerkType,
    ): ItemStack {
        val config = offer(runtime, type)
        val now = clock()
        val until = normalized(player.uniqueId).activeUntil[type]?.takeIf { it > now }
        val canBuy = until == null && available(player.uniqueId) >= config.price
        return menus.item(
            template(type),
            locale.renderPath("perk.${type.name.lowercase()}.name", player),
            buildList {
                add(locale.renderPath("perk.${type.name.lowercase()}.description", player, descriptionValues(runtime)))
                add(locale.renderPath("perk.${type.name.lowercase()}.detail", player, descriptionValues(runtime)))
                add(Component.empty())
                add(
                    locale.render(
                        MessageKey.FARM_PERK_PRICE,
                        player,
                        mapOf("price" to locale.text(config.price), "hours" to locale.text(config.durationHours)),
                    ),
                )
                when {
                    until != null -> add(
                        locale.render(
                            MessageKey.FARM_PERK_ACTIVE,
                            player,
                            mapOf("hours" to locale.text(remainingHours(until, now))),
                        ),
                    )
                    !canBuy -> add(
                        locale.render(
                            MessageKey.FARM_PERK_NOT_ENOUGH,
                            player,
                            mapOf("price" to locale.text(config.price)),
                        ),
                    )
                    else -> {
                        add(Component.empty())
                        add(locale.render(MessageKey.FARM_PERK_BUY, player))
                    }
                }
            },
        ).also { item ->
            item.editMeta { meta ->
                meta.setEnchantmentGlintOverride(until != null)
            }
        }
    }

    private fun purchase(
        player: Player,
        runtime: FarmRuntime,
        type: FarmPerkType,
        expectedSession: FarmMenuSession,
    ): FarmPerkPurchaseUiResult {
        if (player.uniqueId in pendingPurchases) {
            return FarmPerkPurchaseUiResult.REJECTED(
                locale.render(MessageKey.FARM_PERK_SAVE_FAILED_TITLE, player),
                listOf(locale.render(MessageKey.FARM_PERK_SAVE_FAILED, player)),
            )
        }
        val config = offer(runtime, type)
        val before = normalized(player.uniqueId)
        val now = clock()
        val decision = before.purchasePerk(
            type = type,
            price = config.price,
            durationMillis = TimeUnit.HOURS.toMillis(config.durationHours.toLong()),
            weeklyContribution = weeklyContribution(player.uniqueId),
            now = now,
        )
        if (decision is FarmPerkPurchaseResult.AlreadyActive) {
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f)
            return FarmPerkPurchaseUiResult.REJECTED(
                locale.render(MessageKey.FARM_PERK_ALREADY_ACTIVE_TITLE, player),
                listOf(
                    locale.render(
                        MessageKey.FARM_PERK_ALREADY_ACTIVE,
                        player,
                        mapOf("hours" to locale.text(remainingHours(decision.activeUntil, now))),
                    ),
                    locale.render(MessageKey.FARM_PERK_ALREADY_ACTIVE_HINT, player),
                ),
            )
        }
        if (decision is FarmPerkPurchaseResult.InsufficientPoints) {
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f)
            return FarmPerkPurchaseUiResult.REJECTED(
                locale.render(MessageKey.FARM_PERK_NOT_ENOUGH_TITLE, player),
                listOf(locale.render(
                    MessageKey.FARM_PERK_NOT_ENOUGH,
                    player,
                    mapOf("price" to locale.text(decision.required)),
                )),
            )
        }
        val purchased = decision as FarmPerkPurchaseResult.Purchased
        ledger = FarmPerkLedgerState(ledger.values + (player.uniqueId to purchased.state))
        pendingPurchases[player.uniqueId] = purchased.state
        val generation = persistenceGeneration
        val token = tasks.lifecycleToken()
        runCatching(persistAsync).getOrElse { CompletableFuture.failedFuture(it) }.whenComplete { _, failure ->
            tasks.runSync(token) {
                if (generation != persistenceGeneration || pendingPurchases[player.uniqueId] != purchased.state) return@runSync
                pendingPurchases.remove(player.uniqueId)
                if (failure != null) {
                    if (ledger.values[player.uniqueId] == purchased.state) {
                        ledger = FarmPerkLedgerState(ledger.values + (player.uniqueId to before))
                    }
                    state.log(Level.SEVERE, "Could not persist farm perk purchase for ${player.uniqueId}", failure)
                    if (player.isOnline) {
                        audience.sendChat(player, MessageKey.FARM_PERK_SAVE_FAILED)
                        menus.transition(player, expectedSession) { open(player, runtime) }
                    }
                    return@runSync
                }
                if (player.isOnline) {
                    audience.sendChat(
                        player,
                        MessageKey.FARM_PERK_PURCHASED,
                        mapOf("perk" to locale.renderPath("perk.${type.name.lowercase()}.name", player)),
                    )
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.15f)
                    menus.transition(player, expectedSession) { open(player, runtime) }
                }
                debug.event(
                    "farm_perk_purchased", "zone" to runtime.settings.id, "player" to player.name,
                    "perk" to type, "price" to config.price,
                )
            }
        }
        return FarmPerkPurchaseUiResult.PENDING
    }

    private fun showRejectedOffer(
        player: Player,
        session: FarmMenuSession,
        slot: Int,
        type: FarmPerkType,
        name: Component,
        lore: List<Component>,
    ) {
        val item = menus.item(template(type), name, lore)
        session.feedback(slot, item)
        val feedbackId = ++feedbackSequence
        feedbackTasks[player.uniqueId] = feedbackId
        tasks.runLater(FEEDBACK_TICKS) {
            if (!feedbackTasks.remove(player.uniqueId, feedbackId)) return@runLater
            if (!player.isOnline) return@runLater
            val current = menus.session(player) ?: return@runLater
            if (current !== session || current.menuId != MENU) return@runLater
            current.requestRefresh()
        }
    }

    private fun available(playerId: UUID): Long = (weeklyContribution(playerId) - normalized(playerId).spentPoints).coerceAtLeast(0)

    private fun remainingHours(until: Long, now: Long): Long =
        if (until <= now) 1 else ((until - now - 1) / TimeUnit.HOURS.toMillis(1)) + 1

    private fun normalized(playerId: UUID): FarmPlayerPerks {
        val week = currentWeekStart()
        val current = ledger.values[playerId]
        return current?.forWeek(week, clock()) ?: FarmPlayerPerks(weekStartEpochDay = week)
    }

    private fun descriptionValues(runtime: FarmRuntime): Map<String, Component> = with(runtime.settings.perks) {
        mapOf(
            "width" to locale.text(harvestAreaRadius * 2 + 1),
            "speed" to locale.text((speedAmplifier + 1) * 20),
            "hearts" to locale.text(sustenanceHealth / 2.0),
            "food" to locale.text(sustenanceFood / 2.0),
            "seconds" to locale.text(sustainIntervalSeconds),
            "bonus" to locale.text(rewardBonusPercent),
            "strength" to locale.text(strengthAmplifier + 1),
            "resistance" to locale.text((resistanceAmplifier + 1) * 20),
            "jump" to locale.text(jumpAmplifier + 1),
        )
    }

    private fun offer(runtime: FarmRuntime, type: FarmPerkType): FarmPerkOfferSettings = when (type) {
        FarmPerkType.HARVEST_AREA -> runtime.settings.perks.harvestArea
        FarmPerkType.SPEED -> runtime.settings.perks.speed
        FarmPerkType.SUSTENANCE -> runtime.settings.perks.sustenance
        FarmPerkType.REWARD_BOOST -> runtime.settings.perks.rewardBoost
        FarmPerkType.STRENGTH -> runtime.settings.perks.strength
        FarmPerkType.RESISTANCE -> runtime.settings.perks.resistance
        FarmPerkType.FIRE_RESISTANCE -> runtime.settings.perks.fireResistance
        FarmPerkType.JUMP_BOOST -> runtime.settings.perks.jumpBoost
        FarmPerkType.IRON_FARMER -> runtime.settings.perks.ironFarmer
        FarmPerkType.SKY_COURIER -> runtime.settings.perks.skyCourier
    }

    private fun template(type: FarmPerkType): String = "perk-${type.name.lowercase().replace('_', '-')}"

    private sealed interface FarmPerkPurchaseUiResult {
        data object PENDING : FarmPerkPurchaseUiResult
        data class REJECTED(val name: Component, val lore: List<Component>) : FarmPerkPurchaseUiResult
    }

    private companion object {
        val MENU = ArcFarmsMenuPlatform.FARM_PERKS
        val BALANCE = MenuElementId.of("balance")
        const val FEEDBACK_TICKS = 40L
        const val MILLIS_PER_TICK = 50L
    }
}
