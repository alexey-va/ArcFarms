package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.paper.worksite.WorksiteCarryable
import ru.ruscrafting.farms.paper.worksite.WorksiteCarryPosition
import ru.ruscrafting.farms.paper.worksite.WorksiteCrankTethers
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Presentation only: the expedition's existing cargo lease owns pickup, credit and retirement.
 * Farm processing carries light parcels; these heavy loads instead stay in front of a factory worker
 * and roll forward under the worker's push.
 * The shared tether owns the native leash anchor; every visible part uses the shared packet renderer.
 */
internal class MineFactoryCarts(private val plugin: Plugin, private val visuals: MineFactoryCartVisuals = PacketMineFactoryCartVisuals(plugin)) {
    class Cart(val scope: String, val owner: UUID, var at: Location,
        val body: MineFactoryCartVisuals.Body,
        var yaw: Float, var phase: Float = 0f, var nextSound: Long = 0L, var unloaded: Boolean = false,
        var lastPlayer: Location,
        var pushDirectionX: Double,
        var pushDirectionZ: Double)
    private val tethers = WorksiteCrankTethers(NamespacedKey(plugin, "mine_factory_cart_tether"))

    fun spawn(scope: String, player: Player, material: Material): Cart = spawnInternal(scope, player, material, null)

    /** Spawn a front-carried packet model while preserving the ordinary cart lease. */
    fun spawnModel(scope: String, player: Player, material: Material, model: String): Cart =
        spawnInternal(scope, player, material, model)

    private fun spawnInternal(scope: String, player: Player, material: Material, modelOverride: String?): Cart {
        val at = WorksiteCarryable.carriedLocation(player, CART_DISTANCE, 0.0, position = WorksiteCarryPosition.FRONT)
        at.yaw = 0f; at.pitch = 0f
        val pushDirection = horizontalDirection(player)
        if (hypot(at.x - player.location.x, at.z - player.location.z) < CART_DISTANCE - POSITION_EPSILON) {
            at.x = player.location.x + pushDirection.first * CART_DISTANCE
            at.z = player.location.z + pushDirection.second * CART_DISTANCE
        }
        val cartModel = modelOverride ?: when (material) {
            Material.COAL_BLOCK -> "cargo_cart_coal"
            // The crusher charge is physically the same heavy load before and
            // after processing; its dedicated blueprint makes that chain
            // readable without inventing a second cargo lease.
            Material.RAW_IRON_BLOCK -> "cargo_cart_charge"
            else -> "cargo_cart_iron"
        }
        // Keep old plugin jars able to load a restored journal while the
        // connected-line blueprint is rolled out; the new resource is still
        // selected whenever it is present.
        val parts = runCatching { MineDisplayBlueprints.model(cartModel) }
            .getOrElse { MineDisplayBlueprints.model("cargo_cart_iron") }
        var body: MineFactoryCartVisuals.Body? = null
        try {
            tethers.attach(scope, player, at.clone().add(0.0, .85, 0.0)).getOrThrow()
            body = visuals.spawn(at,parts)
            return Cart(
                scope, player.uniqueId, at, body, handleYaw(at, player.location),
                lastPlayer = player.location.clone(),
                pushDirectionX = pushDirection.first,
                pushDirectionZ = pushDirection.second,
            ).also { render(it) }
        } catch (failure: Throwable) {
            body?.remove()
            tethers.release(scope, player.uniqueId)
            throw failure
        }
    }

