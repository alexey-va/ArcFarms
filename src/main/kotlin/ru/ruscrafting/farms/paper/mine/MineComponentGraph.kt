package ru.ruscrafting.farms.paper.mine

import net.kyori.adventure.text.Component
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.worksite.WorksitePorts
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.index.MineChunkTicket
import ru.ruscrafting.farms.paper.mine.index.MineChunkTicketRegistry
import ru.ruscrafting.farms.persistence.MineRecoveryJournal
import ru.ruscrafting.farms.paper.MineBlockEffects
import ru.ruscrafting.farms.paper.PaperMineBlockEffects
import ru.ruscrafting.farms.paper.mine.prospecting.MineProspectingController
import ru.ruscrafting.farms.paper.mine.mining.MineMiningController
import ru.ruscrafting.farms.paper.mine.loading.MineLoadingController
import ru.ruscrafting.farms.paper.mine.extraction.MineCartEffects
import ru.ruscrafting.farms.paper.mine.extraction.MineCartScene
import ru.ruscrafting.farms.paper.mine.extraction.MineExtractionController
import ru.ruscrafting.farms.paper.mine.extraction.PaperMineCartEffects
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentSet
import ru.ruscrafting.farms.paper.mine.incident.cavein.MineCaveInIncident
import ru.ruscrafting.farms.paper.mine.incident.track.MineTrackDamageIncident
import ru.ruscrafting.farms.paper.mine.incident.gas.MineGasLeakIncident
import ru.ruscrafting.farms.paper.mine.incident.crystal.MineCrystalResonanceIncident
import ru.ruscrafting.farms.paper.mine.incident.flood.MineFloodingIncident
import ru.ruscrafting.farms.paper.mine.incident.power.MinePowerFailureIncident
import ru.ruscrafting.farms.paper.mine.incident.creature.MineCreatureNestIncident
import ru.ruscrafting.farms.paper.mine.incident.rescue.MineLostMinerIncident
import ru.ruscrafting.farms.paper.mine.incident.rescue.MineLostMinerMazeWorld
import ru.ruscrafting.farms.paper.mine.incident.rescue.PaperMineLostMinerMazeChunkRetention
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityEffects
import ru.ruscrafting.farms.paper.mine.incident.entity.PaperMineIncidentEntityEffects
import ru.ruscrafting.farms.paper.mine.incident.entity.MineObjectiveMarkerScene
import ru.ruscrafting.farms.paper.mine.recovery.MineIncidentBlockJournal
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.paper.worksite.WorksiteAsyncBlockScanner
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.paper.worksite.WorksiteRewardGrantService
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentScheduler
import ru.ruscrafting.farms.paper.mine.presentation.MineGuidanceSource
import ru.ruscrafting.farms.paper.worksite.WorksiteGuidancePresenter
import ru.ruscrafting.farms.paper.mine.admin.MineAdminService
import java.util.random.RandomGenerator
import ru.ruscrafting.farms.paper.mine.lift.MineLiftAccess

