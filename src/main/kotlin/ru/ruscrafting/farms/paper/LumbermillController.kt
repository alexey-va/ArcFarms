package ru.ruscrafting.farms.paper

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.LumberZoneSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberRules
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.domain.LumberShiftEvent
import ru.ruscrafting.farms.network.NetworkSignal
import kotlin.math.ceil

internal class LumbermillController(
    private val regionGateway: RegionGateway,
    private val locale: ArcFarmsLocale,
    private val port: WorksiteRuntimePort,
    private val clock: () -> Long,
) : WorksiteModule<LumberShiftState>, WorksiteBlockBreakHandler, WorksiteBlockInteractHandler, WorksiteGuidanceHandler {
    override val kind: ActivityKind = ActivityKind.LUMBER
    private var runtimes: List<Runtime> = emptyList()
    override val zoneCount: Int get() = runtimes.size

    fun rebuild(
        configured: List<LumberZoneSettings>,
        persisted: Map<String, LumberShiftState>,
        cooldownMillis: Long,
    ) {
        runtimes = configured.map { settings ->
            val region = requireNotNull(regionGateway.resolve(settings.reference)) {
                "Lumber zone ${settings.id} cannot resolve ${settings.reference}"
            }
            val station = requireNotNull(regionGateway.resolve(settings.station)) {
                "Lumber station ${settings.id} cannot resolve ${settings.station}"
            }
            Runtime(
                settings = settings,
                region = region,
                station = station,
                rules = LumberRules(
                    settings.fellingQuota,
                    settings.processingQuota,
                    settings.processingPerUse,
                    cooldownMillis,
                ),
                stationMaterials = settings.stationMaterials.mapTo(mutableSetOf(), MaterialRules::material),
                state = persisted[settings.id] ?: LumberShiftState(),
            )
        }
    }

    override fun states(): Map<String, LumberShiftState> = runtimes.associate { it.settings.id to it.state }

    override fun statuses(): List<ActivityStatus> = runtimes.map { runtime ->
        val progress = if (runtime.state.phase == LumberPhase.PROCESSING) {
            "${runtime.state.processed}/${runtime.rules.processingQuota}"
        } else {
            "${runtime.state.felled}/${runtime.rules.fellingQuota}"
        }
        ActivityStatus(kind, runtime.settings.id, "phase.lumber.${runtime.state.phase.name.lowercase()}", progress)
    }

    override fun canAccess(player: Player): Boolean = runtimes.any { port.hasAccess(player, it.settings.permission) }

    override fun onBreakHigh(event: BlockBreakEvent): Boolean {
        val runtime = runtimeAt(event.block.location) ?: return false
        port.traceBlockBreak(event, kind, runtime.settings.id)
        if (!port.hasAccess(event.player, runtime.settings.permission)) {
            event.isCancelled = true
            port.sendChat(event.player, MessageKey.ZONE_LOCKED)
            return true
        }
        event.isCancelled = !MaterialRules.isLumberBreakable(event.block.type)
        return true
    }

    override fun onBreakMonitor(event: BlockBreakEvent): Boolean {
        if (event.isCancelled) return false
        val runtime = runtimeAt(event.block.location) ?: return false
        val species = MaterialRules.speciesOf(event.block.type) ?: return true
        fell(runtime, event.player, species)
        return true
    }

    override fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean {
        val runtime = runtimes.firstOrNull { it.station.contains(clicked.location) } ?: return false
        if (clicked.type !in runtime.stationMaterials) return false
        event.isCancelled = true
        port.tracePlayerAction(player, kind, runtime.settings.id, "use_station", clicked.type)
        if (!port.hasAccess(player, runtime.settings.permission)) {
            port.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (!port.allowInteraction("lumber:${runtime.settings.id}:${player.uniqueId}", 900)) return true
        process(runtime, player)
        return true
    }

    override fun tick(now: Long) {
        runtimes.forEach { runtime ->
            port.guarded("lumber:${runtime.settings.id}") {
                val result = LumberShiftEngine.tick(runtime.state, runtime.rules, now)
                if (result.events.isNotEmpty()) apply(runtime, result, null)
            }
        }
    }

    override fun updateGuidance(expectedBars: MutableSet<ActivityBarKey>) {
        runtimes.forEach { runtime ->
            if (runtime.state.phase !in setOf(LumberPhase.FELLING, LumberPhase.PROCESSING)) return@forEach
            val region = if (runtime.state.phase == LumberPhase.PROCESSING) runtime.station else runtime.region
            port.players(region).forEach { player ->
                val processing = runtime.state.phase == LumberPhase.PROCESSING
                val done = if (processing) runtime.state.processed else runtime.state.felled
                val total = if (processing) runtime.rules.processingQuota else runtime.rules.fellingQuota
                val key = if (processing) MessageKey.LUMBER_ACTIONBAR_PROCESSING else MessageKey.LUMBER_ACTIONBAR_FELLING
                val values = mapOf(
                    "wood" to MaterialRules.woodComponent(requireNotNull(runtime.state.species)),
                    "done" to locale.text(done),
                    "total" to locale.text(total),
                )
                val component = locale.render(key, player, values)
                port.sendActionBar(player, key, values)
                port.updateBar(
                    player,
                    "lumber:${runtime.settings.id}",
                    component,
                    done.toFloat() / total,
                    BossBar.Color.YELLOW,
                    expectedBars,
                )
            }
        }
    }

    override fun emitGuidance() {
        runtimes.filter { it.state.phase == LumberPhase.FELLING }.forEach { runtime ->
            val species = runtime.state.species ?: return@forEach
            port.players(runtime.region).filterNot(port::isAdminEditing).forEach { player ->
                nearbyBlocks(player.location, runtime.region, 6, 5, 8) { MaterialRules.speciesOf(it.type) == species }
                    .forEach { block ->
                        port.spawnGuidanceDust(
                            player,
                            block.location.toCenterLocation().add(0.0, 0.8, 0.0),
                            GUIDANCE_COLOR,
                        )
                    }
            }
        }
    }

    private fun fell(runtime: Runtime, player: Player, species: String) {
        val now = clock()
        if (runtime.state.phase == LumberPhase.COOLDOWN) {
            port.sendActionBar(
                player,
                MessageKey.COOLDOWN,
                mapOf("seconds" to locale.text(remainingSeconds(runtime.state.cooldownEndsAt, now))),
            )
            return
        }
        if (runtime.state.phase == LumberPhase.IDLE) {
            val target = runtime.settings.species[(runtime.state.sequence % runtime.settings.species.size).toInt()]
            apply(runtime, LumberShiftEngine.start(runtime.state, target, runtime.rules, now), player)
        }
        val result = LumberShiftEngine.fell(runtime.state, runtime.rules, species, player.uniqueId, now)
        apply(runtime, result, player)
        if (!result.accepted && runtime.state.phase == LumberPhase.FELLING) {
            port.sendActionBar(
                player,
                MessageKey.LUMBER_WRONG_SPECIES,
                mapOf("wood" to MaterialRules.woodComponent(requireNotNull(runtime.state.species))),
            )
        }
    }

    private fun process(runtime: Runtime, player: Player): Boolean {
        if (runtime.state.phase != LumberPhase.PROCESSING) {
            port.sendActionBar(player, MessageKey.LUMBER_STATION_REQUIRED)
            return false
        }
        val result = LumberShiftEngine.process(runtime.state, runtime.rules, player.uniqueId, clock())
        apply(runtime, result, player)
        return result.accepted
    }

    private fun apply(
        runtime: Runtime,
        result: ru.ruscrafting.farms.domain.EngineResult<LumberShiftState, LumberShiftEvent>,
        actor: Player?,
    ) {
        runtime.state = result.state
        port.traceResult(
            kind,
            runtime.settings.id,
            actor,
            runtime.state.phase,
            "${runtime.state.felled}/${runtime.rules.fellingQuota}:${runtime.state.processed}/${runtime.rules.processingQuota}",
            result,
        )
        if (actor != null && result.contribution > 0) {
            port.recordContribution(actor.uniqueId, kind, result.contribution)
        }
        result.events.forEach { event ->
            when (event) {
                LumberShiftEvent.STARTED -> port.broadcast(
                    listOf(runtime.region),
                    MessageKey.LUMBER_STARTED,
                    mapOf("wood" to MaterialRules.woodComponent(requireNotNull(runtime.state.species))),
                    Sound.BLOCK_WOOD_PLACE,
                )
                LumberShiftEvent.PHASE_CHANGED -> {
                    port.broadcast(
                        listOf(runtime.region),
                        MessageKey.LUMBER_PROCESSING,
                        sound = Sound.BLOCK_PISTON_EXTEND,
                        title = true,
                    )
                    port.successBurst(runtime.region)
                    port.signal(
                        NetworkSignal.LUMBER_PROCESSING,
                        kind,
                        actor?.name,
                        listOf(runtime.region, runtime.station)
                            .flatMap(port::players)
                            .mapTo(mutableSetOf(), Player::getUniqueId),
                    )
                }
                LumberShiftEvent.COMPLETED -> {
                    port.recordCompletion(kind, runtime.state.contributors)
                    val regions = listOf(runtime.region, runtime.station)
                    port.broadcast(
                        regions,
                        MessageKey.LUMBER_COMPLETED,
                        mapOf("players" to locale.text(runtime.state.contributors.size)),
                        Sound.UI_TOAST_CHALLENGE_COMPLETE,
                        title = true,
                    )
                    port.announceWinner(regions, runtime.state.contributors)
                    port.celebration(regions)
                    port.complete(
                        kind,
                        actor?.name,
                        regions.flatMap(port::players).mapTo(mutableSetOf(), Player::getUniqueId),
                    )
                    port.persistAsync()
                }
                else -> Unit
            }
        }
    }

    private fun runtimeAt(location: Location): Runtime? = runtimes.firstOrNull { it.region.contains(location) }

    private fun nearbyBlocks(
        center: Location,
        region: ActivityRegion,
        horizontal: Int,
        vertical: Int,
        limit: Int,
        predicate: (Block) -> Boolean,
    ): List<Block> {
        val result = ArrayList<Block>(limit)
        loop@ for (y in -vertical..vertical) {
            for (x in -horizontal..horizontal) {
                for (z in -horizontal..horizontal) {
                    val block = center.world.getBlockAt(center.blockX + x, center.blockY + y, center.blockZ + z)
                    if (region.contains(block.location) && predicate(block)) result += block
                    if (result.size >= limit) break@loop
                }
            }
        }
        return result
    }

    private fun remainingSeconds(deadline: Long, now: Long): Long =
        ceil((deadline - now).coerceAtLeast(0) / 1000.0).toLong()

    private data class Runtime(
        val settings: LumberZoneSettings,
        val region: ActivityRegion,
        val station: ActivityRegion,
        val rules: LumberRules,
        val stationMaterials: Set<Material>,
        var state: LumberShiftState,
    )

    companion object {
        private val GUIDANCE_COLOR = Color.fromRGB(255, 200, 87)

        fun validateRuntime(configured: List<LumberZoneSettings>, regionGateway: RegionGateway) {
            configured.forEach { zone ->
                requireNotNull(regionGateway.resolve(zone.reference)) { "Lumber zone ${zone.id} cannot resolve ${zone.reference}" }
                requireNotNull(regionGateway.resolve(zone.station)) { "Lumber station ${zone.id} cannot resolve ${zone.station}" }
                zone.stationMaterials.forEach(MaterialRules::material)
                zone.species.forEach { species ->
                    require(
                        listOf("${species}_LOG", "${species}_WOOD", "${species}_STEM", "${species}_HYPHAE")
                            .any { Material.matchMaterial(it) != null },
                    ) { "Unknown lumber species in ${zone.id}: $species" }
                }
            }
        }

        fun validatePersisted(configured: List<LumberZoneSettings>, persisted: Map<String, LumberShiftState>) {
            val zones = configured.associateBy(LumberZoneSettings::id)
            persisted.forEach { (id, state) ->
                if (state.phase in setOf(LumberPhase.IDLE, LumberPhase.COOLDOWN)) return@forEach
                val zone = requireNotNull(zones[id]) { "Persisted active lumber zone $id is missing from config" }
                require(state.species in zone.species) { "Persisted lumber species ${state.species} is missing from $id" }
            }
        }

        fun validateReload(configured: List<LumberZoneSettings>, persisted: Map<String, LumberShiftState>) {
            persisted.filterValues { it.phase !in setOf(LumberPhase.IDLE, LumberPhase.COOLDOWN) }.forEach { (id, state) ->
                require(configured.any { it.id == id && state.species in it.species }) {
                    "Cannot remove active lumber zone/species $id during reload"
                }
            }
        }
    }
}
