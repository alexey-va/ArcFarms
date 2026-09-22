package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.mine.expedition.*
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene
import kotlin.math.PI

class MineDieselGeneratorStartTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var plan: MineExpeditionPlan
    lateinit var scene: MineExpeditionScene
    lateinit var actions: MineExpeditionActions
    lateinit var cartVisuals: FakeFactoryCartVisuals
    val scope = "factory:diesel-start"

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("diesel_generator_start")
        // Model a journal written before the flywheel station was persisted.
        plan = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73L).let {
            it.copy(stations = it.stations - "generator_flywheel")
        }
        val placement = MineExpeditionPlacement(world.name, 0, 60, 0, 73L)
        val surface = Location(world, 0.5, 65.0, 24.5)
        scene = MineExpeditionScene(
            plan, placement, world, "factory", 1, 1, 1, surface,
            WorksitePreparedScene(world, "factory", 1, 1, surface, surface, surface, emptyList()),
        )
        val plugin = paper.createSimplePlugin("DieselGeneratorStartTest")
        cartVisuals = FakeFactoryCartVisuals()
        actions = MineExpeditionActions(plugin, null, MineFactoryCarts(plugin, cartVisuals))
    }

    afterEach {
        try {
            actions.cleanup()
        } finally {
            paper.close()
        }
    }

    test("old plans link the flywheel to its fixture and shared clicks can be cleared and retried") {
        plan.stations.containsKey("generator_flywheel") shouldBe false
        var state = MineExpeditionState(
            scene.placement, MineExpeditionStage.FACTORY_WATER, completed = setOf(0, 1),
        )
        val target = MineFactoryProgram.targets(plan, state).single()
        target.id shouldBe "generator_flywheel"
        target.position shouldBe MineFactoryLine.stations.getValue("generator_flywheel")
        MineFactoryLine.effectiveStation(plan, target.id) shouldBe target.position
        MineExpeditionFurnishings.parent(plan.kind, target.id) shouldBe "decor_diesel_generator"
        MineExpeditionFurnishings.childOf(plan.kind, target.id, "decor_diesel_generator") shouldBe true
        val generatorFixture = MineExpeditionFurnishings.fixtures(plan).single { it.id == "decor_diesel_generator" }
        generatorFixture.at shouldBe MineFactoryLine.machines.getValue("decor_diesel_generator")

        val players = listOf(paper.server.addPlayer(), paper.server.addPlayer())
        players.forEach { it.teleport(scene.at(target.position)) }
        var accepted = 0
        val complete: (MineExpeditionStep) -> Boolean = { step ->
            if (step.accepted) accepted++
            state = step.state
            step.accepted
        }

        fun sharedClick(index: Int, now: Long) {
            actions.interact(scope, scene, state, players[index % 2], target, now, complete) {}
            actions.interact(scope, scene, state, players[1 - index % 2], target, now, complete) {}
        }

        fun animatedAngle(now: Long): Double? {
            var angle: Double? = null
            actions.tick(scope, scene, state, listOf(target), players, now,
                { _, _ -> error("flywheel animation cannot complete a checkpoint") }) { id, radians ->
                if (id == target.id) angle = radians
            }
            return angle
        }

        repeat(2) { index ->
            val now = 1_000L + index * 250L
            sharedClick(index, now)
            if (index == 1) animatedAngle(now) shouldBe PI / 2
        }
        accepted shouldBe 0
        actions.clear(scope)
        actions.hint(scope, players.first(), 2_000L) shouldBe null

        repeat(8) { index ->
            val now = 5_000L + index * 250L
            sharedClick(index, now)
            if (index < 7 && index == 1) animatedAngle(now) shouldBe PI / 2
            if (index < 7) accepted shouldBe 0
        }

        accepted shouldBe 1
        state.stage shouldBe MineExpeditionStage.FACTORY_COAL
        state.factoryGeneratorStartedAt shouldBe 6_750L
        actions.interact(scope, scene, state, players.first(), target, 12_750L, complete) {}
        accepted shouldBe 1
        state.factoryGeneratorStartedAt shouldBe 6_750L
    }

    test("disabled cutaway is omitted while the lightweight flywheel checkpoint remains") {
        val enabled = MineExpeditionFurnishings.targets(scene, null, emptySet(), dieselGeneratorEnabled = true)
        enabled.single { it.id == "decor_diesel_generator" }.model shouldBe MineDieselGeneratorModel.kind

        val disabled = MineExpeditionFurnishings.targets(scene, null, emptySet(), dieselGeneratorEnabled = false)
        disabled.none { it.id == "decor_diesel_generator" } shouldBe true

        var state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_WATER, completed = setOf(0, 1))
        val startTarget = MineFactoryProgram.targets(plan, state).single()
        startTarget.id shouldBe "generator_flywheel"
        val player = paper.server.addPlayer().also { it.teleport(scene.at(startTarget.position)) }
        var accepted = false
        repeat(8) { index ->
            actions.interact(scope, scene, state, player, startTarget, 10_000L + index * 250L, { step ->
                state = step.state
                accepted = step.accepted || accepted
                step.accepted
            }) {}
        }
        accepted shouldBe true
        state.stage shouldBe MineExpeditionStage.FACTORY_COAL
        state.factoryGeneratorStartedAt shouldBe 11_750L
    }

    test("connected production cargo cannot be picked up until the startup ramp ends") {
        val state = MineExpeditionState(
            scene.placement,
            MineExpeditionStage.FACTORY_COAL,
            factoryGeneratorStartedAt = 1_000L,
        )
        val pickup = MineExpeditionObjectives.targets(plan, state, null)
            .single { it.interaction == MineExpeditionInteraction.PICKUP }
        val player = paper.server.addPlayer().also { it.teleport(scene.at(pickup.position)) }
        val complete: (MineExpeditionStep) -> Boolean = { error("a production pickup cannot complete a checkpoint") }

        actions.interact(scope, scene, state, player, pickup, 6_999L, complete) {}
        actions.carrying(player, scope) shouldBe false
        cartVisuals.spawned shouldBe 0

        actions.interact(scope, scene, state, player, pickup, 7_000L, complete) {}
        actions.carrying(player, scope) shouldBe true
        cartVisuals.spawned shouldBe 1
    }
})

private class FakeFactoryCartVisuals : MineFactoryCartVisuals {
    class Body : MineFactoryCartVisuals.Body {
        override fun render(at: Location, yaw: Float, phase: Float) = Unit
        override fun remove() = Unit
    }

    var spawned = 0

    override fun spawn(at: Location, parts: List<MineDisplayBlueprints.Part>): MineFactoryCartVisuals.Body {
        spawned++
        return Body()
    }

    override fun close() = Unit
}
