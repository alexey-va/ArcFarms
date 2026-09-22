package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Location
import org.bukkit.Material
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
import kotlin.math.abs
import kotlin.math.ceil

class MineFactoryCraneControlsTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var plugin: Plugin
    lateinit var scene: MineExpeditionScene
    lateinit var markers: MineExpeditionMarkers
    lateinit var controls: MineFactoryCraneControls

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("factory_crane_controls")
        plugin = paper.createSimplePlugin("FactoryCraneControlsTest")
        scene = testScene(world)
        markers = MineExpeditionMarkers(plugin)
        controls = MineFactoryCraneControls(plugin, markers, MineFactoryExperimentTargets(null, ::fixturePosition))
    }

    afterEach {
        controls.cleanup()
        markers.cleanup()
        paper.close()
    }

    test("publishes one non-actionable panel and six separated physical buttons") {
        val state = craneState(scene)
        val targets = controls.targets(scene, state, 0L)

        targets.map { it.id } shouldContainAll listOf(
            "crane_control", "crane_left", "crane_right", "crane_forward", "crane_back", "crane_lift", "crane_lower",
        )
        targets.single { it.id == "crane_control" }.interactive shouldBe false
        targets.single { it.id == "crane_load" }.interactive shouldBe false
        targets.filter { it.id.startsWith("crane_") && it.id !in setOf("crane_control", "crane_load") }.forEach { target ->
            target.interactive shouldBe true
            target.model?.startsWith("factory_crane_button_") shouldBe true
        }
        MineFactoryCraneLayout.buttons.map { it.id }.distinct().size shouldBe 6
        MineFactoryCraneLayout.staticFixtures().map { it.id }.distinct().size shouldBe 7
        val buttonBoxes = targets.filter { it.model?.startsWith("factory_crane_button_") == true }.map {
            it to requireNotNull(MineExpeditionMarkerGeometry.hitbox(MineDisplayBlueprints.model(it.model!!), it.yaw, it.modelScale))
        }
        for (i in buttonBoxes.indices) for (j in i + 1 until buttonBoxes.size) {
            val (left, a) = buttonBoxes[i]; val (right, b) = buttonBoxes[j]
            val dx = abs(left.location.x + a.center.x - right.location.x - b.center.x)
            val dy = abs(left.location.y + a.center.y - right.location.y - b.center.y)
            (dx >= (a.width + b.width) / 2 || dy >= (a.height + b.height) / 2) shouldBe true
        }
    }

    test("lift is required before bounded button movement and a wrong lowering stays repairable") {
        val state = craneState(scene)
        val player = paper.server.addPlayer("CraneOperator")
        val scope = scope(scene)
        controls.targets(scene, state, 0L)
        val source = requireNotNull(controls.position(scope))

        controls.interact(scope, scene, state, player, "crane_right", 0L) { it.accepted } shouldBe true
        controls.position(scope) shouldBe source

        controls.interact(scope, scene, state, player, "crane_lift", 200L) { it.accepted } shouldBe true
        (450L..1_200L step 250L).forEach { now ->
            controls.tick(scope, scene, state, listOf(player), now) { _, step -> step.accepted }
        }
        val lifted = requireNotNull(controls.position(scope))
        (lifted.y - source.y) shouldBe 3.0

        controls.interact(scope, scene, state, player, "crane_right", 1_400L) { it.accepted } shouldBe true
        controls.tick(scope, scene, state, listOf(player), 1_650L) { _, step -> step.accepted }
        controls.tick(scope, scene, state, listOf(player), 1_900L) { _, step -> step.accepted }
        val moved = requireNotNull(controls.position(scope))
        (moved.x > source.x) shouldBe true
        (moved.y - source.y) shouldBe 3.0

        controls.interact(scope, scene, state, player, "crane_lower", 2_100L) { it.accepted } shouldBe true
        (2_350L..3_850L step 250L).forEach { now ->
            controls.tick(scope, scene, state, listOf(player), now) { _, step -> step.accepted }
        }
        controls.hint(scope, player)?.toString() shouldNotBe null
        (controls.position(scope)!!.y < lifted.y) shouldBe true

        // The failed landing is left on the floor. Lift resets the miss and
        // gives the operator a safe, bounded correction path.
        controls.interact(scope, scene, state, player, "crane_lift", 4_100L) { it.accepted } shouldBe true
        (4_350L..5_100L step 250L).forEach { now ->
            controls.tick(scope, scene, state, listOf(player), now) { _, step -> step.accepted }
        }
        controls.hint(scope, player)?.toString() shouldNotBe null
    }

    test("only a successful callback releases a landed load, while release resets the operator to source") {
        val state = craneState(scene)
        val player = paper.server.addPlayer("CallbackOperator")
        val scope = scope(scene)
        controls.targets(scene, state, 0L)
        val source = requireNotNull(controls.position(scope))
        val destination = requireNotNull(fixturePosition(scene, "crane_load", Vector(0.0, 2.0, 0.0)))

        controls.interact(scope, scene, state, player, "crane_lift", 0L) { it.accepted }
        (250L..1_000L step 250L).forEach { now ->
            controls.tick(scope, scene, state, listOf(player), now) { _, step -> step.accepted }
        }
        val direction = if (destination.x >= source.x) "crane_right" else "crane_left"
        val moves = ceil(abs(destination.x - source.x) / .8).toInt()
        var now = 1_200L
        repeat(moves) {
            controls.interact(scope, scene, state, player, direction, now) { it.accepted }
            controls.tick(scope, scene, state, listOf(player), now + 250L) { _, step -> step.accepted }
            controls.tick(scope, scene, state, listOf(player), now + 500L) { _, step -> step.accepted }
            now += 700L
        }
        controls.interact(scope, scene, state, player, "crane_lower", now) { it.accepted }
        val rejected = mutableListOf<MineExpeditionStep>()
        (now + 250L..now + 1_500L step 250L).forEach { tickNow ->
            controls.tick(scope, scene, state, listOf(player), tickNow) { _, step -> rejected += step; false }
        }
        rejected.size shouldBe 1
        controls.position(scope) shouldNotBe null

        controls.release(player)
        controls.position(scope) shouldBe source
    }

    test("a reversed edited route lowers a missed load to the floor and lets the operator retry") {
        controls.cleanup()
        controls = MineFactoryCraneControls(plugin, markers, MineFactoryExperimentTargets(null) { site, anchor, offset ->
            fixturePosition(site, anchor, offset)?.apply { if (anchor == "crane_load") x -= 14.0 }
        })
        val state = craneState(scene)
        val player = paper.server.addPlayer("ReverseOperator")
        val scope = scope(scene)
        controls.targets(scene, state, 0L)
        val source = requireNotNull(controls.position(scope))
        var now = 0L
        fun waitMotion() { repeat(8) {
            now += 250L
            controls.tick(scope, scene, state, listOf(player), now) { _, _ -> error("wrong landing must not finish") }
        } }
        controls.interact(scope, scene, state, player, "crane_lift", now) { it.accepted }
        waitMotion()
        repeat(4) {
            controls.interact(scope, scene, state, player, "crane_left", now + 150L) { it.accepted }
            waitMotion()
        }
        controls.interact(scope, scene, state, player, "crane_lower", now + 150L) { it.accepted }
        waitMotion()
        controls.position(scope)!!.y shouldBe source.y - 1.7
        controls.targets(scene, state, now).single { it.id == "crane_lift" }.glowing shouldBe true
        controls.interact(scope, scene, state, player, "crane_lift", now + 150L) { it.accepted }
        waitMotion()
        controls.targets(scene, state, now).single { it.id == "crane_left" }.glowing shouldBe true
    }

    test("crane model set keeps buttons readable and separated from the console") {
        MineFactoryCraneModels.kinds shouldContainAll MineFactoryCraneLayout.buttons.map { it.model }
        MineFactoryCraneModels.kinds shouldContainAll listOf("factory_crane_panel")
        MineFactoryCraneModels.kinds.forEach { kind ->
            MineFactoryCraneModels.model(kind).isNotEmpty() shouldBe true
        }
        MineFactoryCraneLayout.buttons.map { it.offset.x }.toSet() shouldBe setOf(-1.65, -.65, .35, 1.6)
        MineFactoryCraneLayout.buttons.map { it.offset.y }.toSet() shouldBe setOf(1.10, 1.575, 2.05)
        MineFactoryCraneLayout.buttons.all { it.offset.z == .42 } shouldBe true
        val panel = MineFactoryCraneModels.model("factory_crane_panel")
        panel.none { it.moving } shouldBe true
        (panel.maxOf { it.center.y + it.size.y / 2f } >= 2.5f) shouldBe true
        val buttonCap = MineFactoryCraneModels.model("factory_crane_button_left").single { it.material == Material.BLUE_CONCRETE }
        buttonCap.center.y shouldBe 0f
        buttonCap.size.x shouldBe .72f
        buttonCap.size.y shouldBe .72f
        buttonCap.size.z shouldBe .12f
        MineFactoryCraneModels.model("factory_crane_button_left").any { it.material == Material.WHITE_CONCRETE } shouldBe true
    }
})