/** Composition-only graph; the registry remains the sole V2 runtime collection owner. */
internal class MineComponentGraph(
    plugin: Plugin,
    serverId: String,
    regions: RegionGateway,
    ports: WorksitePorts,
    clock: () -> Long,
    journal: MineRecoveryJournal,
    debug: ArcFarmsDebug,
    random: RandomGenerator = RandomGenerator.getDefault(),
    blockEffects: MineBlockEffects = PaperMineBlockEffects,
    serviceItems: WorksiteServiceItems? = null,
    locale: ArcFarmsLocale? = null,
    cartEffects: MineCartEffects = PaperMineCartEffects(plugin),
    incidentEntityEffects: MineIncidentEntityEffects = PaperMineIncidentEntityEffects(plugin),
    rewardGrants: WorksiteRewardGrantService? = null,
    internal val lift: MineLiftAccess? = null,
    private val points: ru.ruscrafting.farms.paper.mine.point.MinePointService? = null,
    private val tickets: MineChunkTicket = MineChunkTicketRegistry(plugin),
    creatureNavigation: ru.ruscrafting.farms.paper.mine.incident.creature.MineCreatureNavigation = ru.ruscrafting.farms.paper.mine.incident.creature.PaperMineCreatureNavigation,
    blockDataDecoder: ru.ruscrafting.farms.paper.platform.FarmBlockDataDecoder = ru.ruscrafting.farms.paper.platform.PaperFarmBlockDataDecoder,
) {
    internal val registry = MineRuntimeRegistry()
    val recovery = MineBlockRecoveryController(journal, ports.access, ports.state, ports.tasks, clock)
    val index = MineBlockIndex(plugin, lift)
    private val incidentJournal = MineIncidentBlockJournal(recovery)
    private val transitions = MineTransitionCoordinator(ports.state, ports.stats, ports.audience, locale, incidentJournal)
    private val incidents = MineIncidentCoordinator(transitions, ports.state, lift)
    private val blockScanner = WorksiteAsyncBlockScanner(ports.tasks)
    val caveIn = MineCaveInIncident(
        registry, index, incidents, incidentJournal, recovery, ports.audience, ports.state, blockScanner, lift, incidentEntityEffects,
    )
    val trackDamage = MineTrackDamageIncident(registry, index, incidents, serviceItems, ports.state, locale)
    val candidateStock = ru.ruscrafting.farms.paper.mine.incident.MineIncidentCandidateStock(index)
    val gasLeak = MineGasLeakIncident(registry, index, incidents, candidateStock)
    val crystalResonance = MineCrystalResonanceIncident(registry, index, incidents, candidateStock)
    val flooding = MineFloodingIncident(registry, index, incidents, incidentJournal, ports.state, ports.access, serviceItems, locale, candidateStock)
    val powerFailure = MinePowerFailureIncident(registry, index, incidents, incidentJournal, ports.state)
    val cartScene = MineCartScene(cartEffects)
    val extraction = MineExtractionController(
        registry, serverId, index, cartScene, transitions, ports.access, ports.audience, ports.stats, ports.network, clock, rewardGrants,
    )
    val loading = MineLoadingController(
        registry, index, extraction, transitions, serviceItems, locale, ports.access, ports.audience, ports.state, clock,
    )
    val creatureNest = MineCreatureNestIncident(registry, index, incidents, incidentEntityEffects, ports.access, locale,
        ru.ruscrafting.farms.paper.mine.incident.creature.MineCreaturePests(incidentJournal, creatureNavigation, lift), candidateStock)
    private val lostMinerMaze = MineLostMinerMazeWorld(
        plugin,
        debug,
        PaperMineLostMinerMazeChunkRetention(tickets),
        blockDataDecoder, ports.tasks,
    )
    val lostMiner = MineLostMinerIncident(registry, index, incidents, incidentEntityEffects, lostMinerMaze,
        ru.ruscrafting.farms.paper.worksite.WorksiteExpeditionTravel(plugin, ports.tasks, ports.access, ports.state,
            java.nio.file.Path.of("data/recovery/mine-rescue-returns")),
        ru.ruscrafting.farms.paper.mine.incident.rescue.MineRescueCreatures(registry, incidentEntityEffects, ports.access),
        ports.access,
        closingWarning = { player, seconds ->
            player.sendActionBar(locale?.renderPath("mine.working.closing-warning", player,
                mapOf("seconds" to Component.text(seconds))) ?: Component.empty())
        },
        clock = clock,
        candidateStock = candidateStock,
        minerLabel = { locale?.renderPath("mine.rescue-miner-label", null) ?: Component.empty() },
    )
    val objectiveMarkers = MineObjectiveMarkerScene(incidentEntityEffects)
    private val workingWorld = ru.ruscrafting.farms.paper.mine.working.MineWorkingWorld(
        registry,
        ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneOwner(
            plugin, "mine_working",
            ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorksiteSceneCodec(plugin, "mine_working"),
            ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneChunkRetention { chunk ->
                check(tickets.retain(chunk))
                AutoCloseable { tickets.release(chunk) }
            },
            ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneBlockDataDecoder(
                blockDataDecoder::decode),
        ),
        blockDataDecoder,
        { position -> recovery.containsPosition("${position.world}:${position.x}:${position.y}:${position.z}") },
    )
    private val workingPresentation = ru.ruscrafting.farms.paper.mine.working.MineWorkingPresentation(
        plugin, locale, ru.ruscrafting.farms.paper.platform.PaperFarmTextDisplayRenderer,
        ru.ruscrafting.farms.paper.mine.working.MineWorkingCart(plugin),
        { id -> lift?.floors()?.sortedByDescending { it.y }?.indexOfFirst { it.id == id }
            ?.takeIf { it >= 0 }?.plus(1) },
    )
    val workshop = ru.ruscrafting.farms.paper.mine.workshop.MineOreWorkshopController(
        plugin, { runtime -> points?.points(runtime).orEmpty().mapNotNull { (id, point) ->
            val station = mapOf("ore_input" to "ore", "ore_crusher" to "crusher", "ore_furnace" to "furnace",
                "ore_output" to "output", "ore_shipping" to "shipping")[id] ?: return@mapNotNull null
            org.bukkit.Bukkit.getWorld(point.world)?.let { world -> station to org.bukkit.Location(world, point.x, point.y, point.z, point.yaw, 0f) }
        }.toMap() }, incidents, ports.access, ports.state, locale, clock,
    )
    val workings = ru.ruscrafting.farms.paper.mine.working.MineWorkingController(
        registry, ru.ruscrafting.farms.paper.mine.working.MineWorkingPlacementService(index, blockScanner, lift, ports.state, points),
        workingWorld, incidents, ru.ruscrafting.farms.paper.mine.working.MineWorkingEquipment(registry, serviceItems, ports.state, locale),
        workingPresentation,
        ru.ruscrafting.farms.paper.worksite.WorksiteExpeditionTravel(plugin, ports.tasks, ports.access, ports.state,
            java.nio.file.Path.of("data/recovery/mine-working-returns")),
        ports.access, ports.state, ports.tasks, clock,
        ru.ruscrafting.farms.paper.mine.working.MineDriveController(plugin, workingWorld, ports.access, ports.state, ports.tasks, workingPresentation),
    )
    private val expeditionWorld = ru.ruscrafting.farms.paper.mine.expedition.MineExpeditionWorld(
        plugin, ru.ruscrafting.farms.paper.mine.expedition.BukkitMineExpeditionWorldRegistry(plugin),
        ports.tasks, ports.state, tickets,
        ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneBlockDataDecoder(blockDataDecoder::decode),
    )
    val expeditions = ru.ruscrafting.farms.paper.mine.expedition.MineExpeditionController(
        plugin, registry, expeditionWorld, incidents,
        ru.ruscrafting.farms.paper.worksite.WorksiteExpeditionTravel(plugin, ports.tasks, ports.access, ports.state,
            java.nio.file.Path.of("data/recovery/mine-expedition-returns")),
        { runtime -> points?.configured(runtime.settings.id, "expedition_gate", runtime.region.world.name)?.let { point ->
            org.bukkit.Bukkit.getWorld(point.world)?.let { world ->
                org.bukkit.Location(world, point.x, point.y, point.z, point.yaw, 0f)
            }
        } }, ports.access, ports.state, locale, clock, ports.tasks,
    )
    val incidentScheduler = MineIncidentScheduler(
        caveIn, gasLeak, flooding, trackDamage, crystalResonance, creatureNest, powerFailure, lostMiner,
        workings, ru.ruscrafting.farms.paper.mine.incident.MineIncidentPlacementDiagnostics(index, candidateStock), ports.state, workshop, expeditions,
    )
    val incidentSet = MineIncidentSet(
        registry, caveIn, trackDamage, gasLeak, crystalResonance, flooding, powerFailure, creatureNest, lostMiner, objectiveMarkers,
        incidents, incidentScheduler, incidentJournal, workings, workshop, ports.access, expeditions, candidateStock,
    )
    val guidance = MineGuidanceSource(
        registry, ports.audience, locale, extraction::guidanceTarget, { extraction.routeFor(it)?.finalIndex ?: 1 }, clock,
        { runtime, player, now -> expeditions.guidanceHint(runtime, player, now) ?: if (runtime.state.incident?.type == ru.ruscrafting.farms.domain.MineIncidentType.ORE_WORKSHOP)
            workshop.guidanceHint(runtime, player, now) else workings.guidanceHint(runtime, player, now) },
        { runtime, player -> if (runtime.state.incident?.type?.let(ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionEngine::supports) == true)
            expeditions.guidanceTargets(runtime, player) else if (runtime.state.incident?.type == ru.ruscrafting.farms.domain.MineIncidentType.ORE_WORKSHOP)
            workshop.guidanceTargets(runtime, player) else workings.guidanceTargets(runtime, player) },
        expeditions::participants, expeditions::runtimeFor, ru.ruscrafting.farms.paper.mine.presentation.MineOrderOreGuidance(index),
    )
    private val guidancePresenter = WorksiteGuidancePresenter(ports.audience, ports.access, guidance)
    val prospecting = MineProspectingController(
        registry, index, recovery, transitions, ports.access, ports.audience, clock, loading::canStage,
    ) { runtime, player ->
        locale?.renderPath("route.mine.${runtime.settings.id}", player) ?: Component.text(runtime.settings.id)
    }
    val veins = ru.ruscrafting.farms.paper.mine.mining.MineVeinController(index, recovery, ports.state, clock)
    val mining = MineMiningController(
        registry, index, recovery, transitions, ports.access, ports.audience, ports.state, clock, random, blockEffects, loading::begin,
        locale = locale,
    )
    private val pickaxes = MinePickaxeSupply(registry, serviceItems, locale, ports.audience)
    private val worldWarmup = MineWorldWarmup(tickets, ports.tasks, extraCoordinates = { runtime ->
        points?.workingPlacements(runtime).orEmpty().flatMap { (_, point) ->
            val placement=point.workingPlacement("prepared",layoutSeed=1L)
            val end=placement.position(0,0,46)
            val minX=minOf(placement.entrance.x,end.x)-23; val maxX=maxOf(placement.entrance.x,end.x)+23
            val minZ=minOf(placement.entrance.z,end.z)-23; val maxZ=maxOf(placement.entrance.z,end.z)+23
            buildList { for(x in (minX shr 4)..(maxX shr 4)) for(z in (minZ shr 4)..(maxZ shr 4)) add(x to z) }
        }.distinct()
    }) { message, failure ->
        ports.state.log(java.util.logging.Level.WARNING, message, failure)
    }
    val admin = MineAdminService(
        registry, index, tickets, prospecting, extraction, incidentScheduler, incidentSet, ports.state,
    )
    val module = MineModule(
        regions, ports.access, ports.audience, ports.tasks, ports.state, transitions, registry, recovery, index, tickets, worldWarmup, prospecting, mining, loading, extraction, cartScene,
        incidentSet, guidancePresenter, admin, clock, veins, pickaxes,
    )

    internal val mutableRuntimeCollectionCount: Int = 1
}
