package ru.ruscrafting.farms.paper.mine.lift

import com.google.gson.Gson
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.persistence.AtomicFileStore
import java.nio.file.Path
import java.util.UUID

/** Location-only transport escrow; inventories, effects and game modes are never mutated by the lift. */
internal class MineLiftRecovery(root: Path) {
    data class ReturnPoint(val player: UUID, val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float)
    private val gson = Gson()
    private val store = AtomicFileStore(
        root, Path.of("data/mine-lift-passengers.json"), 262_144,
        encode = { entries: Array<ReturnPoint> -> gson.toJson(entries).toByteArray(Charsets.UTF_8) },
        decode = { gson.fromJson(it.toString(Charsets.UTF_8), Array<ReturnPoint>::class.java) },
        validate = { entries ->
            require(entries.size <= 1024 && entries.map { it.player }.distinct().size == entries.size)
            entries.forEach { require(it.world.isNotBlank() && listOf(it.x, it.y, it.z).all(Double::isFinite) && it.yaw.isFinite()) }
        },
    )
    private var entries = store.loadOrDefault { emptyArray() }.associateBy { it.player }
    fun contains(player: UUID) = player in entries
    val pendingCount: Int get() = entries.size

    fun capture(player: Player, safe: Location) {
        check(player.uniqueId !in entries) { "Mine lift passenger recovery is pending" }
        val next = entries + (player.uniqueId to ReturnPoint(player.uniqueId, safe.world.name, safe.x, safe.y, safe.z, safe.yaw))
        entries = store.write(next.values.toTypedArray()).associateBy { it.player }
    }

    fun destination(player: UUID): Location? = entries[player]?.let { saved ->
        Bukkit.getWorld(saved.world)?.let { Location(it, saved.x, saved.y, saved.z, saved.yaw, 0f) }
    }

    /** Save the verified safe player position before acknowledging the escrow. */
    fun acknowledge(player: Player) {
        player.saveData()
        entries = store.write(entries.filterKeys { it != player.uniqueId }.values.toTypedArray()).associateBy { it.player }
    }
}
