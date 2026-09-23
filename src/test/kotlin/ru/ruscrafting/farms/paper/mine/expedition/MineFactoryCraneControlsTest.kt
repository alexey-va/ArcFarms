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
        targets.filter { it.id != "crane_load" && it.model?.startsWith("factory_crane_") == true }.all { it.yaw == 90 } shouldBe true
        val buttonBoxes = targets.filter { it.model?.startsWith("factory_crane_button_") == true }.map {
            it to requireNotNull(MineExpeditionMarkerGeometry.hitbox(MineDisplayBlueprints.model(it.model!!), it.yaw, it.modelScale))
        }
        for (i in buttonBoxes.indices) for (j in i + 1 until buttonBoxes.size) {
            val (left, a) = buttonBoxes[i]; val (right, b) = buttonBoxes[j]
            val dx = abs(left.location.x + a.center.x - right.location.x - b.center.x)
            val dy = abs(left.location.y + a.center.y - right.location.y - b.center.y)
            val dz = abs(left.location.z + a.center.z - right.location.z - b.center.z)
            (dx >= (a.width + b.width) / 2 || dz >= (a.width + b.width) / 2 || dy >= (a.height + b.height) / 2) shouldBe true
        }
    }

    test("direction powers continuous travel and repeated press brakes with inertia") {
        val state = craneState(scene)
        val player = paper.server.addPlayer("Operator")
        val scope = scope(scene)
        controls.targets(scene, state, 0L)
        val source = controls.position(scope)!!
        controls.interact(scope, scene, state, player, "crane_back", 0L) { it.accepted }
        controls.position(scope) shouldBe source
        controls.interact(scope, scene, state, player, "crane_lift", 200L) { it.accepted }
        (250L..1_250L step 50L).forEach { controls.tick(scope, scene, state, listOf(player), it) { _, _ -> false } }
        controls.interact(scope, scene, state, player, "crane_back", 1_400L) { it.accepted }
        (1_450L..2_450L step 50L).forEach { controls.tick(scope, scene, state, listOf(player), it) { _, _ -> false } }
        val moving = controls.position(scope)!!
        (moving.x > source.x + .8) shouldBe true
        controls.interact(scope, scene, state, player, "crane_back", 2_500L) { it.accepted }
        (2_550L..3_750L step 50L).forEach { controls.tick(scope, scene, state, listOf(player), it) { _, _ -> false } }
        val stopped = controls.position(scope)!!
        (stopped.x > moving.x + .5) shouldBe true
        controls.tick(scope, scene, state, listOf(player), 4_000L) { _, _ -> false }
        controls.position(scope) shouldBe stopped
        controls.targets(scene, state, 4_000L).all { !it.glowing } shouldBe true
    }

    test("another authorized non-op operator can brake and correct the shared crane") {
        val state = craneState(scene)
        val first = paper.server.addPlayer("First").apply { isOp = false }
        val second = paper.server.addPlayer("Second").apply { isOp = false }
        val scope = scope(scene)
        controls.targets(scene, state, 0L)
        controls.interact(scope, scene, state, first, "crane_lift", 0L) { it.accepted }
        (50L..1_000L step 50L).forEach { controls.tick(scope, scene, state, listOf(first, second), it) { _, _ -> false } }
        controls.interact(scope, scene, state, second, "crane_back", 1_200L) { it.accepted }
        val before = controls.position(scope)!!
        controls.tick(scope, scene, state, listOf(first, second), 1_450L) { _, _ -> false }
        (controls.position(scope)!!.x > before.x) shouldBe true
        controls.release(second)
        controls.position(scope)!!.x shouldBe before.x
    }

    test("precise manually braked landing completes once and a rejected commit remains retryable") {
        val state = craneState(scene)
        val player = paper.server.addPlayer("Landing")
        val scope = scope(scene)
        controls.targets(scene, state, 0L)
        val source = controls.position(scope)!!
        val destination = fixturePosition(scene, "crane_load", Vector(0.0, 2.0, 0.0))!!
        var now = 0L
        var calls = 0
        fun advance(millis: Long) { repeat((millis / 50L).toInt()) {
            now += 50L
            controls.tick(scope, scene, state, listOf(player), now) { _, _ -> calls++; false }
        } }
        controls.interact(scope, scene, state, player, "crane_lift", now) { it.accepted }
        advance(1_000L)
        controls.interact(scope, scene, state, player, "crane_back", now) { it.accepted }
        advance(3_500L)
        controls.interact(scope, scene, state, player, "crane_back", now) { it.accepted }
        advance(1_000L)
        (abs(controls.position(scope)!!.x - destination.x) < .1) shouldBe true
        controls.interact(scope, scene, state, player, "crane_lower", now) { it.accepted }
        advance(2_000L)
        calls shouldBe 1
        controls.position(scope) shouldNotBe null
        advance(1_000L)
        calls shouldBe 1
        controls.release(player)
        controls.position(scope) shouldBe source
    }

    test("moving load cannot lower and travel stops safely at the yard boundary") {
        val state = craneState(scene)
        val player = paper.server.addPlayer("Boundary")
        val scope = scope(scene)
        controls.targets(scene, state, 0L)
        controls.interact(scope, scene, state, player, "crane_lift", 0L) { it.accepted }
        (50L..1_000L step 50L).forEach { controls.tick(scope, scene, state, listOf(player), it) { _, _ -> false } }
        val high = controls.position(scope)!!.y
        controls.interact(scope, scene, state, player, "crane_back", 1_200L) { it.accepted }
        controls.interact(scope, scene, state, player, "crane_lower", 1_400L) { it.accepted }
        (1_450L..11_450L step 50L).forEach { controls.tick(scope, scene, state, listOf(player), it) { _, _ -> error("must not complete") } }
        controls.position(scope)!!.y shouldBe high
        val destination = fixturePosition(scene, "crane_load", Vector(0.0, 2.0, 0.0))!!
        controls.position(scope)!!.x shouldBe destination.x + 2.0
        controls.interact(scope, scene, state, player, "crane_lower", 12_000L) { it.accepted }
        (12_050L..14_000L step 50L).forEach { controls.tick(scope, scene, state, listOf(player), it) { _, _ -> error("miss must remain retryable") } }
        (controls.position(scope)!!.y < destination.y) shouldBe true
        controls.interact(scope, scene, state, player, "crane_lift", 14_200L) { it.accepted }
        (14_250L..16_250L step 50L).forEach { controls.tick(scope, scene, state, listOf(player), it) { _, _ -> false } }
        controls.position(scope)!!.y shouldBe high
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
