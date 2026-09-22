package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Location
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.mine.expedition.ExpeditionPoint
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionGenerator
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlacement
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionStage
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionState
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionStep
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryExperiment
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryExperimentPlan
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryLine
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene
import kotlin.math.abs

/**
 * MockBukkit coverage for the optional factory interactions.  The packet
 * renderer is replaced with a recorder so these tests exercise ownership,
 * movement and durable callbacks without requiring a client connection.
 */
class MineFactoryExperimentsControllerTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var plugin: Plugin
    lateinit var scene: MineExpeditionScene
    lateinit var markers: MineExpeditionMarkers
    lateinit var visuals: RecordingExperimentVisuals
    lateinit var controller: MineFactoryExperimentsController

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("factory_experiments")
        plugin = paper.createSimplePlugin("FactoryExperimentsTest")
        scene = testScene(world)
        markers = MineExpeditionMarkers(plugin)
        visuals = RecordingExperimentVisuals()
        controller = MineFactoryExperimentsController(plugin, markers, null, ::fixturePosition, visuals)
    }

    afterEach {
        try {
            controller.cleanup()
            markers.cleanup()
        } finally {
            paper.close()
        }
    }

    test("rock jam requires three separated pries and resolves only on the third") {
        val state = state(MineExpeditionStage.FACTORY_COAL, setOf(0), MineFactoryExperiment.ROCK_JAM)
        val player = paper.server.addPlayer("RockWorker")
        val id = MineFactoryExperimentLayout.rockJam.id
        val steps = mutableListOf<MineExpeditionStep>()
        val complete: (MineExpeditionStep) -> Boolean = { steps += it; it.accepted }

        controller.targets(scene, state, 0L).map { it.id } shouldContain id
        controller.interact(scope(), scene, state, player, id, 0L, complete) shouldBe true
        controller.pryProgress(scope()) shouldBe (1.0 / 3.0)
        controller.interact(scope(), scene, state, player, id, 200L, complete) shouldBe true
        controller.pryProgress(scope()) shouldBe (2.0 / 3.0)
        steps.size shouldBe 0

        controller.interact(scope(), scene, state, player, id, 400L, complete) shouldBe true
        steps.size shouldBe 1
        steps.single().state.factoryExperiments!!.resolved shouldBe setOf(MineFactoryExperiment.ROCK_JAM)
        controller.pryProgress(scope()) shouldBe 0.0
    }

    test("taking the hose starts a visible directed spray without sneak or another click") {
        val state = state(MineExpeditionStage.FACTORY_HEAT, emptySet(), MineFactoryExperiment.COOLING)
        val player = paper.server.addPlayer("CoolingWorker")
        val steps = mutableListOf<MineExpeditionStep>()
        val complete: (org.bukkit.entity.Player, MineExpeditionStep) -> Boolean = { _, step -> steps += step; step.accepted }
        val bearing = requireNotNull(fixturePosition(scene, "decor_roller_table", Vector(0.0, .90 + .43 * .68, 1.55 + .08 * .68)))
        val feet = Location(world, bearing.x - 3.0, 64.0, bearing.z)
        controller.targets(scene, state, 0L)
        controller.interact(scope(), scene, state, player, MineFactoryExperimentLayout.hoseNozzle.id, 0L) { it.accepted } shouldBe true

        player.isSneaking = false
        lookAt(player, feet, bearing)
        controller.tick(scope(), scene, state, listOf(player), 250L, complete)
        (controller.coolingProgress(scope()) > 0.0) shouldBe true

        // Aiming away pauses the continuous spray requirement without
        // teleporting the hose progress backwards.
        val beforeMiss = controller.coolingProgress(scope())
        lookAt(player, feet, feet.clone().add(0.0, 0.0, -3.0))
        controller.tick(scope(), scene, state, listOf(player), 500L, complete)
        controller.coolingProgress(scope()) shouldBe beforeMiss

        lookAt(player, feet, bearing)
        (750L..4_500L step 250L)
            .forEach { now -> controller.tick(scope(), scene, state, listOf(player), now, complete) }
        steps.size shouldBe 1
        steps.single().state.factoryExperiments!!.resolved shouldBe setOf(MineFactoryExperiment.COOLING)
        visuals.rendered.map { it.model } shouldContain "hose_nozzle_held"
        visuals.rendered.map { it.model } shouldContain "hose_link"
    }

    test("release, ineligible-player ticks and clear remove transient ownership and visuals") {
        val state = state(MineExpeditionStage.FACTORY_HEAT, emptySet(), MineFactoryExperiment.COOLING)
        val player = paper.server.addPlayer("CleanupWorker")
        player.teleport(requireNotNull(fixturePosition(scene, "decor_roller_table", Vector(-2.0, 0.0, 4.0))))
        controller.targets(scene, state, 0L)
        controller.interact(scope(), scene, state, player, MineFactoryExperimentLayout.hoseNozzle.id, 0L) { it.accepted } shouldBe true
        player.isSneaking = false
        controller.tick(scope(), scene, state, listOf(player), 250L) { _, _ -> true }
        visuals.rendered.map { it.model } shouldContain "hose_nozzle_held"
        visuals.rendered.map { it.model } shouldContain "hose_link"

        controller.tick(scope(), scene, state, emptyList(), 500L) { _, _ -> error("owner left") }
        controller.coolingProgress(scope()) shouldBe 0.0
        controller.release(player)
        visuals.cleared shouldContain scope()
        controller.clear(scope())
        visuals.cleared shouldContain scope()
        controller.cleanup()
        visuals.cleaned shouldBe true
    }
})

