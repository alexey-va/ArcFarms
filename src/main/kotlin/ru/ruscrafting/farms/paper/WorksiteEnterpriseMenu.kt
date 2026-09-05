@file:Suppress("DEPRECATION")

package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseCapitalPhase
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseCompanyView
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseOwnershipView
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterprisePlayerView
import ru.ruscrafting.farms.paper.enterprise.EnterpriseInvestmentActionResult
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Farm-company report, primary funding and durable investment-account UI. */
internal class WorksiteEnterpriseMenu(
    private val service: ArcFarmsService,
    private val locale: ArcFarmsLocale,
    private val settings: () -> ArcFarmsConfig,
    private val menus: ArcFarmsMenuPlatform,
    private val openRoot: (Player) -> Unit,
    private val openParticipation: (Player) -> Unit = {},
) {
    fun openCompany(player: Player) {
        if (menus.usesDialogs && service.enterpriseCompany(ActivityKind.FARM) != null) openFarm(player)
        else openOverview(player)
    }

    fun openOverview(player: Player) {
        val current = settings()
        menus.open(player, OVERVIEW, { openOverview(player) }) {
            FarmMenuContent(
                title = locale.render(MessageKey.COMPANIES_TITLE, player),
                background = menus.background(OVERVIEW),
                elements = mapOf(
                    FARM to entry(farmCard(player)) { context ->
                        menus.transition(player, context.session) { openFarmOrTravel(player) }
                    },
                    LUMBER to entry(unavailableCard(player, LUMBER, MessageKey.COMPANIES_LUMBER_NAME, MessageKey.COMPANIES_LUMBER_LORE), false),
                    MINE to entry(unavailableCard(player, MINE, MessageKey.COMPANIES_MINE_NAME, MessageKey.COMPANIES_MINE_LORE), false),
                    BACK to entry(backItem(player, OVERVIEW)) { context ->
                        menus.transition(player, context.session) { openRoot(player) }
                    },
                ),
            )
        }
    }

    fun onClick(event: InventoryClickEvent): Boolean {
        return menus.owns(event, ENTERPRISE_MENUS)
    }

    fun onDrag(event: InventoryDragEvent): Boolean = menus.owns(event, ENTERPRISE_MENUS)

    private fun openFarmOrTravel(player: Player) {
        if (service.enterpriseCompany(ActivityKind.FARM) != null) {
            openFarm(player)
            return
        }
        if (!service.isAvailable(ActivityKind.FARM) && service.canNavigate(ActivityKind.FARM) &&
            service.canAccess(player, ActivityKind.FARM)
        ) {
            menus.close(player)
            service.travel(player, ActivityKind.FARM)
        }
    }

    private fun openFarm(player: Player) {
        val view = service.enterpriseCompany(ActivityKind.FARM) ?: run {
            openOverview(player)
            return
        }
        val ownership = service.enterpriseOwnership(ActivityKind.FARM, player.uniqueId)
        val playerView = service.enterprisePlayerView(player.uniqueId)
        menus.open(player, FARM_DETAIL, { openFarm(player) }) {
            val elements = linkedMapOf<MenuElementId, FarmMenuEntry>()
            elements[HEADER] = entry(item(
                FARM_DETAIL, HEADER,
                locale.render(MessageKey.COMPANY_FARM_HEADER_NAME, player),
                listOf(
                    locale.render(MessageKey.COMPANY_FARM_HEADER_LORE, player),
                    Component.empty(),
                    companyStatus(player, ownership),
                ),
            ), false)
            elements[REPORT] = entry(reportItem(player, view, playerView), false)
            elements[WORKERS] = entry(workersItem(player, view, playerView), false)
            elements[POLICY] = entry(policyItem(player, view), !menus.usesDialogs) { context ->
                menus.transition(player, context.session) { openParticipation(player) }
            }
            elements[LICENSE] = entry(licenseItem(player, view, playerView), false)
            val sharesLore = if (ownership == null) {
                listOf(locale.render(MessageKey.COMPANY_SHARES_LORE, player))
            } else {
                listOf(
                    locale.render(MessageKey.COMPANY_SHARES_LIVE_LORE, player),
                    Component.empty(),
                    locale.render(MessageKey.COMPANY_SHARES_OPEN, player),
                )
            }
            elements[SHARES] = entry(
                item(FARM_DETAIL, SHARES, locale.render(MessageKey.COMPANY_SHARES_NAME, player), sharesLore),
                enabled = ownership != null,
            ) { context ->
                menus.transition(player, context.session) { openShares(player) }
            }
            elements[MARKET] = entry(item(
                FARM_DETAIL, MARKET,
                locale.render(MessageKey.COMPANY_MARKET_NAME, player),
                projectLore(player, playerView),
            ), true) { context ->
                menus.transition(player, context.session) { openParticipation(player) }
            }
            elements[BACK] = entry(backItem(player, FARM_DETAIL)) { context ->
                menus.transition(player, context.session) {
                    if (menus.usesDialogs) openRoot(player) else openOverview(player)
                }
            }
            FarmMenuContent(
                title = locale.render(MessageKey.COMPANY_FARM_TITLE, player),
                background = menus.background(FARM_DETAIL),
                elements = elements,
            )
        }
    }

    private fun openShares(player: Player): FarmMenuSession? {
        val view = service.enterpriseOwnership(ActivityKind.FARM, player.uniqueId) ?: run {
            openFarm(player)
            return null
        }
        val current = settings()
        val playerView = service.enterprisePlayerView(player.uniqueId)
        val elements = linkedMapOf<MenuElementId, FarmMenuEntry>()
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
        elements[STATUS] = entry(item(SHARES_DETAIL, STATUS, locale.render(MessageKey.COMPANY_SHARES_STATUS_NAME, player), statusLore), false)
        elements[HOLDING] = entry(item(
                SHARES_DETAIL, HOLDING,
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
            ), false)
        val accountLore = mutableListOf(
            locale.render(MessageKey.COMPANY_SHARES_ACCOUNT_BALANCE, player, mapOf("amount" to money(view.accountBalanceCents))),
            locale.render(MessageKey.COMPANY_SHARES_ACCOUNT_AVAILABLE, player, mapOf("amount" to money(view.accountAvailableCents))),
        )
        playerView?.let {
            accountLore += locale.render(MessageKey.COMPANY_WORKERS_PROJECTED, player, mapOf("amount" to money(it.projectedDividendCents)))
            accountLore += locale.render(MessageKey.COMPANY_WORKERS_SETTLEMENT, player, mapOf("time" to locale.text(settlementTime(it.nextSettlementMillis))))
        }
        if (view.pendingManualReviewCount > 0) {
            accountLore += locale.render(
                MessageKey.COMPANY_SHARES_ACCOUNT_REVIEW,
                player,
                mapOf("count" to locale.text(view.pendingManualReviewCount)),
            )
        }
        elements[ACCOUNT] = entry(item(SHARES_DETAIL, ACCOUNT, locale.render(MessageKey.COMPANY_SHARES_ACCOUNT_NAME, player), accountLore), false)
        val buyOptions = current.enterprises.getValue(ActivityKind.FARM).capital.purchaseOptions.map { shares ->
            entry(buyItem(player, view, shares), canBuy(view, shares)) { context ->
                menus.transition(player, context.session) { openPurchaseConfirmation(player, shares) }
            }
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
        elements[WITHDRAW] = entry(
            item(SHARES_DETAIL, WITHDRAW, locale.render(MessageKey.COMPANY_SHARES_WITHDRAW_NAME, player), withdrawLore),
            view.accountAvailableCents > 0L,
        ) { context -> menus.transition(player, context.session) { withdraw(player) } }
        elements[BACK] = entry(backItem(player, SHARES_DETAIL)) { context ->
            menus.transition(player, context.session) { openFarm(player) }
        }
        return menus.open(player, SHARES_DETAIL, { openShares(player) }) {
            FarmMenuContent(
                title = locale.render(MessageKey.COMPANY_SHARES_TITLE, player),
                background = menus.background(SHARES_DETAIL),
                elements = elements,
                regions = mapOf(ArcFarmsMenuPlatform.BUY_OPTIONS to buyOptions),
            )
        }
    }

    private fun openPurchaseConfirmation(player: Player, shares: Int) {
        val view = service.enterpriseOwnership(ActivityKind.FARM, player.uniqueId)
        if (view == null || !canBuy(view, shares)) {
            openShares(player)
            return
        }
        val current = settings()
        val amount = Math.multiplyExact(view.sharePriceCents, shares.toLong())
        val confirm = item(
                CONFIRM, CONFIRM_ACTION,
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
                    locale.render(
                        MessageKey.COMPANY_SHARES_CONFIRM_LICENSE,
                        player,
                        mapOf("percent" to locale.text(current.enterprises.getValue(ActivityKind.FARM).capital.licenseBurnPercent)),
                    ),
                    locale.render(MessageKey.COMPANY_SHARES_CONFIRM_RISK, player),
                    Component.empty(),
                    locale.render(MessageKey.COMPANY_SHARES_CONFIRM_CLICK, player),
                ),
            ).also { it.amount = shares.coerceAtMost(it.maxStackSize) }
        menus.open(player, CONFIRM, { openPurchaseConfirmation(player, shares) }) {
            FarmMenuContent(
                title = locale.render(MessageKey.COMPANY_SHARES_CONFIRM_TITLE, player),
                background = menus.background(CONFIRM),
                elements = mapOf(
                    CONFIRM_ACTION to entry(confirm) { context ->
                        val options = settings().enterprises.getValue(ActivityKind.FARM).capital.purchaseOptions
                        val ownership = service.enterpriseOwnership(ActivityKind.FARM, player.uniqueId)
                        if (shares in options && ownership != null && canBuy(ownership, shares)) {
                            menus.transition(player, context.session) { buy(player, shares) }
                        }
                    },
                    BACK to entry(backItem(player, CONFIRM)) { context ->
                        menus.transition(player, context.session) { openShares(player) }
                    },
                ),
            )
        }
    }

    private fun buyItem(
        player: Player,
        view: WorksiteEnterpriseOwnershipView,
        shares: Int,
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
            "enterprise-share-buy",
            locale.render(MessageKey.COMPANY_SHARES_BUY_NAME, player, mapOf("shares" to locale.text(shares))),
            lore,
        ).also { it.amount = shares.coerceAtMost(it.maxStackSize) }
    }

    private fun buy(player: Player, shares: Int) {
        var expectedSession: FarmMenuSession? = null
        val result = service.buyFarmShares(player, shares) { completed ->
            notifyInvestment(player, completed, withdrawal = false)
            refreshSharesIfOpen(player, expectedSession)
        }
        notifyInvestment(player, result, withdrawal = false)
        expectedSession = openShares(player)
    }

    private fun withdraw(player: Player) {
        var expectedSession: FarmMenuSession? = null
        val result = service.withdrawFarmInvestment(player) { completed ->
            notifyInvestment(player, completed, withdrawal = true)
            refreshSharesIfOpen(player, expectedSession)
        }
        notifyInvestment(player, result, withdrawal = true)
        expectedSession = openShares(player)
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

    private fun refreshSharesIfOpen(player: Player, expectedSession: FarmMenuSession?) {
        if (!player.isOnline || expectedSession == null) return
        if (menus.session(player) === expectedSession) openShares(player)
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

    private fun farmCard(player: Player): ItemStack {
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
        return item(OVERVIEW, FARM, locale.render(MessageKey.COMPANIES_FARM_NAME, player), lore)
    }

    private fun unavailableCard(
        player: Player,
        element: MenuElementId,
        name: MessageKey,
        description: MessageKey,
    ): ItemStack =
        item(
            OVERVIEW,
            element,
            locale.render(name, player),
            listOf(locale.render(description, player), Component.empty(), locale.render(MessageKey.COMPANIES_UNAVAILABLE, player)),
        )

    private fun reportItem(
        player: Player,
        view: WorksiteEnterpriseCompanyView,
        playerView: WorksiteEnterprisePlayerView?,
    ): ItemStack {
        val lore = mutableListOf(
            locale.render(MessageKey.COMPANY_REPORT_ORDERS, player, mapOf("orders" to locale.text(view.completedOrdersThisWeek))),
            locale.render(MessageKey.COMPANY_REPORT_CONTRIBUTORS, player, mapOf("workers" to locale.text(view.uniqueContributorsThisWeek))),
            locale.render(MessageKey.COMPANY_REPORT_GROSS, player, mapOf("amount" to money(view.grossRevenueThisWeekCents))),
            locale.render(MessageKey.COMPANY_REPORT_RETAINED, player, mapOf("amount" to money(view.retainedProfitThisWeekCents))),
            Component.empty(),
            locale.render(MessageKey.COMPANY_REPORT_PROJECTED, player, mapOf("amount" to money(view.projectedDividendPoolCents))),
        )
        playerView?.let {
            lore += locale.render(MessageKey.COMPANY_REPORT_PERSONAL, player, mapOf("amount" to money(it.projectedDividendCents)))
            lore += locale.render(MessageKey.COMPANY_REPORT_SETTLEMENT, player, mapOf("time" to locale.text(settlementTime(it.nextSettlementMillis))))
        }
        return item(FARM_DETAIL, REPORT, locale.render(MessageKey.COMPANY_REPORT_NAME, player), lore)
    }

    private fun workersItem(
        player: Player,
        view: WorksiteEnterpriseCompanyView,
        playerView: WorksiteEnterprisePlayerView?,
    ): ItemStack {
        val lore = mutableListOf(
            locale.render(MessageKey.COMPANY_WORKERS_BONUS, player, mapOf("percent" to locale.text(view.workerBonusPercent))),
            locale.render(MessageKey.COMPANY_WORKERS_ACCRUED, player, mapOf("amount" to money(view.workerBonusThisWeekCents))),
        )
        playerView?.let {
            lore += locale.render(MessageKey.COMPANY_WORKERS_PERSONAL_ACCRUED, player, mapOf("amount" to money(it.workerAccruedCents)))
            lore += locale.render(MessageKey.COMPANY_WORKERS_PROJECTED, player, mapOf("amount" to money(it.projectedDividendCents)))
            lore += locale.render(MessageKey.COMPANY_WORKERS_AVAILABLE, player, mapOf("amount" to money(it.availableThisWeekCents)))
            lore += locale.render(MessageKey.COMPANY_WORKERS_SETTLEMENT, player, mapOf("time" to locale.text(settlementTime(it.nextSettlementMillis))))
            lore += locale.render(
                MessageKey.COMPANY_WORKERS_CONTRIBUTION,
                player,
                mapOf("orders" to locale.text(it.completedOrders), "contribution" to locale.text(it.contribution)),
            )
        }
        return item(FARM_DETAIL, WORKERS, locale.render(MessageKey.COMPANY_WORKERS_NAME, player), lore)
    }

    private fun policyItem(
        player: Player,
        view: WorksiteEnterpriseCompanyView,
    ): ItemStack = item(
        FARM_DETAIL,
        POLICY,
        locale.render(MessageKey.COMPANY_POLICY_NAME, player),
        listOf(
            locale.render(MessageKey.COMPANY_POLICY_OPERATING, player, mapOf("percent" to locale.text(view.operatingCostPercent))),
            locale.render(MessageKey.COMPANY_POLICY_DIVIDEND, player, mapOf("percent" to locale.text(view.dividendPercent))),
            locale.render(MessageKey.COMPANY_POLICY_UPKEEP, player, mapOf("amount" to money(view.weeklyUpkeepCents))),
            Component.empty(),
            locale.render(MessageKey.COMPANY_POLICY_OPEN, player),
        ),
    )

    private fun licenseItem(
        player: Player,
        view: WorksiteEnterpriseCompanyView,
        playerView: WorksiteEnterprisePlayerView?,
    ): ItemStack = item(
        FARM_DETAIL,
        LICENSE,
        locale.render(MessageKey.COMPANY_LICENSE_NAME, player),
        listOf(
            locale.render(MessageKey.COMPANY_LICENSE_ENVELOPE, player, mapOf("amount" to money(view.licenseGrossEnvelopeCents))),
            locale.render(MessageKey.COMPANY_LICENSE_SETTLED, player, mapOf("amount" to money(view.settledGrossCents))),
            locale.render(MessageKey.COMPANY_LICENSE_RESERVED, player, mapOf("amount" to money(view.reservedGrossCents))),
            locale.render(MessageKey.COMPANY_LICENSE_AVAILABLE, player, mapOf("amount" to money(view.availableGrossCents))),
            *(playerView?.let { listOf(locale.render(MessageKey.COMPANY_LICENSE_WEEKS, player, mapOf("weeks" to locale.text(it.licenseWeeksRemaining))), locale.render(MessageKey.COMPANY_LICENSE_OUTCOME, player)) } ?: emptyList()).toTypedArray(),
        ),
    )

    private fun projectLore(player: Player, view: WorksiteEnterprisePlayerView?): List<Component> = listOfNotNull(
        locale.render(MessageKey.COMPANY_MARKET_LORE, player),
        view?.let { locale.render(MessageKey.COMPANY_MARKET_STAGE, player, mapOf("stage" to locale.text(it.projectStage))) },
        view?.let { locale.render(MessageKey.COMPANY_MARKET_ORDERS, player, mapOf("orders" to locale.text(it.projectOrders), "target" to locale.text(it.projectTarget))) },
        Component.empty(),
        locale.render(MessageKey.COMPANY_MARKET_OPEN, player),
    )

    private fun settlementTime(millis: Long): String = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm z")
        .withLocale(Locale.forLanguageTag(settings().defaultLocale))
        .withZone(settings().enterprises.getValue(ActivityKind.FARM).businessWeek.zoneId)
        .format(Instant.ofEpochMilli(millis))


    private fun backItem(player: Player, menu: MenuId): ItemStack =
        item(
            menu,
            BACK,
            locale.renderPath("dialog." + when (menu) {
                SHARES_DETAIL -> "back-company"
                CONFIRM -> "back-shares"
                else -> "back-root"
            }, player),
            listOf(locale.render(MessageKey.COMPANIES_BACK_LORE, player), Component.empty(), locale.render(MessageKey.COMPANIES_BACK_CLICK, player)),
        )

    private fun entry(
        item: ItemStack,
        enabled: Boolean = true,
        click: (FarmMenuClickContext) -> Unit = {},
    ) = FarmMenuEntry(item, enabled, setOf(ClickType.LEFT), click)

    private fun item(
        menu: MenuId,
        element: MenuElementId,
        name: Component,
        lore: List<Component>,
    ): ItemStack = menus.item(menu, element, name, lore)

    private fun item(template: String, name: Component, lore: List<Component>): ItemStack =
        menus.item(template, name, lore)

    private fun money(cents: Long): Component = locale.text(formatEnterpriseMoney(cents))

    private companion object {
        val OVERVIEW = ArcFarmsMenuPlatform.ENTERPRISE_OVERVIEW
        val FARM_DETAIL = ArcFarmsMenuPlatform.ENTERPRISE_FARM
        val SHARES_DETAIL = ArcFarmsMenuPlatform.ENTERPRISE_SHARES
        val CONFIRM = ArcFarmsMenuPlatform.ENTERPRISE_CONFIRM
        val ENTERPRISE_MENUS = setOf(OVERVIEW, FARM_DETAIL, SHARES_DETAIL, CONFIRM)

        val FARM = MenuElementId.of("farm")
        val LUMBER = MenuElementId.of("lumber")
        val MINE = MenuElementId.of("mine")
        val HEADER = MenuElementId.of("header")
        val REPORT = MenuElementId.of("report")
        val WORKERS = MenuElementId.of("workers")
        val POLICY = MenuElementId.of("policy")
        val LICENSE = MenuElementId.of("license")
        val SHARES = MenuElementId.of("shares")
        val MARKET = MenuElementId.of("market")
        val STATUS = MenuElementId.of("status")
        val HOLDING = MenuElementId.of("holding")
        val ACCOUNT = MenuElementId.of("account")
        val WITHDRAW = MenuElementId.of("withdraw")
        val CONFIRM_ACTION = MenuElementId.of("confirm")
        val BACK = MenuElementId.of("back")
    }
}

internal fun formatEnterpriseMoney(cents: Long): String = DecimalFormat(
    "#,##0.##",
    DecimalFormatSymbols(Locale.ROOT).apply { groupingSeparator = ' ' },
).format(BigDecimal.valueOf(cents, 2))