private fun testScene(world: WorldMock): MineExpeditionScene {
    val plan = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73L)
    val placement = MineExpeditionPlacement(world.name, 0, 60, 0, 73L)
    val surface = Location(world, .5, 65.0, 24.5)
    return MineExpeditionScene(
        plan,
        placement,
        world,
        "factory-crane-test",
        1L,
        1L,
        1L,
        surface,
        ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene(
            world, "factory-crane-test", 1L, 1, surface, surface, surface, emptyList(),
        ),
    )
}

private fun craneState(scene: MineExpeditionScene): MineExpeditionState = MineExpeditionState(
    placement = scene.placement,
    stage = MineExpeditionStage.FACTORY_CRANE,
    factoryExperiments = ru.ruscrafting.farms.domain.mine.expedition.MineFactoryExperimentPlan(
        selected = setOf(MineFactoryExperiment.MANUAL_CRANE),
    ),
)

private fun fixturePosition(scene: MineExpeditionScene, anchor: String, offset: Vector): Location? {
    val point = scene.plan.stations[anchor]
        ?: ru.ruscrafting.farms.domain.mine.expedition.MineFactoryLine.machines[anchor]
        ?: ru.ruscrafting.farms.domain.mine.expedition.MineFactoryLine.stations[anchor]
        ?: ExpeditionPoint(0, 5, 0)
    return scene.at(point).add(offset.x, offset.y, offset.z)
}

private fun scope(scene: MineExpeditionScene): String = "${scene.zoneId}:${scene.sequence}:${scene.objectiveNonce}"
