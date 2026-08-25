package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.Horse
import org.bukkit.entity.Pig
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.Material
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.FarmMachinePosition
import ru.ruscrafting.farms.domain.FarmSeederFormation

internal data class FarmSeederRig(
    val horse: Horse,
    val pigs: List<Pig>,
    val label: TextDisplay,
) {
    val entities: List<Entity> get() = listOf(horse, label) + pigs
}

internal enum class FarmSeederMountResult { MOUNTED, OCCUPIED, RIDER_BUSY, FAILED }

/** Owns the non-persistent horse-and-pigs scene; shift state remains its source of truth. */
internal class FarmSeederRigManager(plugin: Plugin) {
    private val pigIndexKey = NamespacedKey(plugin, "farm_seeder_pig_index")

    fun resolve(entities: Collection<Entity>, pigCount: Int): FarmSeederRig? {
        if (entities.size != pigCount + FIXED_ENTITY_COUNT) return null
        val horse = entities.filterIsInstance<Horse>().singleOrNull() ?: return null
        val label = entities.filterIsInstance<TextDisplay>().singleOrNull() ?: return null
        val pigsByIndex = entities.filterIsInstance<Pig>().associateBy { pig ->
            pig.persistentDataContainer.get(pigIndexKey, PersistentDataType.INTEGER) ?: return null
        }
        if (pigsByIndex.size != pigCount || pigsByIndex.keys != (0 until pigCount).toSet()) return null
        return FarmSeederRig(horse, (0 until pigCount).map(pigsByIndex::getValue), label)
    }

    fun spawn(
        location: Location,
        pigCount: Int,
        leadDistance: Double,
        spacing: Double,
        horseSpeed: Double,
        pigSpeed: Double,
        labelText: Component,
        labelViewRange: Float,
        mark: (Entity) -> Unit,
        validPosition: (Location) -> Boolean,
    ): FarmSeederRig {
        val world = location.world
        val spawned = mutableListOf<Entity>()
        try {
            val horse = world.spawn(location, Horse::class.java) { entity ->
                entity.setAdult()
                entity.isPersistent = false
                entity.removeWhenFarAway = false
                entity.isInvulnerable = true
                entity.isCollidable = false
                entity.isGlowing = true
                entity.isAware = false
                entity.isTamed = true
                entity.inventory.saddle = ItemStack(Material.SADDLE)
                entity.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = horseSpeed
                mark(entity)
            }.also(spawned::add)
            val desired = positions(horse, pigCount, leadDistance, spacing)
            val pigs = desired.mapIndexed { index, position ->
                val target = position.location(world).takeIf(validPosition) ?: location
                world.spawn(target, Pig::class.java) { entity ->
                    entity.setAdult()
                    entity.isPersistent = false
                    entity.removeWhenFarAway = false
                    entity.isInvulnerable = true
                    entity.isCollidable = false
                    entity.isGlowing = true
                    entity.isAware = false
                    entity.setCanPickupItems(false)
                    entity.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = pigSpeed
                    entity.persistentDataContainer.set(pigIndexKey, PersistentDataType.INTEGER, index)
                    mark(entity)
                }.also(spawned::add)
            }
            pigs.forEach { pig -> pig.setLeashHolder(horse) }
            val label = world.spawn(location.clone().add(0.0, 2.25, 0.0), TextDisplay::class.java) { entity ->
                entity.text(labelText)
                entity.billboard = org.bukkit.entity.Display.Billboard.VERTICAL
                entity.alignment = TextDisplay.TextAlignment.CENTER
                entity.backgroundColor = org.bukkit.Color.fromARGB(128, 16, 16, 16)
                entity.isShadowed = true
                entity.viewRange = labelViewRange
                entity.isPersistent = false
                mark(entity)
            }.also(spawned::add)
            return FarmSeederRig(horse, pigs, label)
        } catch (failure: Exception) {
            spawned.forEach(Entity::remove)
            throw failure
        }
    }

    fun mount(rig: FarmSeederRig, player: Player): FarmSeederMountResult {
        val rider = rig.horse.passengers.filterIsInstance<Player>().firstOrNull()
        if (rider != null && rider.uniqueId != player.uniqueId) return FarmSeederMountResult.OCCUPIED
        if (player.isInsideVehicle && player.vehicle?.uniqueId != rig.horse.uniqueId) {
            return FarmSeederMountResult.RIDER_BUSY
        }
        rig.horse.isTamed = true
        rig.horse.owner = player
        rig.horse.isAware = true
        if (rider?.uniqueId == player.uniqueId || rig.horse.addPassenger(player)) return FarmSeederMountResult.MOUNTED
        return FarmSeederMountResult.FAILED
    }

    fun rider(rig: FarmSeederRig): Player? = rig.horse.passengers.filterIsInstance<Player>().singleOrNull()

    fun park(rig: FarmSeederRig) {
        rig.horse.isAware = false
        rig.pigs.forEach { pig ->
            pig.isAware = false
            pig.pathfinder.stopPathfinding()
            if (!pig.isLeashed || runCatching { pig.leashHolder }.getOrNull() != rig.horse) {
                pig.setLeashHolder(rig.horse)
            }
        }
    }

    fun update(
        rig: FarmSeederRig,
        pigCount: Int,
        leadDistance: Double,
        spacing: Double,
        catchupDistance: Double,
        validPosition: (Location) -> Boolean,
    ): List<FarmMachinePosition> {
        rig.label.teleport(rig.horse.location.clone().add(0.0, 2.25, 0.0))
        rig.horse.isAware = true
        val desired = positions(rig.horse, pigCount, leadDistance, spacing)
        val catchupSquared = catchupDistance * catchupDistance
        rig.pigs.zip(desired).forEach { (pig, position) ->
            val target = position.location(rig.horse.world)
            if (!pig.isLeashed || runCatching { pig.leashHolder }.getOrNull() != rig.horse) {
                pig.setLeashHolder(rig.horse)
            }
            pig.isAware = true
            if (pig.world != target.world || pig.location.distanceSquared(target) > catchupSquared) {
                if (validPosition(target)) pig.teleport(target)
            } else if (pig.location.distanceSquared(target) > MIN_MOVE_DISTANCE_SQUARED) {
                pig.pathfinder.moveTo(target, PIG_PATH_SPEED)
            }
        }
        return rig.pigs.asSequence()
            .filter(Entity::isValid)
            .map { pig -> FarmMachinePosition(pig.world.name, pig.location.x, pig.location.y, pig.location.z) }
            .toList()
    }

    fun release(rig: FarmSeederRig) {
        rig.horse.eject()
        park(rig)
    }

    private fun positions(
        horse: Horse,
        pigCount: Int,
        leadDistance: Double,
        spacing: Double,
    ): List<FarmMachinePosition> {
        val location = horse.location
        val direction = location.direction
        return FarmSeederFormation.pigPositions(
            world = horse.world.name,
            horseX = location.x,
            horseY = location.y,
            horseZ = location.z,
            directionX = direction.x,
            directionZ = direction.z,
            pigCount = pigCount,
            leadDistance = leadDistance,
            spacing = spacing,
        )
    }

    private fun FarmMachinePosition.location(world: org.bukkit.World): Location = Location(world, x, y, z)

    private companion object {
        const val FIXED_ENTITY_COUNT = 2
        const val PIG_PATH_SPEED = 1.45
        const val MIN_MOVE_DISTANCE_SQUARED = 0.36
    }
}
