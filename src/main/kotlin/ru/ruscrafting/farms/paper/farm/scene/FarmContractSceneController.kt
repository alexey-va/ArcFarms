package ru.ruscrafting.farms.paper.farm.scene

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmContractPlanner
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmContractSceneManager
import ru.ruscrafting.farms.paper.FarmContractSceneRole
import ru.ruscrafting.farms.paper.FarmContractSceneSpec
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController

/** Complete high-level lifecycle for the customer, cart and delivered cargo scene. */
internal class FarmContractSceneController(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val points: FarmPointProvider,
    private val special: FarmSpecialIncidentController,
    private val runtimes: () -> Collection<FarmRuntime>,
) {
    private val scene = FarmContractSceneManager(plugin, debug)

    fun owns(entity: Entity): Boolean = scene.owns(entity)

    fun onChunkLoad(chunk: org.bukkit.Chunk) = scene.onChunkLoad(chunk)

    fun ensure(runtime: FarmRuntime) {
        val order = currentOrder(runtime)
        if (order == null || runtime.state.phase == FarmPhase.IDLE) {
            clear(runtime, "contract_inactive")
            return
        }
        val recoveredMilestone = FarmContractPlanner.harvestMilestone(runtime.state.completed(order), order.totalRequired)
        if (recoveredMilestone > runtime.state.harvestMilestone) {
            runtime.state = runtime.state.copy(harvestMilestone = recoveredMilestone)
            state.persistAsync()
            debug.event(
                "farm_contract_scene_milestone_reconciled",
                "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence,
                "milestone" to recoveredMilestone,
            )
        }
        val customerPoint = points.resolve(runtime, FarmPointKind.CUSTOMER)
        val cartPoint = points.resolve(runtime, FarmPointKind.CART)
        val visual = runtime.settings.contractCartVisual
        val customerWorld = Bukkit.getWorld(customerPoint.world) ?: return
        val cartWorld = Bukkit.getWorld(cartPoint.world) ?: return
        if (customerWorld !== cartWorld) return
        val customerLocation = Location(customerWorld, customerPoint.x, customerPoint.y, customerPoint.z, customerPoint.yaw, 0f)
        val cartLocation = Location(
            cartWorld,
            cartPoint.x,
            cartPoint.y + visual.yOffset,
            cartPoint.z,
            cartPoint.yaw + visual.yawOffset,
            0f,
        )
        if (!runtime.region.contains(customerLocation) || !runtime.region.contains(cartLocation)) return
        val market = runtime.state.specialIncident?.takeIf {
            runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.MARKET
        }
        scene.ensure(
            FarmContractSceneSpec(
                zoneId = runtime.settings.id,
                sequence = runtime.state.sequence,
                customerType = order.customerType,
                customerLocation = customerLocation,
                cartLocation = cartLocation,
                cartItem = sceneItem(visual.material, visual.customModelData),
                cartDisplayTransform = visual.displayTransform,
                cartScale = visual.scale,
                loadItem = sceneItem(order.cartLoadMaterial, order.cartLoadCustomModelData),
                loadCount = runtime.state.deliveredCrates.size.coerceIn(0, runtime.settings.delivery.crates),
                loadYOffset = visual.loadYOffset,
                loadScale = visual.loadScale,
                viewRange = visual.viewRange,
                customerName = market?.let { locale.renderPath("farm.market-buyer-name", null) },
                customerGlowing = market?.marketAccepted == false,
                hiddenRoles = FarmContractSceneVisibility.hiddenRoles(runtime.state.phase, runtime.state.incidentType),
            ),
        )
    }

    fun interact(player: Player, entity: Entity) {
        val identity = scene.metadata(entity) ?: return
        val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return
        if (runtime.state.sequence != identity.sequence || !runtime.region.contains(entity.location)) return
        if (!access.hasAccess(player, runtime.settings.permission)) {
            audience.sendChat(player, MessageKey.ZONE_LOCKED)
            return
        }
        if (!access.allowInteraction(
                "farm-contract-scene:${identity.zoneId}:${identity.role}:${player.uniqueId}",
                runtime.settings.inputCooldowns.contractSceneMillis,
            )) return
        val order = currentOrder(runtime) ?: return
        debug.event(
            "farm_contract_scene_interaction",
            "zone" to identity.zoneId,
            "sequence" to identity.sequence,
            "order" to order.id,
            "role" to identity.role,
            "player" to player.name,
            "market" to (runtime.state.incidentType == FarmIncidentType.MARKET),
            "market_accepted" to runtime.state.specialIncident?.marketAccepted,
        )
        when (identity.role) {
            FarmContractSceneRole.CUSTOMER -> interactCustomer(player, entity, runtime, order)
            FarmContractSceneRole.CART,
            FarmContractSceneRole.CART_INTERACTION,
            FarmContractSceneRole.CART_LOAD,
            -> audience.sendActionBar(
                player,
                MessageKey.FARM_CART_PROGRESS,
                mapOf(
                    "order" to locale.renderPath("order.farm.${order.id}", player),
                    "done" to locale.text(runtime.state.deliveredCrates.size),
                    "total" to locale.text(runtime.settings.delivery.crates),
                ),
            )
        }
    }

    fun clear(runtime: FarmRuntime, reason: String) = scene.clearZone(runtime.settings.id, reason)

    fun cleanup(reason: String) = scene.cleanupLoaded(reason)

    private fun interactCustomer(player: Player, entity: Entity, runtime: FarmRuntime, order: FarmOrder) {
        val market = runtime.state.specialIncident?.takeIf {
            runtime.state.phase == FarmPhase.INCIDENT && runtime.state.incidentType == FarmIncidentType.MARKET
        }
        if (market != null) {
            special.openMarket(player, runtime, market)
            return
        }
        audience.sendActionBar(
            player,
            MessageKey.FARM_CUSTOMER_REMINDER,
            mapOf(
                "customer" to locale.renderPath("customer.${order.customerType.name.lowercase()}.name", player),
                "order" to locale.renderPath("order.farm.${order.id}", player),
            ),
        )
        if (settings().sounds) player.playSound(entity.location, Sound.ENTITY_VILLAGER_YES, 0.65f, 1.05f)
    }

    @Suppress("DEPRECATION")
    private fun sceneItem(material: String, customModelData: Int): ItemStack =
        ItemStack(MaterialRules.material(material)).also { item ->
            if (customModelData <= 0) return@also
            val meta = item.itemMeta
            meta.setCustomModelData(customModelData)
            item.itemMeta = meta
        }

    private fun currentOrder(runtime: FarmRuntime): FarmOrder? = runtime.state.orderId?.let(runtime.orders::get)
}

internal object FarmContractSceneVisibility {
    private val CART_ROLES = setOf(
        FarmContractSceneRole.CART,
        FarmContractSceneRole.CART_INTERACTION,
        FarmContractSceneRole.CART_LOAD,
    )
    private val MOBILE_CART_INCIDENTS = setOf(FarmIncidentType.FOOD_DELIVERY, FarmIncidentType.RIVAL_RAID)

    fun hiddenRoles(phase: FarmPhase, incidentType: FarmIncidentType?): Set<FarmContractSceneRole> =
        if (phase == FarmPhase.INCIDENT && incidentType in MOBILE_CART_INCIDENTS) CART_ROLES else emptySet()
}
