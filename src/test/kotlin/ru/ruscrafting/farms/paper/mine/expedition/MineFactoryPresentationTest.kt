package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.mine.expedition.*

class MineFactoryPresentationTest : FunSpec({
    test("running crusher rotates and emits bounded feedback after commissioning and during casting") {
        val plugin = mockk<Plugin>(relaxed = true)
        every { plugin.config.getBoolean(any(), true) } returns true
        val world = mockk<World>(relaxed = true)
        val at = Location(world, 0.0, 60.0, 0.0)
        val player = mockk<Player>(relaxed = true)
        every { player.location } returns at
        every { world.players } returns listOf(player)
        val markers = mockk<MineExpeditionMarkers>(relaxed = true)
        every { markers.at(any(), any(), any(), any(), any()) } answers {
            if (secondArg<String>() == "decor_crusher_left") at else null
        }
        val placement = MineExpeditionPlacement("world", 0, 60, 0, 73)
        val scene = mockk<MineExpeditionScene> {
            every { kind } returns MineExpeditionKind.DEAD_FACTORY
            every { this@mockk.placement } returns placement
            every { journalSequence } returns 1L
        }
        val presentation = MineFactoryPresentation(plugin, markers)
        val water = MineExpeditionState(placement, MineExpeditionStage.FACTORY_WATER, completed = setOf(2), factoryProgram = 1)
        presentation.tick(scene, water, "factory", 3_100, emptyMap())
        presentation.tick(scene, water, "factory", 3_200, emptyMap())
        verify(exactly = 2) { markers.rotate("furnish:1", "decor_crusher_left", any()) }
        verify(exactly = 0) { markers.rotate("furnish:1", "decor_crusher_right", any()) }
        verify(exactly = 1) { player.playSound(at, Sound.BLOCK_GRINDSTONE_USE, any<Float>(), any<Float>()) }
        verify(exactly = 1) { world.spawnParticle(Particle.CLOUD, at, 2, any<Double>(), any<Double>(), any<Double>(), any<Double>()) }

        clearMocks(markers, player, world, answers = false)
        presentation.tick(scene, water.copy(stage = MineExpeditionStage.FACTORY_POUR, completed = emptySet()), "factory", 5_000, emptyMap())
        verify(exactly = 1) { markers.rotate("furnish:1", "decor_crusher_left", any()) }
        verify(exactly = 1) { player.playSound(at, Sound.BLOCK_GRINDSTONE_USE, any<Float>(), any<Float>()) }
        verify(exactly = 1) { world.spawnParticle(Particle.CLOUD, at, 2, any<Double>(), any<Double>(), any<Double>(), any<Double>()) }

        clearMocks(markers, player, world, answers = false)
        presentation.tick(scene, water.copy(stage = MineExpeditionStage.COMPLETE), "factory", 7_000, emptyMap())
        verify { markers wasNot Called }
        verify { player wasNot Called }
        verify { world wasNot Called }
        presentation.clear(scene)
        presentation.cleanup()
    }
})
