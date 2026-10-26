package ru.ruscrafting.farms.config

import ru.arc.config.Config
import ru.ruscrafting.farms.domain.lumber.LumberPose

data class LumbermillSettings(
    val world: String = "rc_atelier_cedar_sawmill",
    val forest: CuboidBounds = CuboidBounds(94, 64, 158, 147, 92, 215),
    val worksite: CuboidBounds = CuboidBounds(90, 64, 150, 260, 100, 290),
    val cart: LumberPose = LumberPose(world, 144.5, 72.0, 206.5),
    val warehouse: LumberPose = LumberPose(world, 194.1, 73.0, 185.5, 180f),
    val chopHits: Int = 5,
    val fallTicks: Int = ru.ruscrafting.farms.domain.lumber.LumberTiming.FALL_TICKS,
    val interactionRadius: Double = 5.5,
    val deliveryRadius: Double = 2.5,
    val actionCooldownMillis: Long = 300,
) {
    init {
        require(world.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid lumber world" }
        require(forest.volume <= 2_000_000L) { "Lumber forest exceeds bounded index capacity" }
        require(chopHits in 1..100 && fallTicks == ru.ruscrafting.farms.domain.lumber.LumberTiming.FALL_TICKS) { "Lumber fall must use the authored choreography" }
        require(interactionRadius in 1.0..8.0 && deliveryRadius in 0.5..6.0) { "Invalid lumber interaction radius" }
        require(actionCooldownMillis in 100..5_000) { "Invalid lumber action cooldown" }
        require(listOf(cart, warehouse).all { it.world == world && listOf(it.x, it.y, it.z).all(Double::isFinite) }) {
            "Lumber anchors must be finite and belong to its world"
        }
    }

    companion object {
        const val BETA_PERMISSION = "arcfarms.lumber.beta"

        fun parse(config: Config): LumbermillSettings {
            val defaults = LumbermillSettings()
            val world = config.string("lumbermill.world", defaults.world)
            fun bounds(path: String, fallback: CuboidBounds) = CuboidBounds(
                config.int("$path.min-x", fallback.minX), config.int("$path.min-y", fallback.minY),
                config.int("$path.min-z", fallback.minZ), config.int("$path.max-x", fallback.maxX),
                config.int("$path.max-y", fallback.maxY), config.int("$path.max-z", fallback.maxZ),
            )
            fun anchor(path: String, fallback: LumberPose) = LumberPose(world,
                config.double("$path.x", fallback.x), config.double("$path.y", fallback.y),
                config.double("$path.z", fallback.z), config.double("$path.yaw", fallback.yaw.toDouble()).toFloat())
            return LumbermillSettings(world,
                bounds("lumbermill.forest", defaults.forest), bounds("lumbermill.worksite", defaults.worksite),
                anchor("lumbermill.cart", defaults.cart), anchor("lumbermill.warehouse", defaults.warehouse),
                config.int("lumbermill.chop-hits", defaults.chopHits), config.int("lumbermill.fall-ticks", defaults.fallTicks),
                config.double("lumbermill.interaction-radius", defaults.interactionRadius),
                config.double("lumbermill.delivery-radius", defaults.deliveryRadius),
                config.int("lumbermill.action-cooldown-millis", defaults.actionCooldownMillis.toInt()).toLong(),
            )
        }
    }
}
