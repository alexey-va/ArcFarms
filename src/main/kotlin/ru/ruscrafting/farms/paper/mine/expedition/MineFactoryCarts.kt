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
import kotlin.math.min

/** Presentation only: the expedition's existing cargo lease owns pickup, credit and retirement.
 * Farm processing carries light parcels; these heavy loads instead trail a factory worker on wheels.
 * The shared tether owns the native leash anchor; every visible part uses the shared packet renderer.
 */
internal class MineFactoryCarts(private val plugin: Plugin, private val visuals: MineFactoryCartVisuals = PacketMineFactoryCartVisuals(plugin)) {
    class Cart(val scope: String, val owner: UUID, var at: Location,
        val body: MineFactoryCartVisuals.Body,
        var yaw: Float, var phase: Float = 0f, var nextSound: Long = 0L, var unloaded: Boolean = false)
    private val tethers = WorksiteCrankTethers(NamespacedKey(plugin, "mine_factory_cart_tether"))

    fun spawn(scope: String, player: Player, material: Material): Cart {
        val at = WorksiteCarryable.carriedLocation(player, 1.9, 0.0, position = WorksiteCarryPosition.BACK)
        at.yaw = 0f; at.pitch = 0f
        val parts = MineDisplayBlueprints.model(if(material == Material.COAL_BLOCK) "cargo_cart_coal" else "cargo_cart_iron")
        var body: MineFactoryCartVisuals.Body? = null
        try {
            tethers.attach(scope, player, at.clone().add(0.0, .85, 0.0)).getOrThrow()
            body = visuals.spawn(at,parts)
            return Cart(scope, player.uniqueId, at, body, player.location.yaw).also { render(it) }
        } catch (failure: Throwable) {
            body?.remove()
            tethers.release(scope, player.uniqueId)
            throw failure
        }
    }

    /** Follow translation, not the player's camera: looking around never swings the cart in a circle. */
    fun move(cart: Cart, player: Player, now: Long): Boolean {
        if(cart.unloaded) return true
        val to = player.location
        if(to.world !== cart.at.world || to.distanceSquared(cart.at) > 100 || kotlin.math.abs(to.y-cart.at.y)>3) return false
        val dx=to.x-cart.at.x; val dz=to.z-cart.at.z
        val distance=kotlin.math.sqrt(dx*dx+dz*dz)
        if(distance>1.9) {
            val step=min(distance-1.9,.7)
            cart.at.add(dx/distance*step, (to.y-cart.at.y).coerceIn(-.15,.15), dz/distance*step)
            cart.yaw=Math.toDegrees(atan2(-dx,dz)).toFloat()
            cart.phase+=(step/.32).toFloat()
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
}
