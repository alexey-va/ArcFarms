package ru.ruscrafting.farms.paper.farm.incident.action

import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Ghast
import org.bukkit.entity.Mob
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRivalFieldPolicy
import ru.ruscrafting.farms.domain.FarmRivalPatrolPlanner
import ru.ruscrafting.farms.domain.MAX_FARM_SPECIAL_PLOTS
import ru.ruscrafting.farms.paper.FarmNightShiftController
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.platform.FarmMobDespawnPolicy
import ru.ruscrafting.farms.paper.platform.FarmMobNavigation
import kotlin.math.ceil

/** Owns the moving local worker swarm for a rival raid. */
internal class FarmRivalRaidWorkers(
    plugin: Plugin,
    private val locale: ArcFarmsLocale,
    private val beds: FarmIncidentBedProvider,
    private val mobDespawns: FarmMobDespawnPolicy,
    private val mobNavigation: FarmMobNavigation,
    private val nightShift: FarmNightShiftController,
) {
    private data class Swarm(
        val sequence: Long,
        val placementSequence: Long,
        val fieldPlots: List<FarmPlotPosition>,
        val workerIds: MutableSet<java.util.UUID> = linkedSetOf(),
        var spawnSequence: Int = 0,
        var patrolCursor: Int = 0,
    )

    private val zoneKey = NamespacedKey(plugin, "farm_raid_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_raid_sequence")
    private val roleKey = NamespacedKey(plugin, "farm_raid_role")
    private val targetKey = NamespacedKey(plugin, "farm_raid_target")
    private val swarms = mutableMapOf<String, Swarm>()

    fun start(runtime: FarmRuntime, fieldPlots: List<FarmPlotPosition>) {
        clear(runtime.settings.id)
        swarms[runtime.settings.id] = Swarm(runtime.state.sequence, runtime.state.placementSequence, fieldPlots)
    }

    fun ensure(runtime: FarmRuntime, ghast: Ghast, fallbackPlots: List<FarmPlotPosition>) {
        val swarm = swarms.getOrPut(runtime.settings.id) {
            Swarm(runtime.state.sequence, runtime.state.placementSequence, fallbackPlots)
        }
        if (swarm.sequence != runtime.state.sequence) {
            start(runtime, fallbackPlots)
            return
        }
        swarm.workerIds.removeIf { Bukkit.getEntity(it)?.isValid != true }
        val threat = ghast.point()
        val retireRadiusSquared = runtime.settings.rivalRaid.workerRetireRadius * runtime.settings.rivalRaid.workerRetireRadius
        swarm.workerIds.removeIf { workerId ->
            val worker = Bukkit.getEntity(workerId) as? Mob
            val retired = worker?.isValid != true || worker.world !== ghast.world ||
                worker.location.horizontalDistanceSquared(ghast.location) > retireRadiusSquared
            if (retired) {
                worker?.remove()
                nightShift.releaseExternalLight(lightOwner(runtime.settings.id, workerId))
            }
            retired
        }
        val localPlots = localPlots(swarm, threat, runtime.settings.rivalRaid.workerFocusRadius)
        if (localPlots.isEmpty()) return
        val spawnBatch = minOf(
            runtime.settings.rivalRaid.workerSpawnBatchSize,
            runtime.settings.rivalRaid.workerCount - swarm.workerIds.size,
        )
        repeat(spawnBatch) {
            val index = swarm.spawnSequence++
            val selectedPlot = localPlots[Math.floorMod(index, localPlots.size)]
            val selectedIndex = swarm.fieldPlots.indexOf(selectedPlot)
            val spawn = selectedPlot.spawnLocation(index.toLong()) ?: return@repeat
            val mob = spawn.world.spawnEntity(
                spawn,
                EntityType.valueOf(runtime.settings.rivalRaid.workerEntity),
            ) as? Mob ?: return@repeat
            mob.isPersistent = false
            mobDespawns.setRemoveWhenFarAway(mob, false)
            mob.target = null
            mob.isGlowing = true
            mob.getAttribute(Attribute.MAX_HEALTH)?.baseValue = runtime.settings.rivalRaid.workerHealth
            mob.health = runtime.settings.rivalRaid.workerHealth
            mob.customName(locale.render(MessageKey.FARM_RIVAL_RAID_WORKER))
            mob.equipment.setItemInMainHand(
                ItemStack(MaterialRules.material(runtime.settings.rivalRaid.workerHeldItem)),
                true,
            )
            mob.equipment.itemInMainHandDropChance = 0.0f
            mark(mob, runtime, index)
            mob.persistentDataContainer.set(targetKey, PersistentDataType.INTEGER, selectedIndex)
            swarm.workerIds += mob.uniqueId
            move(runtime, swarm, mob, threat, index.toLong())
        }
        swarm.workerIds.forEachIndexed { index, workerId ->
            val worker = Bukkit.getEntity(workerId) as? Mob ?: return@forEachIndexed
            if (index % runtime.settings.rivalRaid.workerLightStride == 0) {
                nightShift.updateExternalLight(
                    lightOwner(runtime.settings.id, workerId),
                    worker,
                    runtime.settings.rivalRaid.workerLightLevel,
                )
            } else nightShift.releaseExternalLight(lightOwner(runtime.settings.id, workerId))
        }
    }

    fun patrol(runtime: FarmRuntime, threat: FarmPointPosition) {
        val swarm = swarms[runtime.settings.id] ?: return
        val workers = swarm.workerIds.toList()
        if (workers.isEmpty()) return
        repeat(minOf(runtime.settings.rivalRaid.workerPatrolBatchSize, workers.size)) { offset ->
            val workerId = workers[Math.floorMod(swarm.patrolCursor + offset, workers.size)]
            val worker = Bukkit.getEntity(workerId) as? Mob ?: return@repeat
            worker.target = null
            move(runtime, swarm, worker, threat, swarm.patrolCursor.toLong() + offset)
        }
        swarm.patrolCursor = Math.floorMod(
            swarm.patrolCursor + runtime.settings.rivalRaid.workerPatrolBatchSize,
            workers.size,
        )
    }

    fun fieldPlots(runtime: FarmRuntime, rival: FarmPointPosition): List<FarmPlotPosition> {
        val world = Bukkit.getWorld(rival.world) ?: return emptyList()
        val radius = ceil(runtime.settings.rivalRaid.workerRadius).toInt()
        val radiusSquared = runtime.settings.rivalRaid.workerRadius * runtime.settings.rivalRaid.workerRadius
        val indexed = if (runtime.region.contains(Location(world, rival.x, rival.y, rival.z))) {
            beds.discover(runtime).asSequence()
                .filter { it.world == rival.world && it.horizontalDistanceSquared(rival) <= radiusSquared }
                .filter { eligiblePlot(runtime, world, it) }
                .toList()
        } else emptyList()
        val eligible = indexed.ifEmpty {
            buildList {
                for (x in rival.x.toInt() - radius..rival.x.toInt() + radius) {
                    for (z in rival.z.toInt() - radius..rival.z.toInt() + radius) {
                        val dx = x + 0.5 - rival.x
                        val dz = z + 0.5 - rival.z
                        if (dx * dx + dz * dz > radiusSquared) continue
                        val loaded = world.isChunkLoaded(x shr 4, z shr 4) || world.loadChunk(x shr 4, z shr 4, true)
                        if (!loaded) continue
                        val highestY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING)
                        val soil = world.getBlockAt(x, highestY, z)
                        val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
                        val head = crop.getRelative(org.bukkit.block.BlockFace.UP)
                        if (FarmRivalFieldPolicy.isEligible(
                                loaded = true,
                                outdoor = highestY == soil.y,
                                farmland = soil.type == Material.FARMLAND &&
                                    (crop.type.isAir || crop.type.name in runtime.settings.crops),
                                headroom = head.isPassable,
                            )
                        ) add(FarmPlotPosition(world.name, x, soil.y, z))
                    }
                }
            }
        }
        return FarmRivalFieldPolicy.distribute(eligible, MAX_FARM_SPECIAL_PLOTS, runtime.state.placementSequence)
    }

    fun ids(zoneId: String): Set<java.util.UUID> = swarms[zoneId]?.workerIds.orEmpty()

    fun contains(zoneId: String, entityId: java.util.UUID): Boolean = entityId in ids(zoneId)

    fun remove(zoneId: String, entityId: java.util.UUID) {
        swarms[zoneId]?.workerIds?.remove(entityId)
        nightShift.releaseExternalLight(lightOwner(zoneId, entityId))
    }

    fun owns(entity: Entity): Boolean =
        entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING) == ROLE_WORKER

    fun clear(zoneId: String) {
        swarms.remove(zoneId)?.workerIds.orEmpty().forEach { Bukkit.getEntity(it)?.remove() }
        nightShift.releaseExternalLights(lightPrefix(zoneId))
    }

    private fun move(runtime: FarmRuntime, swarm: Swarm, worker: Mob, threat: FarmPointPosition, sequence: Long) {
        val target = FarmRivalPatrolPlanner.select(
            localPlots(swarm, threat, runtime.settings.rivalRaid.workerFocusRadius),
            FarmPointPosition(worker.world.name, worker.location.x, worker.location.y, worker.location.z),
            threat,
            swarm.placementSequence * 1_000_003L + sequence * 97L,
        ) ?: return
        val targetIndex = swarm.fieldPlots.indexOf(target)
        if (targetIndex >= 0) worker.persistentDataContainer.set(targetKey, PersistentDataType.INTEGER, targetIndex)
        target.spawnLocation(sequence)?.let { mobNavigation.moveTo(worker, it, runtime.settings.rivalRaid.workerPatrolSpeed) }
    }

    private fun localPlots(swarm: Swarm, center: FarmPointPosition, radius: Double): List<FarmPlotPosition> {
        val radiusSquared = radius * radius
        return swarm.fieldPlots.asSequence()
            .filter { it.world == center.world && it.horizontalDistanceSquared(center) <= radiusSquared }
            .sortedWith(compareBy<FarmPlotPosition> { it.horizontalDistanceSquared(center) }
                .thenBy { it.x }.thenBy { it.z }.thenBy { it.y })
            .toList()
    }

    private fun eligiblePlot(runtime: FarmRuntime, world: org.bukkit.World, plot: FarmPlotPosition): Boolean {
        val loaded = world.isChunkLoaded(plot.x shr 4, plot.z shr 4) || world.loadChunk(plot.x shr 4, plot.z shr 4, true)
        if (!loaded) return false
        val soil = world.getBlockAt(plot.x, plot.y, plot.z)
        val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
        val head = crop.getRelative(org.bukkit.block.BlockFace.UP)
        return FarmRivalFieldPolicy.isEligible(
            loaded = true,
            outdoor = world.getHighestBlockYAt(plot.x, plot.z, HeightMap.MOTION_BLOCKING) == soil.y,
            farmland = soil.type == Material.FARMLAND && (crop.type.isAir || crop.type.name in runtime.settings.crops),
            headroom = head.isPassable,
        )
    }

    private fun mark(entity: Entity, runtime: FarmRuntime, target: Int) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, ROLE_WORKER)
        entity.persistentDataContainer.set(targetKey, PersistentDataType.INTEGER, target)
    }

    private fun FarmPlotPosition.spawnLocation(sequence: Long): Location? {
        val center = block()?.location?.toCenterLocation()?.add(0.0, 1.0, 0.0) ?: return null
        val phase = Math.floorMod(sequence, 8L).toDouble() / 8.0 * Math.PI * 2.0
        return center.add(kotlin.math.cos(phase) * 0.32, 0.0, kotlin.math.sin(phase) * 0.32)
    }
    private fun Ghast.point() = FarmPointPosition(world.name, location.x, location.y, location.z)
    private fun FarmPlotPosition.horizontalDistanceSquared(point: FarmPointPosition): Double {
        val dx = x + 0.5 - point.x
        val dz = z + 0.5 - point.z
        return dx * dx + dz * dz
    }
    private fun Location.horizontalDistanceSquared(other: Location): Double {
        val dx = x - other.x
        val dz = z - other.z
        return dx * dx + dz * dz
    }
    private fun lightPrefix(zoneId: String) = "raid:$zoneId:worker:"
    private fun lightOwner(zoneId: String, workerId: java.util.UUID) = lightPrefix(zoneId) + workerId

    private companion object {
        const val ROLE_WORKER = "raid_worker"
    }
}
