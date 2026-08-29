package ru.ruscrafting.farms.paper.farm.perk

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
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
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
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
    private val port: WorksiteRuntimePort,
    private val points: FarmPointProvider,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val weeklyContribution: (UUID) -> Long,
    private val currentWeekStart: () -> Long,
    private val clock: () -> Long,
    private val persistAsync: () -> CompletableFuture<Unit>,
) {
    private class PerkHolder(val zoneId: String) : InventoryHolder {
        lateinit var value: Inventory
        override fun getInventory(): Inventory = value
    }

    private val zoneKey = NamespacedKey(plugin, "farm_perk_vendor_zone")
    private val offerKey = NamespacedKey(plugin, "farm_perk_offer")
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
        if (!port.hasAccess(event.player, runtime.settings.permission)) {
            port.sendChat(event.player, MessageKey.ZONE_LOCKED)
            return true
        }
        open(event.player, runtime)
        return true
    }

    fun handleClick(event: InventoryClickEvent): Boolean {
        val holder = event.view.topInventory.holder as? PerkHolder ?: return false
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return true
        if (event.rawSlot !in 0 until event.view.topInventory.size) return true
        val type = event.currentItem?.itemMeta?.persistentDataContainer
            ?.get(offerKey, PersistentDataType.STRING)
            ?.let { runCatching { FarmPerkType.valueOf(it) }.getOrNull() }
            ?: return true
        val runtime = runtimes().firstOrNull { it.settings.id == holder.zoneId } ?: return true
        when (val result = purchase(player, runtime, type)) {
            FarmPerkPurchaseUiResult.PENDING -> Unit
            is FarmPerkPurchaseUiResult.REJECTED -> showRejectedOffer(
                player,
                event.view.topInventory,
                event.rawSlot,
                runtime,
                type,
                result.name,
                result.lore,
            )
        }
        return true
    }

    fun handleDrag(event: InventoryDragEvent): Boolean {
        if (event.view.topInventory.holder !is PerkHolder) return false
        event.isCancelled = true
        return true
    }

    fun tick(runtime: FarmRuntime) {
        val now = clock()
        ensure(runtime)
        port.players(runtime.region).forEach { player ->
            if (active(player.uniqueId, FarmPerkType.SPEED, now)) {
                val current = player.getPotionEffect(PotionEffectType.SPEED)
                if (current == null || current.amplifier <= 0) {
                    player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 60, 0, true, false, false))
                }
            }
            if (active(player.uniqueId, FarmPerkType.SUSTENANCE, now) &&
                port.allowInteraction("farm-perk-sustain:${player.uniqueId}", runtime.settings.perks.sustainIntervalSeconds * 1_000L)
            ) {
                player.foodLevel = (player.foodLevel + 2).coerceAtMost(20)
                player.saturation = (player.saturation + 1.0f).coerceAtMost(20.0f)
                val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                if (player.health > 0.0) player.health = (player.health + 1.0).coerceAtMost(maxHealth)
            }
        }
    }

    fun refresh(runtime: FarmRuntime, reason: String) {
        vendorIds.remove(runtime.settings.id)?.let(Bukkit::getEntity)?.remove()
        ensure(runtime)
        debug.event("farm_perk_vendor_refreshed", "zone" to runtime.settings.id, "reason" to reason)
    }

    fun cleanup(reason: String) {
        persistenceGeneration++
        pendingPurchases.clear()
        Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }.filter(::owns).forEach(Entity::remove)
        vendorIds.clear()
        feedbackTasks.clear()
        menuRefreshTasks.clear()
        debug.event("farm_perk_vendor_cleanup", "reason" to reason)
    }

    internal fun open(player: Player, runtime: FarmRuntime) {
        feedbackTasks.remove(player.uniqueId)
        menuRefreshTasks.remove(player.uniqueId)
        val holder = PerkHolder(runtime.settings.id)
        val inventory = Bukkit.createInventory(holder, 27, locale.render(MessageKey.FARM_PERK_MENU_TITLE, player))
        holder.value = inventory
        backgroundItem()?.let { background ->
            repeat(inventory.size) { slot -> inventory.setItem(slot, background.clone()) }
        }
        val available = available(player.uniqueId)
        inventory.setItem(4, named(Material.SUNFLOWER, locale.render(
            MessageKey.FARM_PERK_BALANCE, player, mapOf("points" to locale.text(available)),
        )))
        offer(inventory, 10, player, runtime, FarmPerkType.HARVEST_AREA, Material.DIAMOND_HOE)
        offer(inventory, 12, player, runtime, FarmPerkType.SPEED, Material.RABBIT_FOOT)
        offer(inventory, 14, player, runtime, FarmPerkType.SUSTENANCE, Material.GOLDEN_CARROT)
        offer(inventory, 16, player, runtime, FarmPerkType.REWARD_BOOST, Material.EMERALD)
        player.openInventory(inventory)
        scheduleMenuRefresh(player, runtime, inventory)
    }

    private fun scheduleMenuRefresh(player: Player, runtime: FarmRuntime, inventory: Inventory) {
        val now = clock()
        val until = normalized(player.uniqueId).activeUntil.values.filter { it > now }.minOrNull()
        if (until == null) {
            menuRefreshTasks.remove(player.uniqueId)
            return
        }
        val refreshId = ++menuRefreshSequence
        menuRefreshTasks[player.uniqueId] = refreshId
        val delayTicks = ((until - now - 1L) / MILLIS_PER_TICK) + 2L
        if (!port.runLater(delayTicks) {
                if (!menuRefreshTasks.remove(player.uniqueId, refreshId)) return@runLater
                if (!player.isOnline) return@runLater
                val current = player.openInventory
                val holder = current.topInventory.holder as? PerkHolder ?: return@runLater
                if (holder.zoneId != runtime.settings.id || current.topInventory !== inventory) return@runLater
                val liveRuntime = runtimes().firstOrNull { it.settings.id == holder.zoneId } ?: return@runLater
                offer(inventory, 10, player, liveRuntime, FarmPerkType.HARVEST_AREA, Material.DIAMOND_HOE)
                offer(inventory, 12, player, liveRuntime, FarmPerkType.SPEED, Material.RABBIT_FOOT)
                offer(inventory, 14, player, liveRuntime, FarmPerkType.SUSTENANCE, Material.GOLDEN_CARROT)
                offer(inventory, 16, player, liveRuntime, FarmPerkType.REWARD_BOOST, Material.EMERALD)
                scheduleMenuRefresh(player, liveRuntime, inventory)
            }
        ) {
            menuRefreshTasks.remove(player.uniqueId, refreshId)
        }
    }

    private fun offer(
        inventory: Inventory,
        slot: Int,
        player: Player,
        runtime: FarmRuntime,
        type: FarmPerkType,
        material: Material,
    ) {
        inventory.setItem(slot, offerItem(player, runtime, type, material))
    }

    private fun offerItem(
        player: Player,
        runtime: FarmRuntime,
        type: FarmPerkType,
        material: Material,
    ): ItemStack {
        val config = offer(runtime, type)
        val now = clock()
        val until = normalized(player.uniqueId).activeUntil[type]?.takeIf { it > now }
        return named(
            material,
            locale.renderPath("perk.${type.name.lowercase()}.name", player),
            listOf(
                locale.renderPath("perk.${type.name.lowercase()}.description", player),
                locale.render(
                    MessageKey.FARM_PERK_PRICE,
                    player,
                    mapOf("price" to locale.text(config.price), "hours" to locale.text(config.durationHours)),
                ),
            ) + if (until != null) listOf(locale.render(
                MessageKey.FARM_PERK_ACTIVE,
                player,
                mapOf("hours" to locale.text(remainingHours(until, now))),
            )) else emptyList(),
        ).also { item ->
            item.editMeta { meta ->
                meta.persistentDataContainer.set(offerKey, PersistentDataType.STRING, type.name)
                meta.setEnchantmentGlintOverride(until != null)
            }
        }
    }

    private fun purchase(player: Player, runtime: FarmRuntime, type: FarmPerkType): FarmPerkPurchaseUiResult {
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
        val token = port.lifecycleToken()
        runCatching(persistAsync).getOrElse { CompletableFuture.failedFuture(it) }.whenComplete { _, failure ->
            port.runSync(token) {
                if (generation != persistenceGeneration || pendingPurchases[player.uniqueId] != purchased.state) return@runSync
                pendingPurchases.remove(player.uniqueId)
                if (failure != null) {
                    if (ledger.values[player.uniqueId] == purchased.state) {
                        ledger = FarmPerkLedgerState(ledger.values + (player.uniqueId to before))
                    }
                    port.log(Level.SEVERE, "Could not persist farm perk purchase for ${player.uniqueId}", failure)
                    if (player.isOnline) {
                        port.sendChat(player, MessageKey.FARM_PERK_SAVE_FAILED)
                        open(player, runtime)
                    }
                    return@runSync
                }
                if (player.isOnline) {
                    port.sendChat(
                        player,
                        MessageKey.FARM_PERK_PURCHASED,
                        mapOf("perk" to locale.renderPath("perk.${type.name.lowercase()}.name", player)),
                    )
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.15f)
                    open(player, runtime)
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
        inventory: Inventory,
        slot: Int,
        runtime: FarmRuntime,
        type: FarmPerkType,
        name: Component,
        lore: List<Component>,
    ) {
        val material = inventory.getItem(slot)?.type ?: return
        val error = named(material, name, lore).also { item ->
            item.editMeta { it.persistentDataContainer.set(offerKey, PersistentDataType.STRING, type.name) }
        }
        inventory.setItem(slot, error)
        val feedbackId = ++feedbackSequence
        feedbackTasks[player.uniqueId] = feedbackId
        port.runLater(FEEDBACK_TICKS) {
            if (!feedbackTasks.remove(player.uniqueId, feedbackId)) return@runLater
            if (!player.isOnline) return@runLater
            val current = player.openInventory
            val holder = current.topInventory.holder as? PerkHolder ?: return@runLater
            if (holder.zoneId != runtime.settings.id || current.topInventory !== inventory) return@runLater
            val liveRuntime = runtimes().firstOrNull { it.settings.id == holder.zoneId } ?: return@runLater
            val currentType = current.topInventory.getItem(slot)?.itemMeta?.persistentDataContainer
                ?.get(offerKey, PersistentDataType.STRING)
            if (currentType != type.name) return@runLater
            current.topInventory.setItem(slot, offerItem(player, liveRuntime, type, material))
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

    private fun offer(runtime: FarmRuntime, type: FarmPerkType): FarmPerkOfferSettings = when (type) {
        FarmPerkType.HARVEST_AREA -> runtime.settings.perks.harvestArea
        FarmPerkType.SPEED -> runtime.settings.perks.speed
        FarmPerkType.SUSTENANCE -> runtime.settings.perks.sustenance
        FarmPerkType.REWARD_BOOST -> runtime.settings.perks.rewardBoost
    }

    @Suppress("DEPRECATION")
    private fun backgroundItem(): ItemStack? {
        val background = settings().menuBackground
        if (!background.enabled) return null
        return ItemStack(MaterialRules.material(background.material)).apply {
            editMeta { meta ->
                if (background.customModelData > 0) meta.setCustomModelData(background.customModelData)
                meta.setHideTooltip(true)
            }
        }
    }

    private fun named(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack = ItemStack(material).apply {
        editMeta { meta ->
            meta.displayName(name.decoration(TextDecoration.ITALIC, false))
            meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
        }
    }

    private sealed interface FarmPerkPurchaseUiResult {
        data object PENDING : FarmPerkPurchaseUiResult
        data class REJECTED(val name: Component, val lore: List<Component>) : FarmPerkPurchaseUiResult
    }

    private companion object {
        const val FEEDBACK_TICKS = 40L
        const val MILLIS_PER_TICK = 50L
    }
}
