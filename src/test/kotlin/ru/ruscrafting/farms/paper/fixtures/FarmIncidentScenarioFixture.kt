package ru.ruscrafting.farms.paper.fixtures

import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.FarmShiftEvent
import ru.ruscrafting.farms.domain.FarmDeliveryRoute
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmProcessingLayout
import ru.ruscrafting.farms.domain.FarmRouteKeys
import ru.ruscrafting.farms.domain.FarmRouteState
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmNightShiftController
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.admin.FarmRouteAdminService
import ru.ruscrafting.farms.paper.farm.incident.fire.FarmBarnFireIncident
import ru.ruscrafting.farms.paper.farm.incident.processing.FarmProcessingIncident
import ru.ruscrafting.farms.paper.farm.incident.processing.FarmProcessingSceneRole
import ru.ruscrafting.farms.paper.farm.incident.route.FarmFoodDeliveryIncident
import ru.ruscrafting.farms.persistence.ArcFarmsStateRepository
import ru.ruscrafting.farms.persistence.FarmRouteRepository
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** Shared full-world fixture for multi-stage farm incident scenarios. */
internal class FarmIncidentScenarioFixture private constructor(
    val paper: MockBukkitTestRuntime,
    val world: WorldMock,
    val plugin: Plugin,
    val settings: ArcFarmsConfig,
    val locale: ArcFarmsLocale,
    val zone: FarmZoneSettings,
    val port: WorksiteRuntimePort,
    val processingPoint: FarmPointPosition,
    val barnPoint: FarmPointPosition,
    private val fixtureRoot: Path,
    private val stateRoot: Path,
    private val routeRepository: FarmRouteRepository,
    private val delayedTasks: MutableList<DelayedTask>,
) : AutoCloseable {
    data class AppliedTransition(
        val actor: Player?,
        val result: EngineResult<FarmShiftState, FarmShiftEvent>,
    )

    data class DelayedTask(
        val delayTicks: Long,
        val task: () -> Unit,
    )

    val transitions = mutableListOf<AppliedTransition>()

    private val processingRoleKey = NamespacedKey(plugin, "farm_processing_role")
    private val processingIndexKey = NamespacedKey(plugin, "farm_processing_index")
    private val transitionSink = FarmTransitionSink { runtime, result, actor ->
        transitions += AppliedTransition(actor, result)
        if (result.accepted) runtime.state = result.state
    }

    fun runtime(state: FarmShiftState): FarmRuntime {
        val orders = zone.orders.map { configured ->
            FarmOrder(
                id = configured.id,
                required = configured.required,
                rarity = configured.rarity,
                careTypes = configured.careTypes,
                incidentTypes = configured.incidentTypes,
                customerType = configured.customerType,
                cartLoadMaterial = configured.cartLoadMaterial,
                cartLoadCustomModelData = configured.cartLoadCustomModelData,
            )
        }
        return FarmRuntime(
            settings = zone,
            region = CuboidActivityRegion(world, "scenario_farm", CuboidBounds(0, 0, 0, 63, 128, 63)),
            orders = orders.associateBy(FarmOrder::id),
            orderList = orders,
            rules = FarmRules(listOf(50), 1, 1_000L),
            state = state,
        )
    }

    fun processing(): FarmProcessingIncident = FarmProcessingIncident(
        plugin = plugin,
        settings = { settings },
        locale = locale,
        debug = ArcFarmsDebug({ false }) {},
        access = port,
        audience = port,
        state = port,
        configuredPoint = { zoneId, kind ->
            if (zoneId == zone.id && kind == FarmPointKind.PROCESSING) processingPoint else null
        },
        transitions = transitionSink,
        blockPassability = MockBukkitFarmBlockPassability,
        textDisplays = MockBukkitFarmTextDisplays,
    )

    fun barnFire(): FarmBarnFireIncident = FarmBarnFireIncident(
        settings = { settings },
        debug = ArcFarmsDebug({ false }) {},
        access = port,
        audience = port,
        state = port,
        points = FarmPointProvider { runtime, kind ->
            check(runtime.settings.id == zone.id)
            check(kind == FarmPointKind.PEN)
            barnPoint
        },
        transitions = transitionSink,
        blockPassability = MockBukkitFarmBlockPassability,
    )

    fun foodDelivery(runtime: FarmRuntime, points: List<FarmPointPosition>): FarmFoodDeliveryIncident {
        routeRepository.saveBlocking(
            FarmRouteState(
                routes = mapOf(FarmRouteKeys.encode(zone.id, FarmRouteKeys.DEFAULT_NAME) to FarmDeliveryRoute(points)),
            ),
        )
        val routes = FarmRouteAdminService(
            repository = routeRepository,
            debug = ArcFarmsDebug({ false }) {},
            port = port,
            runtimes = { listOf(runtime) },
        )
        return FarmFoodDeliveryIncident(
            plugin = plugin,
            settings = { settings },
            locale = locale,
            debug = ArcFarmsDebug({ false }) {},
            access = port,
            audience = port,
            state = port,
            tasks = port,
            routes = routes,
            points = FarmPointProvider { _, kind ->
                check(kind == FarmPointKind.RECEIVING)
                FarmPointPosition(world.name, 12.5, 65.0, 12.5)
            },
            transitions = transitionSink,
            random = java.util.Random(7),
            night = FarmNightShiftController(plugin),
            blockPassability = MockBukkitFarmBlockPassability,
            mobDespawns = MockBukkitFarmMobDespawns,
            vehiclePassengers = MockBukkitFarmVehiclePassengers,
            textDisplays = MockBukkitFarmTextDisplays,
        )
    }

    fun runDelayedTasks(): List<Long> = delayedTasks.toList().also {
        delayedTasks.clear()
        it.forEach { delayed -> delayed.task() }
    }.map(DelayedTask::delayTicks)

    fun persistAndReload(runtime: FarmRuntime): FarmRuntime {
        ArcFarmsStateRepository(stateRoot).use { repository ->
            repository.saveBlocking(ArcFarmsState(farms = mapOf(zone.id to runtime.state)))
        }
        val restored = ArcFarmsStateRepository(stateRoot).use { repository ->
            repository.load().farms.getValue(zone.id)
        }
        return runtime(restored)
    }

    fun processingInteraction(
        controller: FarmProcessingIncident,
        runtime: FarmRuntime,
        role: FarmProcessingSceneRole,
        index: Int? = null,
    ): Interaction {
        repeat(12) {
            controller.ensure(runtime)
            findProcessingInteraction(role, index)?.let { return it }
        }
        error("Processing interaction $role/$index was not created")
    }

    fun processingDisplays(role: FarmProcessingSceneRole): List<ItemDisplay> =
        world.entities.asSequence().filterIsInstance<ItemDisplay>().filter { entity ->
            entity.persistentDataContainer.get(processingRoleKey, PersistentDataType.STRING) == role.name
        }.toList()

    fun processingTextDisplays(role: String): List<TextDisplay> =
        world.entities.asSequence().filterIsInstance<TextDisplay>().filter { entity ->
            entity.persistentDataContainer.get(processingRoleKey, PersistentDataType.STRING) == role
        }.toList()

    fun processingTextDisplay(index: Int): TextDisplay = processingTextDisplays(FarmProcessingSceneRole.LABEL.name).single { entity ->
        entity.persistentDataContainer.get(processingIndexKey, PersistentDataType.INTEGER) == index
    }

    fun clickProcessing(
        controller: FarmProcessingIncident,
        runtime: FarmRuntime,
        player: PlayerMock,
        role: FarmProcessingSceneRole,
        index: Int? = null,
        horizontalOffset: Double = 0.0,
    ) {
        val interaction = processingInteraction(controller, runtime, role, index)
        player.teleport(interaction.location.clone().add(horizontalOffset, 0.0, 0.0))
        val event = PlayerInteractEntityEvent(player, interaction, EquipmentSlot.HAND)
        check(controller.interact(event, listOf(runtime))) { "Processing interaction was not routed" }
        check(event.isCancelled) { "Processing interaction must be cancelled" }
    }

    fun deliverProcessingCargo(
        controller: FarmProcessingIncident,
        runtime: FarmRuntime,
        player: PlayerMock,
        raw: Boolean,
        tick: Long,
    ) {
        val layout = FarmProcessingLayout.create(processingPoint)
        val target = if (raw) layout.inputDrop else layout.outputPallet
        player.teleport(location(target))
        controller.update(listOf(runtime), tick)
        controller.ensure(runtime)
    }

    fun sprayFire(controller: FarmBarnFireIncident, runtime: FarmRuntime, player: PlayerMock, index: Int) {
        val point = requireNotNull(runtime.state.specialIncident).points[index]
        val target = location(point)
        player.inventory.setItemInMainHand(ItemStack(Material.SPYGLASS))
        // Approach a dense hotspot from directly above so the water cone cannot
        // accidentally select a neighbouring flame first.
        val origin = target.clone().add(0.0, 3.0, 0.0)
        origin.direction = target.toVector().subtract(origin.clone().add(0.0, player.eyeHeight, 0.0).toVector()).normalize()
        player.teleport(origin)
        val aimed = player.location.clone().setDirection(target.toVector().subtract(player.eyeLocation.toVector()).normalize())
        player.teleport(aimed)
        val event = PlayerInteractEvent(
            player,
            Action.RIGHT_CLICK_AIR,
            player.inventory.itemInMainHand,
            null,
            BlockFace.SELF,
            EquipmentSlot.HAND,
        )
        check(controller.spray(event, runtime)) { "Barn-fire spray was not routed" }
        check(event.isCancelled) { "Barn-fire spray must be cancelled" }
    }

    fun location(point: FarmPointPosition): Location =
        Location(world, point.x, point.y, point.z, point.yaw, point.pitch)

    override fun close() {
        try {
            routeRepository.close()
        } finally {
            try {
                paper.close()
            } finally {
                fixtureRoot.toFile().deleteRecursively()
            }
        }
    }

    private fun findProcessingInteraction(role: FarmProcessingSceneRole, index: Int?): Interaction? =
        world.entities.asSequence().filterIsInstance<Interaction>().firstOrNull { entity ->
            val data = entity.persistentDataContainer
            data.get(processingRoleKey, PersistentDataType.STRING) == role.name &&
                (index == null || data.get(processingIndexKey, PersistentDataType.INTEGER) == index)
        }

    companion object {
        fun open(): FarmIncidentScenarioFixture {
            val paper = MockBukkitTestRuntime.open()
            val world = paper.server.addSimpleWorld("sp11")
            for (chunkX in 0..3) for (chunkZ in 0..3) world.getChunkAt(chunkX, chunkZ).load()
            for (x in 0..63) for (z in 0..63) world.getBlockAt(x, 64, z).type = Material.STONE

            val plugin = paper.createSimplePlugin("FarmIncidentScenario")
            val fixtureRoot = Files.createTempDirectory("arcfarms-incident-scenario")
            val resourceRoot = copyResources(fixtureRoot.resolve("config"))
            val settings = ArcFarmsConfig.inspect(resourceRoot)
            val zone = settings.farms.single { it.id == "communal_farm" }
            val port = mockk<WorksiteRuntimePort>(relaxed = true)
            val delayedTasks = mutableListOf<DelayedTask>()
            every { port.hasAccess(any(), any()) } returns true
            every { port.allowInteraction(any(), any()) } returns true
            every { port.players(any()) } answers { paper.server.onlinePlayers.toList() }
            every { port.isAdminEditing(any()) } returns false
            every { port.persistAsync() } returns CompletableFuture.completedFuture(Unit)
            every { port.runLater(any(), any()) } answers {
                delayedTasks += DelayedTask(firstArg(), secondArg())
                true
            }

            val routeRepository = FarmRouteRepository(fixtureRoot.resolve("routes"))

            return FarmIncidentScenarioFixture(
                paper = paper,
                world = world,
                plugin = plugin,
                settings = settings,
                locale = ArcFarmsLocale(resourceRoot) { settings },
                zone = zone,
                port = port,
                processingPoint = FarmPointPosition(world.name, 18.5, 65.0, 18.5, 0f, 0f),
                barnPoint = FarmPointPosition(world.name, 46.5, 65.0, 46.5, 0f, 0f),
                fixtureRoot = fixtureRoot,
                stateRoot = fixtureRoot.resolve("state"),
                routeRepository = routeRepository,
                delayedTasks = delayedTasks,
            )
        }

        private fun copyResources(root: Path): Path {
            Files.createDirectories(root.resolve("lang"))
            listOf("config.yml", "lang/ru.yml", "lang/en.yml").forEach { name ->
                val input = requireNotNull(FarmIncidentScenarioFixture::class.java.classLoader.getResourceAsStream(name)) {
                    "Missing test resource $name"
                }
                input.use { Files.copy(it, root.resolve(name)) }
            }
            return root
        }
    }
}
