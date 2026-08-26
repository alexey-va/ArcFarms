package ru.ruscrafting.farms.paper.farm.point

import org.bukkit.Location
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.TeleportDestination
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmLocationOverrides
import ru.ruscrafting.farms.domain.FarmPlotGeometry
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.persistence.FarmLocationRepository
import kotlin.math.cos
import kotlin.math.sin

/** Sole owner of configured farm point overrides and deterministic base points. */
internal class FarmPointService(
    private val settings: () -> ArcFarmsConfig,
    private val repository: FarmLocationRepository,
) {
    private var overrides = FarmLocationOverrides()

    fun load(): FarmLocationOverrides = repository.load().also { overrides = it }

    fun snapshot(): FarmLocationOverrides = overrides

    fun overridden(zoneId: String): Set<FarmPointKind> = overrides.zones[zoneId].orEmpty().keys

    fun configured(zoneId: String, kind: FarmPointKind): FarmPointPosition? = overrides.zones[zoneId]?.get(kind)

    fun save(
        zoneId: String,
        kind: FarmPointKind,
        position: FarmPointPosition,
        validate: (FarmLocationOverrides) -> Unit,
    ) {
        val previous = overrides
        val zone = overrides.zones[zoneId].orEmpty() + (kind to position)
        val candidate = overrides.copy(zones = overrides.zones + (zoneId to zone))
        try {
            validate(candidate)
            repository.saveBlocking(candidate)
            overrides = candidate
        } catch (failure: Exception) {
            overrides = previous
            throw failure
        }
    }

    fun clear(
        zoneId: String,
        kind: FarmPointKind,
        validate: (FarmLocationOverrides) -> Unit,
    ): Boolean {
        val previous = overrides
        val candidate = overrides.without(zoneId, kind)
        if (candidate == previous) return false
        try {
            validate(candidate)
            repository.saveBlocking(candidate)
            overrides = candidate
        } catch (failure: Exception) {
            overrides = previous
            throw failure
        }
        return true
    }

    fun destination(kind: ActivityKind, runtimes: Collection<FarmRuntime>): TeleportDestination {
        val configured = settings().destinations.getValue(kind.configKey)
        if (kind != ActivityKind.FARM) return configured
        val runtime = runtimes.firstOrNull() ?: return configured
        val override = configured(runtime.settings.id, FarmPointKind.TRAVEL) ?: return configured
        return configured.copy(
            world = override.world,
            x = override.x,
            y = override.y,
            z = override.z,
            yaw = override.yaw,
            pitch = override.pitch,
        )
    }

    fun resolveBase(runtime: FarmRuntime, kind: FarmPointKind): FarmPointPosition {
        configured(runtime.settings.id, kind)?.let { return it }
        val zone = runtime.settings
        return when (kind) {
            FarmPointKind.TOOL -> zone.supplies.tool.toPoint()
            FarmPointKind.SEEDS -> zone.supplies.seeds.toPoint()
            FarmPointKind.WATER -> zone.supplies.water.toPoint()
            FarmPointKind.CRATES -> zone.delivery.pickup.toPoint()
            FarmPointKind.RECEIVING -> FarmPointPosition(zone.delivery.world, zone.delivery.x, zone.delivery.y, zone.delivery.z)
            FarmPointKind.CART -> resolveBase(runtime, FarmPointKind.CRATES)
            FarmPointKind.CUSTOMER -> customerPoint(runtime)
            FarmPointKind.TRAVEL -> settings().destinations.getValue(ActivityKind.FARM.configKey).let { destination ->
                FarmPointPosition(
                    destination.world,
                    destination.x,
                    destination.y,
                    destination.z,
                    destination.yaw,
                    destination.pitch,
                )
            }
            FarmPointKind.HIVE,
            FarmPointKind.IRRIGATION,
            FarmPointKind.COVERS,
            FarmPointKind.SCARECROWS,
            FarmPointKind.PEN,
            -> FarmPlotGeometry.center(runtime.state.preparationPatch)?.let { plot ->
                FarmPointPosition(plot.world, plot.x + 0.5, plot.y + 1.0, plot.z + 0.5)
            } ?: zone.supplies.tool.toPoint()
        }
    }

    private fun customerPoint(runtime: FarmRuntime): FarmPointPosition {
        val receiving = resolveBase(runtime, FarmPointKind.RECEIVING)
        val radians = Math.toRadians(receiving.yaw.toDouble())
        val candidate = FarmPointPosition(
            receiving.world,
            receiving.x - sin(radians) * 1.8,
            receiving.y,
            receiving.z + cos(radians) * 1.8,
            receiving.yaw + 180f,
            0f,
        )
        val opposite = FarmPointPosition(
            receiving.world,
            receiving.x + sin(radians) * 1.8,
            receiving.y,
            receiving.z - cos(radians) * 1.8,
            receiving.yaw,
            0f,
        )
        return listOf(candidate, opposite).firstOrNull { point ->
            runtime.region.contains(Location(runtime.region.world, point.x, point.y, point.z))
        } ?: receiving.copy(yaw = receiving.yaw + 180f, pitch = 0f)
    }

    private val ActivityKind.configKey: String
        get() = when (this) {
            ActivityKind.FARM -> "farm"
            ActivityKind.LUMBER -> "lumber"
            ActivityKind.MINE -> "mine"
        }

    private fun ru.ruscrafting.farms.config.FarmSupplyPointSettings.toPoint(): FarmPointPosition =
        FarmPointPosition(world, x, y, z)
}
