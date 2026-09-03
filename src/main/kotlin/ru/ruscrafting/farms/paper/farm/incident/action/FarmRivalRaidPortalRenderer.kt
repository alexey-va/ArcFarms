package ru.ruscrafting.farms.paper.farm.incident.action

import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.FarmRivalRaidSettings
import kotlin.math.cos
import kotlin.math.sin

/** Draws a dense, vertical raid entry field that remains readable from every approach angle. */
internal object FarmRivalRaidPortalRenderer {
    fun render(portal: Interaction, settings: FarmRivalRaidSettings, viewers: Collection<Player>) {
        if (viewers.isEmpty()) return
        val ground = portal.location.clone().add(0.0, 0.12, 0.0)
        repeat(RING_PARTICLES) { index ->
            val angle = Math.PI * 2.0 * index / RING_PARTICLES
            val point = ground.clone().add(cos(angle) * RING_RADIUS, 0.0, sin(angle) * RING_RADIUS)
            viewers.forEach { viewer ->
                viewer.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, PURPLE_EDGE)
            }
        }
        val center = portal.location.clone().add(0.0, settings.portalHeight * 0.5, 0.0)
        repeat(FRAME_PARTICLES) { index ->
            val angle = Math.PI * 2.0 * index / FRAME_PARTICLES
            val horizontal = cos(angle) * settings.portalWidth * 0.46
            val vertical = sin(angle) * settings.portalHeight * 0.48
            viewers.forEach { viewer ->
                viewer.spawnParticle(
                    Particle.DUST,
                    center.clone().add(horizontal, vertical, 0.0),
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    PURPLE_FRAME,
                )
                viewer.spawnParticle(
                    Particle.DUST,
                    center.clone().add(0.0, vertical, horizontal),
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    BLUE_FRAME,
                )
            }
        }
        viewers.forEach { viewer ->
            viewer.spawnParticle(
                Particle.REVERSE_PORTAL,
                center,
                FIELD_PARTICLES,
                settings.portalWidth * 0.35,
                settings.portalHeight * 0.38,
                settings.portalWidth * 0.35,
                0.035,
            )
        }
    }

    private val PURPLE_EDGE = Particle.DustOptions(Color.fromRGB(199, 120, 255), 1.15f)
    private val PURPLE_FRAME = Particle.DustOptions(Color.fromRGB(199, 120, 255), 1.35f)
    private val BLUE_FRAME = Particle.DustOptions(Color.fromRGB(92, 198, 255), 1.1f)
    private const val RING_PARTICLES = 18
    private const val FRAME_PARTICLES = 28
    private const val FIELD_PARTICLES = 24
    private const val RING_RADIUS = 1.15
}
