package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.mine.expedition.*
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene

class MineFactoryLineStartTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var plan: MineExpeditionPlan
    lateinit var scene: MineExpeditionScene
    lateinit var actions: MineExpeditionActions
    lateinit var cartVisuals: FakeFactoryCartVisuals
    val scope = "factory:line-start"

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("factory_line_start")
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
        val plugin = paper.createSimplePlugin("FactoryLineStartTest")
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

    test("old unfinished plans start on the crusher console and reject a stale start after completion") {
        var state = MineFactoryExperiments.normalizeConnected(MineExpeditionState(
            scene.placement, MineExpeditionStage.FACTORY_WATER, completed = setOf(0, 1)))
        val target = MineFactoryProgram.targets(plan, state).single()
        target.id shouldBe "control_crusher_left"
        target.interaction shouldBe MineExpeditionInteraction.OPERATE
        val fixture = MineExpeditionFurnishings.fixtures(plan).single { it.id == target.id }
        fixture.model shouldBe "mounted_console"
        fixture.at shouldBe target.position
        val player = paper.server.addPlayer().also { it.teleport(scene.at(target.position)) }
        var accepted = 0
        val complete: (MineExpeditionStep) -> Boolean = { step ->
            if (step.accepted) { accepted++; state = step.state }
            step.accepted
        }
        actions.interact(scope, scene, state, player, target, 1_000L, complete) {}
        accepted shouldBe 0
        actions.tick(scope, scene, state, listOf(target), listOf(player), 3_999L, { _, step -> complete(step) }) { _, _ -> }
        accepted shouldBe 0
        actions.tick(scope, scene, state, listOf(target), listOf(player), 4_000L, { _, step -> complete(step) }) { _, _ -> }
        accepted shouldBe 1
        state.stage shouldBe MineExpeditionStage.FACTORY_COAL
        state.factoryGeneratorStartedAt shouldBe 0L
        actions.interact(scope, scene, state, player, target, 4_100L, complete) {}
        accepted shouldBe 1
    }

    test("persistent and editor furnishing sets keep the ore bunker and line without retired utilities") {
        val fixtures = MineExpeditionFurnishings.fixtures(plan).associateBy { it.id }
        fixtures.getValue("fuel_supply").model shouldBe "charge_bunker"
        setOf("decor_crusher_left", "decor_conveyor_raw", "decor_furnace_left", "pour_control",
            "decor_roller_table", "assembly_socket").all { it in fixtures } shouldBe true
        fixtures.keys.none { it.contains("diesel") || it.contains("pump") || it.contains("tank") ||
            it.contains("waterwheel") || it.contains("water_header") || it.contains("valve") ||
            it.contains("generator") || it.startsWith("repair_supply_") || it == "crusher_repair" } shouldBe true
    }

    test("production pickup ignores retired generator warmup from an old save") {
        val state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_COAL,
            factoryGeneratorStartedAt = 50_000L)
        val pickup = MineExpeditionObjectives.targets(plan, state, null)
            .single { it.interaction == MineExpeditionInteraction.PICKUP }
        val player = paper.server.addPlayer().also { it.teleport(scene.at(pickup.position)) }
        actions.interact(scope, scene, state, player, pickup, 1_000L,
            { error("pickup must not award a checkpoint") }) {}
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
