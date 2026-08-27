package ru.ruscrafting.farms.paper.farm.presentation

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmGuidancePlanner
import ru.ruscrafting.farms.domain.FarmGiantCropBlueprint
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotGeometry
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmSpecialIncidentEngine
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.care.FarmCareController
import ru.ruscrafting.farms.paper.farm.care.FarmCarePlanService
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.incident.special.SPECIAL_FARM_INCIDENT_TYPES
import ru.ruscrafting.farms.paper.location
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Stateless renderer for farm world markers and explicit admin guidance bursts. */
internal class FarmGuidanceController(
    private val settings: () -> ArcFarmsConfig,
    private val port: WorksiteRuntimePort,
    private val care: FarmCareController,
    private val carePlans: FarmCarePlanService,
    private val delivery: FarmDeliveryController,
    private val points: FarmPointProvider,
    private val runtimes: () -> Collection<FarmRuntime>,
) {
    fun emit() {
        if (!settings().particles) return
        val farms = runtimes()
        farms.filter { it.state.phase == FarmPhase.PREPARATION }.forEach { runtime ->
            val marker = FarmPlotGeometry.center(runtime.state.preparationPatch.filterNot(runtime.state.tilledPlots::contains))?.location()
                ?: return@forEach
            players(runtime).filterNot(port::isAdminEditing).forEach { spawnColumn(it, marker, TILL_COLOR) }
        }
        farms.filter { it.state.phase == FarmPhase.PLANTING }.forEach { runtime ->
            val remaining = runtime.state.tilledPlots.filterNot(runtime.state.plantedPlots::contains)
            val individual = FarmGuidancePlanner.individualMissingPlots(
                remaining,
                settings().missingBedHighlightThreshold,
            ).mapNotNull(FarmPlotPosition::location)
            val marker = if (individual.isEmpty()) FarmPlotGeometry.center(remaining)?.location() else null
            players(runtime).filterNot(port::isAdminEditing).forEach { player ->
                if (individual.isNotEmpty()) individual.forEach { spawnPlotMarker(player, it, PLANT_COLOR) }
                else if (marker != null) spawnColumn(player, marker, PLANT_COLOR)
            }
        }
        farms.filter { it.state.phase == FarmPhase.INCIDENT && it.state.incidentType == FarmIncidentType.DROUGHT }
            .forEach { runtime ->
                val markers = cluster(runtime.state.droughtPlots, runtime.settings.droughtPatches)
                    .mapNotNull(FarmPlotGeometry::center)
                    .mapNotNull(FarmPlotPosition::location)
                players(runtime).filterNot(port::isAdminEditing).forEach { player ->
                    markers.forEach { spawnColumn(player, it, DROUGHT_COLOR) }
                }
            }
        farms.filter { it.state.phase == FarmPhase.INCIDENT && it.state.incidentType in SPECIAL_FARM_INCIDENT_TYPES }
            .forEach(::emitSpecial)
        farms.filter { it.state.phase == FarmPhase.CARE }.forEach(::emitCare)
        farms.filter { it.state.phase == FarmPhase.DELIVERY }.forEach(::emitDelivery)
    }

    fun showDebug(playerId: UUID, zoneId: String, remainingBursts: Int) {
        if (remainingBursts <= 0) return
        val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline) ?: return
        val runtime = runtimes().firstOrNull { it.settings.id == zoneId } ?: return
        emitActiveTarget(player, runtime)
        POINT_COLORS.forEach { (kind, color) ->
            val point = points.resolve(runtime, kind)
            val world = Bukkit.getWorld(point.world) ?: return@forEach
            if (player.world == world) spawnColumn(player, Location(world, point.x, point.y, point.z), color)
        }
        if (remainingBursts > 1) port.runLater(10L) { showDebug(playerId, zoneId, remainingBursts - 1) }
    }

    private fun emitCare(runtime: FarmRuntime) {
        val incomplete = runtime.state.careTargets.filterNot(FarmCareTarget::complete)
        players(runtime).filterNot(port::isAdminEditing).forEach { player ->
            val hive = runtime.state.careTargets.firstOrNull { it.role == FarmCareRole.HIVE }
            val visible = when (runtime.state.careType) {
                FarmCareType.SEEDER -> incomplete.firstOrNull { it.role == FarmCareRole.SEEDER_HORSE }
                    ?.let(::listOf).orEmpty()
                FarmCareType.IRRIGATION -> incomplete.minByOrNull(FarmCareTarget::id)?.let(::listOf).orEmpty()
                FarmCareType.POLLINATION -> if (care.hasPollen(player, runtime)) {
                    incomplete.filter { it.role == FarmCareRole.FLOWER_PATCH }
                } else listOfNotNull(hive)
                else -> incomplete
            }
            visible.filter { it.role != FarmCareRole.ANIMAL }.forEach { target ->
                val world = Bukkit.getWorld(target.position.world) ?: return@forEach
                if (player.world == world) {
                    spawnPlotMarker(
                        player,
                        Location(world, target.position.x, target.position.y - 1.0, target.position.z),
                        care.color(target.role),
                    )
                }
            }
            if (runtime.state.careType == FarmCareType.ANIMAL_RESCUE) {
                carePlans.fixturePoint(runtime, FarmPointKind.PEN)?.let { pen ->
                    val world = Bukkit.getWorld(pen.world) ?: return@let
                    if (player.world == world) spawnColumn(player, Location(world, pen.x, pen.y, pen.z), SUCCESS_COLOR)
                }
            } else {
                visible.filter { Bukkit.getWorld(it.position.world) == player.world }
                    .minByOrNull { target ->
                        val dx = target.position.x - player.location.x
                        val dz = target.position.z - player.location.z
                        dx * dx + dz * dz
                    }?.let { target ->
                        spawnColumn(
                            player,
                            Location(player.world, target.position.x, target.position.y, target.position.z),
                            care.color(target.role),
                        )
                    }
            }
        }
    }

    private fun emitDelivery(runtime: FarmRuntime) {
        val deliveryPoint = points.resolve(runtime, FarmPointKind.RECEIVING)
        val world = Bukkit.getWorld(deliveryPoint.world) ?: return
        val target = Location(world, deliveryPoint.x, deliveryPoint.y, deliveryPoint.z)
        val crateTargets = delivery.uncarriedLocations(runtime)
        players(runtime).filter { it.world == world && !port.isAdminEditing(it) }.forEach { player ->
            spawnColumn(player, target, DELIVERY_COLOR)
            spawnRing(player, target, runtime.settings.delivery.radius, DELIVERY_COLOR)
            crateTargets.forEach { spawnPlotMarker(player, it, AMBER_COLOR) }
        }
    }

    private fun emitSpecial(runtime: FarmRuntime) {
        val special = runtime.state.specialIncident ?: return
        val remaining = special.plots.filter { plot -> runtime.state.specialDamagedCrops.none { it.position == plot } }
        players(runtime).filterNot(port::isAdminEditing).forEach { player ->
            when (runtime.state.incidentType) {
                FarmIncidentType.GIANT_CROP -> special.points.firstOrNull()?.let { point ->
                    val anchor = Location(player.world, point.x, point.y, point.z)
                    spawnColumn(player, anchor, AMBER_COLOR)
                    spawnRing(player, anchor.clone().add(0.0, -1.0, 0.0), 2.3, AMBER_COLOR)
                    emitGiantCropBlocks(player, point, special.crop, runtime.settings.specialIncidents.giantCropParticleStride)
                }
                FarmIncidentType.CHANNELS -> special.points.forEachIndexed { index, point ->
                    if (index in special.active) return@forEachIndexed
                    spawnSlimColumn(
                        player,
                        Location(player.world, point.x, point.y, point.z),
                        WATER_COLOR,
                    )
                }.also {
                    val source = points.resolve(runtime, FarmPointKind.IRRIGATION)
                    val reached = FarmSpecialIncidentEngine.channelFlowProgress(special.active, special.points.size)
                    val visibleFlow = (reached + 1).coerceAtMost(special.points.size)
                    (listOf(source) + special.points.take(visibleFlow)).zipWithNext()
                        .forEach { (from, to) -> spawnWaterTrail(player, from, to) }
                }
                FarmIncidentType.NIGHT_SHIFT -> remaining.forEach { plot ->
                    plot.location()?.let { location ->
                        player.spawnParticle(Particle.END_ROD, location.add(0.5, 1.65, 0.5), 1, 0.08, 0.12, 0.08, 0.0)
                    }
                }
                FarmIncidentType.MARKET -> if (!special.marketAccepted) {
                    val customer = points.resolve(runtime, FarmPointKind.CUSTOMER)
                    spawnColumn(player, Location(player.world, customer.x, customer.y, customer.z), AMBER_COLOR)
                }
                else -> Unit
            }
        }
    }

    private fun emitGiantCropBlocks(
        player: Player,
        anchor: FarmPointPosition,
        crop: String?,
        stride: Int,
    ) {
        if (crop == null || !FarmGiantCropBlueprint.supports(crop)) return
        val baseX = kotlin.math.floor(anchor.x).toInt()
        val baseY = kotlin.math.floor(anchor.y).toInt()
        val baseZ = kotlin.math.floor(anchor.z).toInt()
        val phase = java.lang.Math.floorMod((player.world.gameTime / 10L).toInt(), stride)
        FarmGiantCropBlueprint.voxels(crop).forEachIndexed { index, voxel ->
            if (index % stride != phase) return@forEachIndexed
            val block = player.world.getBlockAt(baseX + voxel.dx, baseY + voxel.dy, baseZ + voxel.dz)
            if (block.type.name != voxel.material) return@forEachIndexed
            player.spawnParticle(
                Particle.DUST,
                block.location.add(0.5, 0.55, 0.5),
                1,
                0.22,
                0.22,
                0.22,
                0.0,
                Particle.DustOptions(AMBER_COLOR, 1.25f),
            )
        }
    }

    private fun emitActiveTarget(player: Player, runtime: FarmRuntime) {
        val markers = when (runtime.state.phase) {
            FarmPhase.PREPARATION -> listOfNotNull(
                FarmPlotGeometry.center(runtime.state.preparationPatch.filterNot(runtime.state.tilledPlots::contains))?.location()
                    ?.let { it to TILL_COLOR },
            )
            FarmPhase.PLANTING -> listOfNotNull(
                FarmPlotGeometry.center(runtime.state.tilledPlots.filterNot(runtime.state.plantedPlots::contains))?.location()
                    ?.let { it to PLANT_COLOR },
            )
            FarmPhase.CARE -> runtime.state.careTargets.filterNot(FarmCareTarget::complete).mapNotNull { target ->
                Bukkit.getWorld(target.position.world)?.let { world ->
                    Location(world, target.position.x, target.position.y, target.position.z) to care.color(target.role)
                }
            }
            FarmPhase.HARVESTING -> listOfNotNull(FarmPlotGeometry.center(runtime.state.preparationPatch)?.location()?.let { it to AMBER_COLOR })
            FarmPhase.INCIDENT -> incidentMarkers(runtime)
            FarmPhase.DELIVERY -> points.resolve(runtime, FarmPointKind.RECEIVING).let { point ->
                Bukkit.getWorld(point.world)?.let { listOf(Location(it, point.x, point.y, point.z) to DELIVERY_COLOR) }.orEmpty()
            }
            FarmPhase.IDLE, FarmPhase.COOLDOWN -> emptyList()
        }
        markers.filter { it.first.world == player.world }.forEach { (location, color) -> spawnColumn(player, location, color) }
    }

    private fun incidentMarkers(runtime: FarmRuntime): List<Pair<Location, Color>> = when (runtime.state.incidentType) {
        FarmIncidentType.DROUGHT -> cluster(runtime.state.droughtPlots, runtime.settings.droughtPatches)
            .mapNotNull(FarmPlotGeometry::center).mapNotNull(FarmPlotPosition::location).map { it to DROUGHT_COLOR }
        FarmIncidentType.PESTS -> runtime.state.pestNests.mapNotNull { it.position.location() }.map { it to DANGER_COLOR }
        FarmIncidentType.GIANT_CROP -> runtime.state.specialIncident?.points.orEmpty().mapNotNull { point ->
            Bukkit.getWorld(point.world)?.let { Location(it, point.x, point.y, point.z) to AMBER_COLOR }
        }
        FarmIncidentType.CHANNELS -> runtime.state.specialIncident?.let { special ->
            special.points.mapIndexedNotNull { index, point ->
                if (index in special.active) null else {
                    Bukkit.getWorld(point.world)?.let { Location(it, point.x, point.y, point.z) to WATER_COLOR }
                }
            }
        }.orEmpty()
        FarmIncidentType.NIGHT_SHIFT -> runtime.state.specialIncident?.plots.orEmpty().mapNotNull(FarmPlotPosition::location)
            .map { it to NIGHT_COLOR }
        FarmIncidentType.MARKET -> points.resolve(runtime, FarmPointKind.CUSTOMER).let { point ->
            listOf(Location(runtime.region.world, point.x, point.y, point.z) to AMBER_COLOR)
        }
        null -> emptyList()
    }

    private fun spawnWaterTrail(player: Player, from: FarmPointPosition, to: FarmPointPosition) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val steps = (sqrt(dx * dx + dy * dy + dz * dz) * 2.0).toInt().coerceIn(1, 48)
        repeat(steps) { step ->
            val ratio = (step + 1).toDouble() / steps
            player.spawnParticle(
                Particle.SPLASH,
                Location(player.world, from.x + dx * ratio, from.y + dy * ratio + 0.2, from.z + dz * ratio),
                1,
                0.04,
                0.02,
                0.04,
                0.0,
            )
        }
    }

    private fun spawnColumn(player: Player, base: Location, color: Color) {
        val center = base.clone().toCenterLocation().add(0.0, 1.0, 0.0)
        val steps = settings().markerHeight * 2
        for (step in 0..steps) {
            player.spawnParticle(
                Particle.DUST,
                center.clone().add(0.0, step * 0.5, 0.0),
                if (step == 0 || step == steps) 4 else 2,
                0.22,
                0.12,
                0.22,
                0.0,
                Particle.DustOptions(color, if (step % 4 == 0) 2.2f else 1.7f),
                true,
            )
        }
    }

    private fun spawnSlimColumn(player: Player, base: Location, color: Color) {
        val center = base.clone().toCenterLocation().add(0.0, 1.0, 0.0)
        repeat(9) { step ->
            player.spawnParticle(
                Particle.DUST,
                center.clone().add(0.0, step * 0.5, 0.0),
                1,
                0.06,
                0.04,
                0.06,
                0.0,
                Particle.DustOptions(color, if (step % 4 == 0) 1.8f else 1.35f),
                true,
            )
        }
    }

    private fun spawnPlotMarker(player: Player, soil: Location, color: Color) {
        val center = soil.clone().toCenterLocation().add(0.0, 1.05, 0.0)
        repeat(4) { layer ->
            player.spawnParticle(
                Particle.DUST,
                center.clone().add(0.0, layer * 0.38, 0.0),
                3,
                0.18,
                0.08,
                0.18,
                0.0,
                Particle.DustOptions(color, if (layer == 0) 2.0f else 1.65f),
                true,
            )
        }
    }

    private fun spawnRing(player: Player, center: Location, radius: Double, color: Color) {
        repeat(24) { index ->
            val angle = 2.0 * PI * index / 24.0
            player.spawnParticle(
                Particle.DUST,
                center.clone().add(cos(angle) * radius, 0.35, sin(angle) * radius),
                2,
                0.08,
                0.04,
                0.08,
                0.0,
                Particle.DustOptions(color, 1.75f),
                true,
            )
        }
    }

    private fun cluster(plots: Set<FarmPlotPosition>, requestedClusters: Int): List<Set<FarmPlotPosition>> {
        if (plots.isEmpty()) return emptyList()
        val centers = mutableListOf(plots.first())
        while (centers.size < minOf(requestedClusters, plots.size)) {
            val next = plots.asSequence().filterNot(centers::contains).maxByOrNull { plot ->
                centers.minOf { center -> FarmPlotGeometry.horizontalDistanceSquared(plot, center) }
            } ?: break
            centers += next
        }
        val clusters = centers.associateWith { linkedSetOf<FarmPlotPosition>() }
        plots.forEach { plot -> clusters.getValue(centers.minBy { FarmPlotGeometry.horizontalDistanceSquared(plot, it) }) += plot }
        return clusters.values.filter { it.isNotEmpty() }
    }

    private fun players(runtime: FarmRuntime): List<Player> = port.players(runtime.region)

    private companion object {
        val TILL_COLOR: Color = Color.fromRGB(255, 173, 66)
        val PLANT_COLOR: Color = Color.fromRGB(199, 120, 255)
        val DROUGHT_COLOR: Color = Color.fromRGB(255, 122, 69)
        val AMBER_COLOR: Color = Color.fromRGB(255, 200, 87)
        val DELIVERY_COLOR: Color = Color.fromRGB(199, 120, 255)
        val DANGER_COLOR: Color = Color.fromRGB(255, 95, 109)
        val SUCCESS_COLOR: Color = Color.fromRGB(85, 217, 139)
        val WATER_COLOR: Color = Color.fromRGB(79, 195, 247)
        val NIGHT_COLOR: Color = Color.fromRGB(139, 211, 255)
        val POINT_COLORS = mapOf(
            FarmPointKind.TOOL to TILL_COLOR,
            FarmPointKind.SEEDS to PLANT_COLOR,
            FarmPointKind.WATER to WATER_COLOR,
            FarmPointKind.CRATES to AMBER_COLOR,
            FarmPointKind.RECEIVING to DELIVERY_COLOR,
            FarmPointKind.CART to AMBER_COLOR,
            FarmPointKind.CUSTOMER to SUCCESS_COLOR,
            FarmPointKind.TRAVEL to SUCCESS_COLOR,
            FarmPointKind.HIVE to AMBER_COLOR,
            FarmPointKind.IRRIGATION to WATER_COLOR,
            FarmPointKind.COVERS to Color.fromRGB(154, 140, 255),
            FarmPointKind.SCARECROWS to DANGER_COLOR,
            FarmPointKind.PEN to SUCCESS_COLOR,
        )
    }
}
