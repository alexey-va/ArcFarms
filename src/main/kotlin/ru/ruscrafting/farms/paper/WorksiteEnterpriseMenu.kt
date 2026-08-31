@file:Suppress("DEPRECATION")

package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MenuItemVisualSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseCapitalPhase
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseCompanyView
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseOwnershipView
import ru.ruscrafting.farms.paper.enterprise.EnterpriseInvestmentActionResult
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Farm-company report, primary funding and durable investment-account UI. */
internal class WorksiteEnterpriseMenu(
    private val service: ArcFarmsService,
    private val locale: ArcFarmsLocale,
    private val settings: () -> ArcFarmsConfig,
    private val openRoot: (Player) -> Unit,
) {
    private enum class Screen { OVERVIEW, FARM, SHARES, PURCHASE_CONFIRM }

    private inner class Holder(val screen: Screen, val purchaseShares: Int = 0) : ArcFarmsReloadableInventory {
        lateinit var backing: Inventory
        override fun getInventory(): Inventory = backing
        override fun refresh(player: Player) = when (screen) {
            Screen.OVERVIEW -> openOverview(player)
            Screen.FARM -> openFarm(player)
            Screen.SHARES -> openShares(player)
            Screen.PURCHASE_CONFIRM -> openPurchaseConfirmation(player, purchaseShares)
        }
    }

    fun openOverview(player: Player) {
        val current = settings()
        val visuals = current.enterpriseMenuItems
        val inventory = inventory(player, 27, MessageKey.COMPANIES_TITLE, Screen.OVERVIEW)
        inventory.setItem(2, farmCard(player, visuals.overviewFarm))
        inventory.setItem(4, unavailableCard(player, visuals.overviewLumber, MessageKey.COMPANIES_LUMBER_NAME, MessageKey.COMPANIES_LUMBER_LORE))
        inventory.setItem(6, unavailableCard(player, visuals.overviewMine, MessageKey.COMPANIES_MINE_NAME, MessageKey.COMPANIES_MINE_LORE))
        inventory.setItem(18, backItem(player, current.menuBack))
        fillBackground(inventory, current)
        player.openInventory(inventory)
    }

    fun onClick(event: InventoryClickEvent): Boolean {
        val holder = event.view.topInventory.holder as? Holder ?: return false
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory) return true
        if (event.click != ClickType.LEFT) return true
        val player = event.whoClicked as? Player ?: return true
        val expectedTop = event.view.topInventory
        when (holder.screen) {
            Screen.OVERVIEW -> when (event.rawSlot) {
                2 -> service.deferInventoryTransition(player, expectedTop) { openFarmOrTravel(player) }
                18 -> service.deferInventoryTransition(player, expectedTop) { openRoot(player) }
            }
            Screen.FARM -> if (event.rawSlot == 36) {
                service.deferInventoryTransition(player, expectedTop) { openOverview(player) }
            } else if (event.rawSlot == 28 && service.enterpriseOwnership(ActivityKind.FARM, player.uniqueId) != null) {
                service.deferInventoryTransition(player, expectedTop) { openShares(player) }
            }
            Screen.SHARES -> when {
                event.rawSlot == 36 -> service.deferInventoryTransition(player, expectedTop) { openFarm(player) }
                event.rawSlot == 32 -> service.deferInventoryTransition(player, expectedTop) { withdraw(player) }
                event.rawSlot in BUY_SLOTS -> {
                    val index = BUY_SLOTS.indexOf(event.rawSlot)
                    val shares = settings().enterprises.getValue(ActivityKind.FARM).capital.purchaseOptions.getOrNull(index)
                    val ownership = service.enterpriseOwnership(ActivityKind.FARM, player.uniqueId)
                    if (shares != null && ownership != null && canBuy(ownership, shares)) {
                        service.deferInventoryTransition(player, expectedTop) { openPurchaseConfirmation(player, shares) }
                    }
                }
            }
            Screen.PURCHASE_CONFIRM -> when (event.rawSlot) {
                13 -> {
                    val options = settings().enterprises.getValue(ActivityKind.FARM).capital.purchaseOptions
                    val ownership = service.enterpriseOwnership(ActivityKind.FARM, player.uniqueId)
                    if (holder.purchaseShares in options && ownership != null && canBuy(ownership, holder.purchaseShares)) {
                        service.deferInventoryTransition(player, expectedTop) { buy(player, holder.purchaseShares) }
                    }
                }
                18 -> service.deferInventoryTransition(player, expectedTop) { openShares(player) }
            }
        }
        return true
    }

    fun onDrag(event: InventoryDragEvent): Boolean {
        if (event.view.topInventory.holder !is Holder) return false
        event.isCancelled = true
        return true
    }

    private fun openFarmOrTravel(player: Player) {
        if (service.enterpriseCompany(ActivityKind.FARM) != null) {
            openFarm(player)
            return
        }
        if (!service.isAvailable(ActivityKind.FARM) && service.canNavigate(ActivityKind.FARM) &&
            service.canAccess(player, ActivityKind.FARM)
        ) {
            player.closeInventory()
            service.travel(player, ActivityKind.FARM)
        }
    }

    private fun openFarm(player: Player) {
        val view = service.enterpriseCompany(ActivityKind.FARM) ?: run {
            openOverview(player)
            return
        }
        val current = settings()
        val visuals = current.enterpriseMenuItems
        val ownership = service.enterpriseOwnership(ActivityKind.FARM, player.uniqueId)
        val inventory = inventory(player, 45, MessageKey.COMPANY_FARM_TITLE, Screen.FARM)
        inventory.setItem(
            4,
            item(
                visuals.farmHeader,
                locale.render(MessageKey.COMPANY_FARM_HEADER_NAME, player),
                listOf(
                    locale.render(MessageKey.COMPANY_FARM_HEADER_LORE, player),
                    Component.empty(),
                    companyStatus(player, ownership),
                ),
            ),
        )
        inventory.setItem(10, reportItem(player, view, visuals.report))
        inventory.setItem(12, workersItem(player, view, visuals.workers))
        inventory.setItem(14, policyItem(player, view, visuals.policy))
        inventory.setItem(16, licenseItem(player, view, visuals.license))
        val sharesLore = if (ownership == null) {
            listOf(locale.render(MessageKey.COMPANY_SHARES_LORE, player))
        } else {
            listOf(
                locale.render(MessageKey.COMPANY_SHARES_LIVE_LORE, player),
                Component.empty(),
                locale.render(MessageKey.COMPANY_SHARES_OPEN, player),
            )
        }
        inventory.setItem(28, item(visuals.shares, locale.render(MessageKey.COMPANY_SHARES_NAME, player), sharesLore))
        inventory.setItem(
            30,
            item(
                visuals.market,
                locale.render(MessageKey.COMPANY_MARKET_NAME, player),
                listOf(locale.render(MessageKey.COMPANY_MARKET_LORE, player)),
            ),
        )
        inventory.setItem(36, backItem(player, current.menuBack))
        fillBackground(inventory, current)
        player.openInventory(inventory)
    }

    private fun openShares(player: Player) {
        val view = service.enterpriseOwnership(ActivityKind.FARM, player.uniqueId) ?: run {
            openFarm(player)
            return
        }
        val current = settings()
        val visuals = current.enterpriseMenuItems
        val inventory = inventory(player, 45, MessageKey.COMPANY_SHARES_TITLE, Screen.SHARES)
        val statusLore = mutableListOf(
            locale.render(MessageKey.COMPANY_SHARES_STATUS_PHASE, player, mapOf("phase" to phase(player, view.phase))),
            locale.render(
                MessageKey.COMPANY_SHARES_STATUS_PROGRESS,
                player,
                mapOf(
                    "issued" to locale.text(view.issuedShares),
                    "total" to locale.text(view.totalShares),
                    "reserved" to locale.text(view.reservedShares),
                ),
            ),
            locale.render(MessageKey.COMPANY_SHARES_STATUS_PRICE, player, mapOf("amount" to money(view.sharePriceCents))),
        )
        if (view.phase == WorksiteEnterpriseCapitalPhase.FUNDING) {
            statusLore += locale.render(
                MessageKey.COMPANY_SHARES_STATUS_DEADLINE,
                player,
                mapOf("hours" to locale.text(remainingHours(view.fundingClosesAt))),
            )
        }
        inventory.setItem(
            10,
            item(visuals.shareStatus, locale.render(MessageKey.COMPANY_SHARES_STATUS_NAME, player), statusLore),
        )
        inventory.setItem(
            12,
            item(
                visuals.shareHolding,
                locale.render(MessageKey.COMPANY_SHARES_HOLDING_NAME, player),
                listOf(
                    locale.render(
                        MessageKey.COMPANY_SHARES_HOLDING_AMOUNT,
                        player,
                        mapOf("shares" to locale.text(view.ownedShares), "total" to locale.text(view.totalShares)),
                    ),
                    locale.render(
                        MessageKey.COMPANY_SHARES_HOLDING_LIMIT,
                        player,
                        mapOf("limit" to locale.text(view.maxSharesPerOwner)),
                    ),
                ),
            ),
        )
        val accountLore = mutableListOf(
            locale.render(MessageKey.COMPANY_SHARES_ACCOUNT_BALANCE, player, mapOf("amount" to money(view.accountBalanceCents))),
            locale.render(MessageKey.COMPANY_SHARES_ACCOUNT_AVAILABLE, player, mapOf("amount" to money(view.accountAvailableCents))),
        )
        if (view.pendingManualReviewCount > 0) {
            accountLore += locale.render(
                MessageKey.COMPANY_SHARES_ACCOUNT_REVIEW,
                player,
                mapOf("count" to locale.text(view.pendingManualReviewCount)),
            )
        }
        inventory.setItem(14, item(visuals.shareAccount, locale.render(MessageKey.COMPANY_SHARES_ACCOUNT_NAME, player), accountLore))
        current.enterprises.getValue(ActivityKind.FARM).capital.purchaseOptions.forEachIndexed { index, shares ->
            inventory.setItem(BUY_SLOTS[index], buyItem(player, view, shares, visuals.shareBuy))
        }
        val withdrawLore = if (view.accountAvailableCents > 0L) {
            listOf(
                locale.render(MessageKey.COMPANY_SHARES_WITHDRAW_AMOUNT, player, mapOf("amount" to money(view.accountAvailableCents))),
                Component.empty(),
                locale.render(MessageKey.COMPANY_SHARES_WITHDRAW_CLICK, player),
            )
        } else {
            listOf(locale.render(MessageKey.COMPANY_SHARES_WITHDRAW_EMPTY, player))
        }
        inventory.setItem(
            32,
            item(visuals.shareWithdraw, locale.render(MessageKey.COMPANY_SHARES_WITHDRAW_NAME, player), withdrawLore),
        )
        inventory.setItem(36, backItem(player, current.menuBack))
        fillBackground(inventory, current)
        player.openInventory(inventory)
    }

    private fun openPurchaseConfirmation(player: Player, shares: Int) {
        val view = service.enterpriseOwnership(ActivityKind.FARM, player.uniqueId)
        if (view == null || !canBuy(view, shares)) {
            openShares(player)
            return
        }
        val current = settings()
        val inventory = inventory(player, 27, MessageKey.COMPANY_SHARES_CONFIRM_TITLE, Screen.PURCHASE_CONFIRM, shares)
        val amount = Math.multiplyExact(view.sharePriceCents, shares.toLong())
        inventory.setItem(
            13,
            item(
                current.enterpriseMenuItems.shareConfirm,
                locale.render(MessageKey.COMPANY_SHARES_CONFIRM_NAME, player, mapOf("shares" to locale.text(shares))),
                listOf(
                    locale.render(MessageKey.COMPANY_SHARES_CONFIRM_COST, player, mapOf("amount" to money(amount))),
                    locale.render(
                        MessageKey.COMPANY_SHARES_CONFIRM_EFFECT,
                        player,
                        mapOf("shares" to locale.text(view.ownedShares + shares), "limit" to locale.text(view.maxSharesPerOwner)),
                    ),
                    Component.empty(),
                    locale.render(MessageKey.COMPANY_SHARES_CONFIRM_WARNING, player),
                    Component.empty(),
                    locale.render(MessageKey.COMPANY_SHARES_CONFIRM_CLICK, player),
                ),
            ).also { it.amount = shares.coerceAtMost(it.maxStackSize) },
        )
        inventory.setItem(18, backItem(player, current.menuBack))
        fillBackground(inventory, current)
        player.openInventory(inventory)
    }

    private fun buyItem(
        player: Player,
        view: WorksiteEnterpriseOwnershipView,
        shares: Int,
        visual: MenuItemVisualSettings,
    ): ItemStack {
        val lore = if (canBuy(view, shares)) {
            listOf(
                locale.render(
                    MessageKey.COMPANY_SHARES_BUY_COST,
                    player,
                    mapOf("amount" to money(Math.multiplyExact(view.sharePriceCents, shares.toLong()))),
                ),
                locale.render(
                    MessageKey.COMPANY_SHARES_BUY_EFFECT,
                    player,
                    mapOf("shares" to locale.text(view.ownedShares + shares), "limit" to locale.text(view.maxSharesPerOwner)),
                ),
                Component.empty(),
                locale.render(MessageKey.COMPANY_SHARES_BUY_CLICK, player),
            )
        } else {
            listOf(locale.render(MessageKey.COMPANY_SHARES_BUY_UNAVAILABLE, player))
        }
        return item(
            visual,
            locale.render(MessageKey.COMPANY_SHARES_BUY_NAME, player, mapOf("shares" to locale.text(shares))),
            lore,
        ).also { it.amount = shares.coerceAtMost(it.maxStackSize) }
    }

    private fun buy(player: Player, shares: Int) {
        val result = service.buyFarmShares(player, shares) { completed ->
            notifyInvestment(player, completed, withdrawal = false)
            refreshSharesIfOpen(player)
        }
        notifyInvestment(player, result, withdrawal = false)
        openShares(player)
    }

    private fun withdraw(player: Player) {
        val result = service.withdrawFarmInvestment(player) { completed ->
            notifyInvestment(player, completed, withdrawal = true)
            refreshSharesIfOpen(player)
        }
        notifyInvestment(player, result, withdrawal = true)
        openShares(player)
    }

    private fun notifyInvestment(player: Player, result: EnterpriseInvestmentActionResult, withdrawal: Boolean) {
        val key = when (result) {
            EnterpriseInvestmentActionResult.STARTED -> MessageKey.COMPANY_INVESTMENT_STARTED
            EnterpriseInvestmentActionResult.SUCCESS -> if (withdrawal) {
                MessageKey.COMPANY_INVESTMENT_WITHDRAW_SUCCESS
            } else MessageKey.COMPANY_INVESTMENT_BUY_SUCCESS
            EnterpriseInvestmentActionResult.PROVIDER_REJECTED -> MessageKey.COMPANY_INVESTMENT_PROVIDER_REJECTED
            EnterpriseInvestmentActionResult.MANUAL_REVIEW -> MessageKey.COMPANY_INVESTMENT_MANUAL_REVIEW
            EnterpriseInvestmentActionResult.STATE_ERROR -> MessageKey.COMPANY_INVESTMENT_STATE_ERROR
            EnterpriseInvestmentActionResult.FUNDING_CLOSED -> MessageKey.COMPANY_INVESTMENT_FUNDING_CLOSED
            EnterpriseInvestmentActionResult.OWNER_LIMIT -> MessageKey.COMPANY_INVESTMENT_OWNER_LIMIT
            EnterpriseInvestmentActionResult.SOLD_OUT -> MessageKey.COMPANY_INVESTMENT_SOLD_OUT
            EnterpriseInvestmentActionResult.ALREADY_PENDING -> MessageKey.COMPANY_INVESTMENT_PENDING
            EnterpriseInvestmentActionResult.NO_CREDIT -> MessageKey.COMPANY_INVESTMENT_NO_CREDIT
            EnterpriseInvestmentActionResult.NOT_AVAILABLE -> MessageKey.COMPANY_INVESTMENT_NOT_AVAILABLE
        }
        if (player.isOnline) player.sendMessage(locale.render(key, player))
    }

    private fun refreshSharesIfOpen(player: Player) {
        if (!player.isOnline) return
        val holder = player.openInventory.topInventory.holder as? Holder ?: return
        if (holder.screen == Screen.SHARES || holder.screen == Screen.PURCHASE_CONFIRM) openShares(player)
    }

    private fun canBuy(view: WorksiteEnterpriseOwnershipView, shares: Int): Boolean =
        view.phase == WorksiteEnterpriseCapitalPhase.FUNDING &&
            view.ownedShares + shares <= view.maxSharesPerOwner &&
            view.issuedShares + view.reservedShares + shares <= view.totalShares

    private fun companyStatus(player: Player, ownership: WorksiteEnterpriseOwnershipView?): Component = when (ownership?.phase) {
        null -> locale.render(MessageKey.COMPANIES_SHADOW, player)
        WorksiteEnterpriseCapitalPhase.FUNDING -> locale.render(
            MessageKey.COMPANY_LIVE_FUNDING,
            player,
            mapOf("issued" to locale.text(ownership.issuedShares), "total" to locale.text(ownership.totalShares)),
        )
        WorksiteEnterpriseCapitalPhase.ACTIVE -> locale.render(
            MessageKey.COMPANY_LIVE_ACTIVE,
            player,
            mapOf("amount" to money(ownership.treasuryCents)),
        )
        WorksiteEnterpriseCapitalPhase.CANCELLED -> locale.render(MessageKey.COMPANY_LIVE_CANCELLED, player)
        WorksiteEnterpriseCapitalPhase.EXPIRED -> locale.render(MessageKey.COMPANY_LIVE_EXPIRED, player)
    }

    private fun phase(player: Player, phase: WorksiteEnterpriseCapitalPhase): Component = locale.render(
        when (phase) {
            WorksiteEnterpriseCapitalPhase.FUNDING -> MessageKey.COMPANY_SHARES_PHASE_FUNDING
            WorksiteEnterpriseCapitalPhase.ACTIVE -> MessageKey.COMPANY_SHARES_PHASE_ACTIVE
            WorksiteEnterpriseCapitalPhase.CANCELLED -> MessageKey.COMPANY_SHARES_PHASE_CANCELLED
            WorksiteEnterpriseCapitalPhase.EXPIRED -> MessageKey.COMPANY_SHARES_PHASE_EXPIRED
        },
        player,
    )

    private fun remainingHours(deadline: Long): Long =
        ceil((deadline - System.currentTimeMillis()).coerceAtLeast(0L) / TimeUnit.HOURS.toMillis(1).toDouble()).toLong()

    private fun farmCard(player: Player, visual: MenuItemVisualSettings): ItemStack {
        val view = service.enterpriseCompany(ActivityKind.FARM)
        val lore = mutableListOf(locale.render(MessageKey.COMPANIES_FARM_LORE, player), Component.empty())
        when {
            view != null -> {
                val ownership = service.enterpriseOwnership(ActivityKind.FARM, player.uniqueId)
                lore += locale.render(MessageKey.COMPANIES_ORDERS, player, mapOf("orders" to locale.text(view.completedOrdersThisWeek)))
                lore += locale.render(MessageKey.COMPANIES_WORKER_BONUS, player, mapOf("percent" to locale.text(view.workerBonusPercent)))
                lore += locale.render(MessageKey.COMPANIES_AVAILABLE, player, mapOf("amount" to money(view.availableGrossCents)))
                lore += locale.render(MessageKey.COMPANIES_PROJECTED, player, mapOf("amount" to money(view.projectedDividendPoolCents)))
                lore += Component.empty()
                lore += companyStatus(player, ownership)
                lore += Component.empty()
                lore += locale.render(MessageKey.COMPANIES_OPEN, player)
            }
            !service.isAvailable(ActivityKind.FARM) && service.canNavigate(ActivityKind.FARM) &&
                service.canAccess(player, ActivityKind.FARM) -> {
                lore += locale.render(MessageKey.COMPANIES_REMOTE, player)
                lore += Component.empty()
                lore += locale.render(MessageKey.COMPANIES_TRAVEL, player)
            }
            else -> lore += locale.render(MessageKey.COMPANIES_UNAVAILABLE, player)
        }
        return item(visual, locale.render(MessageKey.COMPANIES_FARM_NAME, player), lore)
    }

    private fun unavailableCard(
        player: Player,
        visual: MenuItemVisualSettings,
        name: MessageKey,
        description: MessageKey,
    ): ItemStack =
        item(
            visual,
            locale.render(name, player),
            listOf(locale.render(description, player), Component.empty(), locale.render(MessageKey.COMPANIES_UNAVAILABLE, player)),
        )

    private fun reportItem(
        player: Player,
        view: WorksiteEnterpriseCompanyView,
        visual: MenuItemVisualSettings,
    ): ItemStack = item(
        visual,
        locale.render(MessageKey.COMPANY_REPORT_NAME, player),
        listOf(
            locale.render(MessageKey.COMPANY_REPORT_ORDERS, player, mapOf("orders" to locale.text(view.completedOrdersThisWeek))),
            locale.render(MessageKey.COMPANY_REPORT_CONTRIBUTORS, player, mapOf("workers" to locale.text(view.uniqueContributorsThisWeek))),
            locale.render(MessageKey.COMPANY_REPORT_GROSS, player, mapOf("amount" to money(view.grossRevenueThisWeekCents))),
            locale.render(MessageKey.COMPANY_REPORT_RETAINED, player, mapOf("amount" to money(view.retainedProfitThisWeekCents))),
            Component.empty(),
            locale.render(MessageKey.COMPANY_REPORT_PROJECTED, player, mapOf("amount" to money(view.projectedDividendPoolCents))),
        ),
    )

    private fun workersItem(
        player: Player,
        view: WorksiteEnterpriseCompanyView,
        visual: MenuItemVisualSettings,
    ): ItemStack = item(
        visual,
        locale.render(MessageKey.COMPANY_WORKERS_NAME, player),
        listOf(
            locale.render(MessageKey.COMPANY_WORKERS_BONUS, player, mapOf("percent" to locale.text(view.workerBonusPercent))),
            locale.render(MessageKey.COMPANY_WORKERS_ACCRUED, player, mapOf("amount" to money(view.workerBonusThisWeekCents))),
        ),
    )

    private fun policyItem(
        player: Player,
        view: WorksiteEnterpriseCompanyView,
        visual: MenuItemVisualSettings,
    ): ItemStack = item(
        visual,
        locale.render(MessageKey.COMPANY_POLICY_NAME, player),
        listOf(
            locale.render(MessageKey.COMPANY_POLICY_OPERATING, player, mapOf("percent" to locale.text(view.operatingCostPercent))),
            locale.render(MessageKey.COMPANY_POLICY_DIVIDEND, player, mapOf("percent" to locale.text(view.dividendPercent))),
            locale.render(MessageKey.COMPANY_POLICY_UPKEEP, player, mapOf("amount" to money(view.weeklyUpkeepCents))),
        ),
    )

    private fun licenseItem(
        player: Player,
        view: WorksiteEnterpriseCompanyView,
        visual: MenuItemVisualSettings,
    ): ItemStack = item(
        visual,
        locale.render(MessageKey.COMPANY_LICENSE_NAME, player),
        listOf(
            locale.render(MessageKey.COMPANY_LICENSE_ENVELOPE, player, mapOf("amount" to money(view.licenseGrossEnvelopeCents))),
            locale.render(MessageKey.COMPANY_LICENSE_SETTLED, player, mapOf("amount" to money(view.settledGrossCents))),
            locale.render(MessageKey.COMPANY_LICENSE_RESERVED, player, mapOf("amount" to money(view.reservedGrossCents))),
            locale.render(MessageKey.COMPANY_LICENSE_AVAILABLE, player, mapOf("amount" to money(view.availableGrossCents))),
        ),
    )

    private fun backItem(player: Player, visual: MenuItemVisualSettings): ItemStack =
        item(
            visual,
            locale.render(MessageKey.COMPANIES_BACK_NAME, player),
            listOf(locale.render(MessageKey.COMPANIES_BACK_LORE, player), Component.empty(), locale.render(MessageKey.COMPANIES_BACK_CLICK, player)),
        )

    private fun inventory(
        player: Player,
        size: Int,
        title: MessageKey,
        screen: Screen,
        purchaseShares: Int = 0,
    ): Inventory {
        val holder = Holder(screen, purchaseShares)
        return player.server.createInventory(holder, size, locale.render(title, player)).also { holder.backing = it }
    }

    private fun fillBackground(inventory: Inventory, current: ArcFarmsConfig) {
        val background = current.menuBackground
        if (!background.enabled) return
        val filler = ItemStack(MaterialRules.material(background.material)).apply {
            editMeta { meta ->
                if (background.customModelData > 0) meta.setCustomModelData(background.customModelData)
                meta.setHideTooltip(true)
            }
        }
        repeat(inventory.size) { slot -> if (inventory.getItem(slot) == null) inventory.setItem(slot, filler) }
    }

    @Suppress("DEPRECATION")
    private fun item(
        visual: MenuItemVisualSettings,
        name: Component,
        lore: List<Component>,
    ): ItemStack = ItemStack(MaterialRules.material(visual.material)).apply {
        editMeta { meta ->
            if (visual.customModelData > 0) meta.setCustomModelData(visual.customModelData)
            meta.displayName(name.decoration(TextDecoration.ITALIC, false))
            meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
        }
    }

    private fun money(cents: Long): Component = locale.text(formatEnterpriseMoney(cents))

    private companion object {
        val BUY_SLOTS = listOf(19, 20, 21, 22, 23, 24, 25)
    }
}

internal fun formatEnterpriseMoney(cents: Long): String = DecimalFormat(
    "#,##0.##",
    DecimalFormatSymbols(Locale.ROOT).apply { groupingSeparator = ' ' },
).format(BigDecimal.valueOf(cents, 2))