    /**
     * Keep the cart ahead of the worker without making a camera-only turn orbit it. A translation
     * adopts the current facing as the push direction; the cart then eases toward that front point.
     * This keeps normal walking, strafing, turning and walking backward on one smooth path.
     */
    fun move(cart: Cart, player: Player, now: Long): Boolean {
        if(cart.unloaded) return true
        val to = player.location
        if(to.world !== cart.at.world || to.distanceSquared(cart.at) > 100 || kotlin.math.abs(to.y-cart.at.y)>3) return false
        val previousPlayer = cart.lastPlayer
        val playerDx = to.x - previousPlayer.x
        val playerDz = to.z - previousPlayer.z
        if (playerDx * playerDx + playerDz * playerDz > PLAYER_TRANSLATION_EPSILON) {
            val direction = horizontalDirection(player)
            cart.pushDirectionX = direction.first
            cart.pushDirectionZ = direction.second
        }
        cart.lastPlayer = to.clone()

        val targetX = to.x + cart.pushDirectionX * CART_DISTANCE
        val targetZ = to.z + cart.pushDirectionZ * CART_DISTANCE
        val dx = targetX - cart.at.x
        val dz = targetZ - cart.at.z
        val horizontalDistance = hypot(dx, dz)
        val verticalDistance = to.y - cart.at.y
        var moved = false
        if (horizontalDistance > POSITION_EPSILON) {
            val step = min(horizontalDistance, MAX_STEP)
            val beforeX = cart.at.x
            val beforeZ = cart.at.z
            cart.at.add(dx / horizontalDistance * step, verticalDistance.coerceIn(-MAX_VERTICAL_STEP, MAX_VERTICAL_STEP),
                dz / horizontalDistance * step)
            val travelX = cart.at.x - beforeX
            val travelZ = cart.at.z - beforeZ
            val towardPlayerX = to.x - cart.at.x
            val towardPlayerZ = to.z - cart.at.z
            val towardPlayerLength = hypot(towardPlayerX, towardPlayerZ)
            if (towardPlayerLength > DIRECTION_EPSILON) {
                cart.phase += ((travelX * towardPlayerX + travelZ * towardPlayerZ) /
                    towardPlayerLength / WHEEL_ROLL_DISTANCE).toFloat()
            }
            moved = true
        } else if (kotlin.math.abs(verticalDistance) > POSITION_EPSILON) {
            cart.at.y += verticalDistance.coerceIn(-MAX_VERTICAL_STEP, MAX_VERTICAL_STEP)
            moved = true
        }

        val beforeYaw = cart.yaw
        cart.yaw = steerYaw(cart.yaw, handleYaw(cart.at, to, cart.yaw))
        if (moved || cart.yaw != beforeYaw) {
            render(cart)
            if(now>=cart.nextSound && plugin.config.getBoolean("ui.sounds",true)) {
                cart.nextSound=now+650
                cart.at.world.playSound(cart.at, Sound.BLOCK_CHAIN_STEP,.25f,.7f)
            }
        }
        return tethers.attach(cart.scope,player,cart.at.clone().add(0.0,.85,0.0)).isSuccess
    }

    /** Lift just the iron off the cart and onto the press; the empty chassis is retired here. */
    fun unload(cart: Cart) {
        if(cart.unloaded) return
        cart.unloaded=true
        tethers.release(cart.scope,cart.owner)
        cart.body.remove()
        // The press owns its workpiece via MineExpeditionMachinery; no second cargo entity survives unloading.
    }
    private fun render(cart: Cart) = cart.body.render(cart.at,cart.yaw,cart.phase)
    fun remove(cart: Cart) { tethers.release(cart.scope,cart.owner);cart.body.remove() }
    fun owns(entity: Entity)=tethers.owns(entity)
    fun removeOrphans(entities: Iterable<Entity>)=tethers.removeOrphans(entities)
    fun close() = visuals.close()

    private fun handleYaw(from: Location, to: Location, fallback: Float = 0f): Float {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return if (dx * dx + dz * dz <= DIRECTION_EPSILON) fallback
        else Math.toDegrees(atan2(-dx, dz)).toFloat()
    }

    private fun steerYaw(current: Float, target: Float): Float {
        var delta = (target - current) % 360f
        if (delta <= -180f) delta += 360f
        if (delta > 180f) delta -= 360f
        return current + delta.coerceIn(-MAX_STEER_DEGREES, MAX_STEER_DEGREES)
    }

    private fun horizontalDirection(player: Player): Pair<Double, Double> {
        val direction = player.location.direction.setY(0.0)
        if (direction.lengthSquared() > DIRECTION_EPSILON) {
            direction.normalize()
            return direction.x to direction.z
        }
        val radians = Math.toRadians(player.location.yaw.toDouble())
        return -sin(radians) to cos(radians)
    }

    private companion object {
        const val CART_DISTANCE = 1.9
        const val MAX_STEP = .7
        const val MAX_VERTICAL_STEP = .15
        const val WHEEL_ROLL_DISTANCE = .32
        const val MAX_STEER_DEGREES = 22f
        const val POSITION_EPSILON = .001
        const val DIRECTION_EPSILON = .001
        const val PLAYER_TRANSLATION_EPSILON = .0004
    }
}
