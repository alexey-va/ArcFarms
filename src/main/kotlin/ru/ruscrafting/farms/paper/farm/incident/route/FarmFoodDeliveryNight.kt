package ru.ruscrafting.farms.paper.farm.incident.route

import org.bukkit.Bukkit
import org.bukkit.GameRule
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID

/** One frozen world clock shared by all delivery zones in that exact world. */
internal class FarmFoodDeliveryNight(plugin: Plugin) {
    private val logger = plugin.logger
    private val recoveryKey = NamespacedKey(plugin, "farm_food_delivery_night_v1")
    private val owners = mutableMapOf<UUID, MutableSet<String>>()

    fun hasOwner(zoneId: String): Boolean = owners.values.any { zoneId in it }

    fun hold(zoneId: String, world: World, time: Long) {
        check(Bukkit.isPrimaryThread())
        require(time in 0..24_000L)
        if (owners.any { (worldId, zones) -> worldId != world.uid && zoneId in zones }) release(zoneId)
        val saved = world.persistentDataContainer.get(recoveryKey, PersistentDataType.LONG_ARRAY)
        if (saved == null) {
            val cycle = checkNotNull(world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE))
            // The baseline and world clock/rules share the same persisted level data.
            world.persistentDataContainer.set(recoveryKey, PersistentDataType.LONG_ARRAY,
                longArrayOf(world.fullTime, if (cycle) 1L else 0L))
        } else validate(saved, world)
        val zones = owners.getOrPut(world.uid, ::linkedSetOf)
        val acquired = zones.add(zoneId)
        if (world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) != false) {
            check(world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)) { "Could not freeze delivery time in ${world.name}" }
        }
        val target = Math.floorMod(time, 24_000L)
        if (world.time != target) world.time = target
        check(world.time == target && world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) == false) {
            "Delivery world night was not applied: zone=$zoneId world=${world.name} target=$target"
        }
        if (acquired) logger.info("FARM_DELIVERY_NIGHT action=acquire zone=$zoneId world=${world.name} " +
            "world_id=${world.uid} target=$target owners=${zones.size}")
    }

    fun release(zoneId: String) {
        check(Bukkit.isPrimaryThread())
        owners.toMap().forEach { (worldId, zones) ->
            if (zoneId in zones) {
                if (zones.size > 1) zones.remove(zoneId)
                else {
                    val world = Bukkit.getWorld(worldId) ?: return@forEach
                    // A failed restore must retain both this owner and its persisted baseline for retry.
                    restore(world, "release:$zoneId")
                    owners.remove(worldId)
                }
            }
        }
    }

    /** Startup also restores crash-left clocks, including worlds without a current delivery zone. */
    fun cleanup() {
        check(Bukkit.isPrimaryThread())
        var failure: Throwable? = null
        Bukkit.getWorlds().forEach { world ->
            runCatching {
                restore(world, "cleanup")
                owners.remove(world.uid)
            }.exceptionOrNull()?.let { problem ->
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
        }
        failure?.let { throw IllegalStateException("Could not restore delivery world time", it) }
    }

    private fun restore(world: World, reason: String) {
        val saved = world.persistentDataContainer.get(recoveryKey, PersistentDataType.LONG_ARRAY) ?: return
        validate(saved, world)
        world.fullTime = saved[0]
        val cycle = saved[1] == 1L
        check(world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, cycle)) { "Could not restore daylight cycle in ${world.name}" }
        check(world.fullTime == saved[0] && world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) == cycle) {
            "Delivery world time restore was not applied in ${world.name}"
        }
        world.persistentDataContainer.remove(recoveryKey)
        logger.info("FARM_DELIVERY_NIGHT action=restore world=${world.name} world_id=${world.uid} " +
            "full_time=${saved[0]} daylight_cycle=$cycle reason=$reason")
    }

    private fun validate(saved: LongArray, world: World) {
        check(saved.size == 2 && saved[1] in 0L..1L) { "Invalid delivery world time recovery record in ${world.name}" }
    }
}
