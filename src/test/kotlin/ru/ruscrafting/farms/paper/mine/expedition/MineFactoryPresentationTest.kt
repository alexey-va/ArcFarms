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
    test("diesel supply starts with commissioning, survives a crusher jam and switches off on completion") {
        val plugin = mockk<Plugin>(relaxed = true)
        val markers = mockk<MineExpeditionMarkers>(relaxed = true)
        every { markers.at(any(), any(), any(), any(), any()) } returns null
        val placement = MineExpeditionPlacement("world", 0, 60, 0, 73)
        val scene = mockk<MineExpeditionScene> {
            every { kind } returns MineExpeditionKind.DEAD_FACTORY
            every { this@mockk.placement } returns placement
            every { journalSequence } returns 8L
            every { plan } returns MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73L, 3)
        }
        val presentation = MineFactoryPresentation(plugin, markers)
        val preparing = MineExpeditionState(placement, MineExpeditionStage.FACTORY_WATER, completed = setOf(0, 1))
        presentation.tick(scene, preparing, "factory", 1000, emptyMap())
        verify(exactly = 0) { markers.rotate(any(), "decor_diesel_generator", any()) }
        verify { markers.signal("furnish:8", "decor_diesel_generator", org.bukkit.Material.RED_CONCRETE) }
        clearMocks(markers, answers = false)
        presentation.tick(scene, preparing, "factory", 1100, mapOf("control_crusher_left" to .5))
        verify { markers.rotate("furnish:8", "decor_diesel_generator", any()) }
        verify { markers.signal("furnish:8", "decor_diesel_generator", org.bukkit.Material.LIME_CONCRETE) }
        clearMocks(markers, answers = false)
        presentation.tick(scene, preparing, "factory", 4500, mapOf("control_crusher_left" to .5))
        verify { markers.rotate("furnish:8", "decor_diesel_generator", 3 * Math.PI) }
        clearMocks(markers, answers = false)
        val jammed = preparing.copy(stage = MineExpeditionStage.FACTORY_COAL, completed = setOf(0),
            factoryExperiments = MineFactoryExperimentPlan(setOf(MineFactoryExperiment.ROCK_JAM)))
        presentation.tick(scene, jammed, "factory", 1200, emptyMap())
        verify { markers.rotate("furnish:8", "decor_diesel_generator", any()) }
        verify(exactly = 0) { markers.rotate(any(), "decor_crusher_left", any()) }
        clearMocks(markers, answers = false)
        presentation.tick(scene, jammed.copy(stage = MineExpeditionStage.COMPLETE), "factory", 1500, emptyMap())
        verify(exactly = 0) { markers.rotate(any(), "decor_diesel_generator", any()) }
        verify { markers.signal("furnish:8", "decor_diesel_generator", org.bukkit.Material.RED_CONCRETE) }
    }

    test("connected line shows crusher feed during processing and cargo only during transfer") {
        val plugin = mockk<Plugin>(relaxed = true)
        val markers = mockk<MineExpeditionMarkers>(relaxed = true)
        every { markers.at(any(), any(), any(), any(), any()) } returns null
        val placement = MineExpeditionPlacement("world", 0, 60, 0, 73)
        val scene = mockk<MineExpeditionScene> {
            every { kind } returns MineExpeditionKind.DEAD_FACTORY
            every { this@mockk.placement } returns placement
            every { journalSequence } returns 8L
            every { plan } returns MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73L, 3)
        }
        val presentation = MineFactoryPresentation(plugin, markers)
        val loaded = MineExpeditionState(placement, MineExpeditionStage.FACTORY_COAL, completed = setOf(0))
        presentation.tick(scene, loaded, "factory", 1000, emptyMap())
        verify { markers.motionVisible("furnish:8", "decor_crusher_left", "feed", false) }
        clearMocks(markers, answers = false)
        presentation.tick(scene, loaded, "factory", 1100, emptyMap(), processingCharge = true)
        verify { markers.motionVisible("furnish:8", "decor_crusher_left", "feed", true) }
        verify { markers.motionVisible("furnish:8", "decor_conveyor_raw", "cargo", false) }
        verify(exactly = 0) { markers.motionLoopBoundary(any(), any(), any(), any(), any(), any(), any()) }
        clearMocks(markers, answers = false)
        val transfer = loaded.copy(completed = setOf(0, 1))
        presentation.tick(scene, transfer, "factory", 1200, mapOf("charge_transfer" to .25))
        verify { markers.repositionMotion("furnish:8", "decor_conveyor_raw", "cargo") }
        verify { markers.repositionMotion("factory", "decor_conveyor_raw", "cargo") }
        clearMocks(markers, answers = false)
        presentation.tick(scene, transfer, "factory", 1400, mapOf("charge_transfer" to .5))
        verify { markers.motionLoopBoundary("furnish:8", "decor_conveyor_raw", "cargo", any(), any(), 1400, any()) }
        verify { markers.motionVisible("furnish:8", "decor_conveyor_raw", "cargo", true) }
        clearMocks(markers, answers = false)
        presentation.tick(scene, transfer, "factory", 8000,
            mapOf("charge_transfer" to Math.PI))
        verify { markers.motionVisible("furnish:8", "decor_crusher_left", "feed", false) }
        verify { markers.motionVisible("factory", "crushed_output", "processed", true) }
        clearMocks(markers, answers = false)
        presentation.tick(scene, loaded.copy(stage = MineExpeditionStage.FACTORY_HEAT, completed = emptySet()), "factory", 9000, emptyMap())
        verify { markers.motionVisible("factory", "crushed_output", "processed", false) }
        clearMocks(markers, answers = false)
        presentation.tick(scene, loaded.copy(stage = MineExpeditionStage.FACTORY_CRANE), "factory", 10_000,
            mapOf("crane_control" to Math.PI))
        verify(exactly = 0) { markers.rotate("furnish:8", "decor_roller_table", any()) }
        clearMocks(markers, answers = false)
        presentation.tick(scene, loaded.copy(stage = MineExpeditionStage.FACTORY_INSTALL), "factory", 11_000,
            mapOf("assembly_socket" to Math.PI))
        verify(exactly = 0) { markers.rotate("furnish:8", "decor_roller_table", any()) }
        verify { markers.rotate("furnish:8", "assembly_socket", Math.PI) }
        clearMocks(markers, answers = false)
        presentation.tick(scene, loaded.copy(stage = MineExpeditionStage.FACTORY_INSTALL), "factory", 11_100,
            mapOf("roller_transfer" to Math.PI, "assembly_socket" to Math.PI))
        verify(exactly = 1) { markers.rotate("furnish:8", "decor_roller_table", -Math.PI) }
    }

    test("jam and hot bearing pause the powered crusher until the side job resolves") {
        val plugin = mockk<Plugin>(relaxed = true)
        val markers = mockk<MineExpeditionMarkers>(relaxed = true)
        every { markers.at(any(), any(), any(), any(), any()) } returns null
        val placement = MineExpeditionPlacement("world", 0, 60, 0, 73)
        val scene = mockk<MineExpeditionScene> {
            every { kind } returns MineExpeditionKind.DEAD_FACTORY
            every { this@mockk.placement } returns placement
            every { journalSequence } returns 8L
            every { plan } returns MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73L, 3)
        }
        val presentation = MineFactoryPresentation(plugin, markers)
        for ((experiment, stage) in listOf(MineFactoryExperiment.ROCK_JAM to MineExpeditionStage.FACTORY_COAL,
            MineFactoryExperiment.COOLING to MineExpeditionStage.FACTORY_HEAT)) {
            val stuck = MineExpeditionState(placement, stage, completed = setOf(0),
                factoryExperiments = MineFactoryExperimentPlan(setOf(experiment)))
            presentation.tick(scene, stuck, "factory", 1000, emptyMap())
            verify(exactly = 0) { markers.rotate("furnish:8", "decor_crusher_left", any()) }
            clearMocks(markers, answers = false)
            presentation.tick(scene, stuck.copy(factoryExperiments = stuck.factoryExperiments!!.copy(resolved = setOf(experiment))),
                "factory", 1500, emptyMap())
            verify { markers.rotate("furnish:8", "decor_crusher_left", any()) }
            clearMocks(markers, answers = false)
        }
    }

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
            every { plan } returns MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY,73L,2)
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