private data class RenderedExperimentVisual(
    val scope: String,
    val id: String,
    val at: Location,
    val model: String,
    val scale: Float,
    val yaw: Float,
    val pitch: Float,
    val glowing: Boolean,
)

private class RecordingExperimentVisuals : MineFactoryExperimentVisuals {
    val rendered = mutableListOf<RenderedExperimentVisual>()
    val removed = mutableListOf<Pair<String, String>>()
    val cleared = mutableListOf<String>()
    var cleaned = false

    override fun render(scope: String, id: String, at: Location, model: String, scale: Float, yaw: Float, pitch: Float, glowing: Boolean) {
        rendered += RenderedExperimentVisual(scope, id, at.clone(), model, scale, yaw, pitch, glowing)
    }

    override fun remove(scope: String, id: String) {
        removed += scope to id
    }

    override fun clear(scope: String) {
        cleared += scope
    }

    override fun cleanup() {
        cleaned = true
    }
}

private fun testScene(world: WorldMock): MineExpeditionScene {
    val plan = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73L)
    val placement = MineExpeditionPlacement(world.name, 0, 60, 0, 73L)
    val surface = Location(world, .5, 65.0, 24.5)
    return MineExpeditionScene(
        plan,
        placement,
        world,
        "factory-experiment-test",
        1L,
        1L,
        1L,
        surface,
        WorksitePreparedScene(world, "factory-experiment-test", 1L, 1, surface, surface, surface, emptyList()),
    )
}

private fun fixturePosition(scene: MineExpeditionScene, anchor: String, offset: Vector): Location? {
    val point = scene.plan.stations[anchor]
        ?: MineFactoryLine.machines[anchor]
        ?: MineFactoryLine.stations[anchor]
        ?: ExpeditionPoint(0, 5, 0)
    return scene.at(point).add(offset.x, offset.y, offset.z)
}

private fun state(
    stage: MineExpeditionStage,
    completed: Set<Int>,
    experiment: MineFactoryExperiment,
    product: Int = 0,
): MineExpeditionState = MineExpeditionState(
    placement = MineExpeditionPlacement("factory_experiments", 0, 60, 0, 73L),
    stage = stage,
    completed = completed,
    factoryExperiments = MineFactoryExperimentPlan(setOf(experiment), product = product),
)

private fun scope(): String = "factory-experiment-test:1:1"

private fun lookAt(player: org.bukkit.entity.Player, feet: Location, target: Location) {
    val view = feet.clone()
    view.direction = target.toVector().subtract(view.toVector().add(Vector(0.0, player.eyeHeight, 0.0))).normalize()
    player.teleport(view)
}
