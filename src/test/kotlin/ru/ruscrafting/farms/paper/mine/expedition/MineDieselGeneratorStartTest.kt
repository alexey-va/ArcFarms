package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.mine.expedition.*
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene

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

    test("old plans expose one physical start console and one press starts the persisted ramp") {
        plan.stations.containsKey("generator_flywheel") shouldBe false
        var state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_WATER, completed = setOf(0, 1))
        val target = MineFactoryProgram.targets(plan, state).single()
        target.interaction shouldBe MineExpeditionInteraction.OPERATE
        target.position shouldBe MineFactoryLine.stations.getValue("generator_flywheel")
        val fixture = MineExpeditionFurnishings.fixtures(plan).single { it.id == target.id }
        fixture.model shouldBe "machine_console"
        fixture.at shouldBe target.position
        val oldPlan = plan.copy(stations = plan.stations + (target.id to ExpeditionPoint(24, 5, 6)))
        MineFactoryProgram.targets(oldPlan, state).single().position shouldBe target.position
        val player = paper.server.addPlayer().also { it.teleport(scene.at(target.position)) }
        var accepted = 0
        val complete: (MineExpeditionStep) -> Boolean = { step ->
            if (step.accepted) accepted++
            state = step.state
            step.accepted
        }
        actions.interact(scope, scene, state, player, target, 1_000L, complete) {}
        accepted shouldBe 1
        state.stage shouldBe MineExpeditionStage.FACTORY_COAL
        state.factoryGeneratorStartedAt shouldBe 1_000L
        MineFactoryGeneratorCycle.ready(state, 6_999L) shouldBe false
        MineFactoryGeneratorCycle.ready(state, 7_000L) shouldBe true
        actions.interact(scope, scene, state, player, target, 7_000L, complete) {}
        accepted shouldBe 1
        state.factoryGeneratorStartedAt shouldBe 1_000L
    }

    test("disabling the cutaway keeps a visible working start console") {
        val enabled = MineExpeditionFurnishings.targets(scene, null, emptySet(), dieselGeneratorEnabled = true)
        enabled.single { it.id == "decor_diesel_generator" }.model shouldBe MineDieselGeneratorModel.kind
        val disabled = MineExpeditionFurnishings.targets(scene, null, emptySet(), dieselGeneratorEnabled = false)
        disabled.none { it.id == "decor_diesel_generator" } shouldBe true
        disabled.single { it.id == "generator_flywheel" }.model shouldBe "machine_console"
        var state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_WATER, completed = setOf(0, 1))
        val target = MineFactoryProgram.targets(plan, state).single()
        val player = paper.server.addPlayer().also { it.teleport(scene.at(target.position)) }
        actions.interact(scope, scene, state, player, target, 10_000L, { step -> state = step.state; step.accepted }) {}
        state.stage shouldBe MineExpeditionStage.FACTORY_COAL
        state.factoryGeneratorStartedAt shouldBe 10_000L
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
