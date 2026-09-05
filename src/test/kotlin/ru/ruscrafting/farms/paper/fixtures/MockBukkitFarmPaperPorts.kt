package ru.ruscrafting.farms.paper.fixtures

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.TextDisplay
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import ru.ruscrafting.farms.paper.farm.care.mole.MoleBurrowChunkLease
import ru.ruscrafting.farms.paper.farm.care.mole.MoleBurrowChunkRetention
import ru.ruscrafting.farms.paper.platform.FarmBlockDataDecoder
import ru.ruscrafting.farms.paper.platform.FarmBlockPassability
import ru.ruscrafting.farms.paper.platform.FarmEntityRayTrace
import ru.ruscrafting.farms.paper.platform.FarmMobDespawnPolicy
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayStyle
import ru.ruscrafting.farms.paper.platform.FarmVehiclePassengerControl

/** Exact gameplay-observable approximations for gaps in the pinned MockBukkit 4.110.0 runtime. */
internal object MockBukkitFarmBlockPassability : FarmBlockPassability {
    override fun isPassable(block: Block): Boolean = !block.type.isSolid
}

internal object MockBukkitFarmBlockDataDecoder : FarmBlockDataDecoder {
    override fun decode(serialized: String): BlockData = runCatching {
        Bukkit.createBlockData(serialized)
    }.getOrElse { failure ->
        if (!isPinnedPointedDripstoneGap(serialized)) throw failure
        // MockBukkit 4.110.0 has no PointedDripstone BlockData implementation
        // and rejects Paper's valid thickness property. Tunnel tests only need
        // the material identity; every other malformed state must still fail.
        org.bukkit.Material.POINTED_DRIPSTONE.createBlockData()
    }

    private fun isPinnedPointedDripstoneGap(serialized: String): Boolean {
        val prefix = "minecraft:pointed_dripstone["
        if (!serialized.startsWith(prefix) || !serialized.endsWith(']')) return false
        val pairs = serialized.removePrefix(prefix).dropLast(1).split(',').map { property ->
            val parts = property.split('=', limit = 2)
            if (parts.size != 2) return false
            parts[0] to parts[1]
        }
        if (pairs.map { it.first }.distinct().size != pairs.size) return false
        val properties = pairs.toMap()
        if ("thickness" !in properties) return false
        return properties.all { (key, value) ->
            when (key) {
                "thickness" -> value in setOf("tip", "tip_merge", "frustum", "middle", "base")
                "vertical_direction" -> value in setOf("up", "down")
                "waterlogged" -> value in setOf("true", "false")
                else -> false
            }
        }
    }
}

internal object MockBukkitFarmTextDisplays : FarmTextDisplayRenderer {
    override fun render(entity: TextDisplay, text: Component, style: FarmTextDisplayStyle) {
        entity.text(text)
        entity.viewRange = style.viewRange
        entity.isPersistent = style.persistent
    }
}

internal object MockBukkitFarmMobDespawns : FarmMobDespawnPolicy {
    override fun setRemoveWhenFarAway(entity: LivingEntity, value: Boolean) = Unit
}

internal object MockBukkitFarmMobNavigation : ru.ruscrafting.farms.paper.platform.FarmMobNavigation {
    override fun moveTo(mob: org.bukkit.entity.Mob, target: Entity, speed: Double) = true
    override fun moveTo(mob: org.bukkit.entity.Mob, target: org.bukkit.Location, speed: Double) = true
}

internal object MockBukkitFarmVehiclePassengers : FarmVehiclePassengerControl {
    override fun ejectAll(entity: Entity): Boolean {
        val passengers = entity.passengers.toList()
        passengers.forEach(Entity::leaveVehicle)
        return passengers.isNotEmpty()
    }
}

internal object MockBukkitFarmEntityRayTrace : FarmEntityRayTrace {
    override fun trace(
        start: org.bukkit.Location,
        direction: Vector,
        maxDistance: Double,
        raySize: Double,
        filter: (Entity) -> Boolean,
    ): RayTraceResult? {
        val unit = direction.clone().normalize()
        val origin = start.toVector()
        return start.world.entities.asSequence().filter(filter).mapNotNull { entity ->
            val center = entity.location.toVector().add(Vector(0.0, entity.height * 0.5, 0.0))
            val offset = center.clone().subtract(origin)
            val along = offset.dot(unit)
            if (along !in 0.0..maxDistance) return@mapNotNull null
            val hitPosition = origin.clone().add(unit.clone().multiply(along))
            val hitRadius = raySize + entity.width * 0.5
            if (center.distanceSquared(hitPosition) > hitRadius * hitRadius) return@mapNotNull null
            along to RayTraceResult(hitPosition, entity)
        }.minByOrNull(Pair<Double, RayTraceResult>::first)?.second
    }
}

internal class MockBukkitMoleBurrowChunkRetention : MoleBurrowChunkRetention {
    private data class Key(val world: String, val x: Int, val z: Int)

    private val retained = linkedSetOf<Key>()
    private var closeFailuresRemaining = 0

    override fun retain(chunk: Chunk): MoleBurrowChunkLease {
        val key = chunk.key()
        check(retained.add(key)) { "Mole fixture acquired duplicate chunk lease: $key" }
        return object : MoleBurrowChunkLease {
            private var closed = false

            override fun close() {
                if (closed) return
                if (closeFailuresRemaining > 0) {
                    closeFailuresRemaining--
                    error("Injected mole chunk lease close failure")
                }
                check(retained.remove(key)) { "Mole fixture released an unknown chunk lease: $key" }
                closed = true
            }
        }
    }

    fun retainedCount(): Int = retained.size

    fun failNextCloseAttempts(count: Int) {
        require(count >= 0)
        closeFailuresRemaining = count
    }

    private fun Chunk.key() = Key(world.name, x, z)
}

/** Paper 1.21.10+ moves passengers with their seat; pinned MockBukkit still refuses that teleport. */
internal object MockBukkitFarmRivalRaidSeatMovement : ru.ruscrafting.farms.paper.platform.FarmRivalRaidSeatMovement {
    override fun move(seat: org.bukkit.entity.ArmorStand, destination: org.bukkit.Location): Boolean {
        (seat as org.mockbukkit.mockbukkit.entity.EntityMock).setLocation(destination.clone())
        seat.passengers.forEach { (it as org.mockbukkit.mockbukkit.entity.EntityMock).setLocation(destination.clone()) }
        return true
    }
}
