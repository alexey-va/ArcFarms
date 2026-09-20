package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.entity.ItemDisplay
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.mine.expedition.*
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene

class MineExpeditionActionsMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var scene: MineExpeditionScene
    lateinit var actions: MineExpeditionActions
    val scope = "factory:1:1"

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("factory_operations")
        val plan = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73L)
        val placement = MineExpeditionPlacement(world.name, 0, 60, 0, 73L)
        val surface = Location(world, 0.5, 65.0, 24.5)
        scene = MineExpeditionScene(plan, placement, world, "factory", 1, 1, 1, surface,
            WorksitePreparedScene(world, "factory", 1, 1, surface, surface, surface, emptyList()))
        actions = MineExpeditionActions(null)
    }
    afterEach { actions.cleanup(); paper.close() }

    test("crane waits for its whole cycle and repeated clicks cannot restart or duplicate it") {
        val state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_CRANE)
        val target = MineExpeditionObjectives.targets(scene.plan, state, null).single()
        val player = paper.server.addPlayer()
        val other = paper.server.addPlayer()
        player.teleport(scene.at(target.position))
        other.teleport(scene.at(target.position))
        var completed = 0
        val complete: (MineExpeditionStep) -> Boolean = { completed++; it.accepted }
        actions.interact(scope, scene, state, player, target, 1_000, complete) {}
        actions.interact(scope, scene, state, player, target, 3_000, complete) {}
        actions.interact(scope, scene, state, other, target, 3_000, complete) {}
        actions.tick(scope, scene, state, listOf(target), listOf(player, other), 5_499, { _, s -> complete(s) }) { _, _ -> }
        completed shouldBe 0
        actions.tick(scope, scene, state, listOf(target), listOf(player, other), 5_500, { _, s -> complete(s) }) { _, _ -> }
        completed shouldBe 1
        actions.tick(scope, scene, state, listOf(target), listOf(player, other), 9_000, { _, s -> complete(s) }) { _, _ -> }
        completed shouldBe 1
        actions.operationPhase(scope, target.id, 9_000) shouldBe 0.0
    }

    test("departure cancels the crane and a new operator must run a new cycle") {
        val state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_CRANE)
        val target = MineExpeditionObjectives.targets(scene.plan, state, null).single()
        val player = paper.server.addPlayer()
        player.teleport(scene.at(target.position))
        var completed = 0
        actions.interact(scope, scene, state, player, target, 1_000, { true }) {}
        actions.tick(scope, scene, state, listOf(target), emptyList(), 2_000, { _, _ -> completed++; true }) { _, _ -> }
        actions.operationPhase(scope, target.id, 9_000) shouldBe 0.0
        actions.tick(scope, scene, state, listOf(target), listOf(player), 9_000, { _, _ -> completed++; true }) { _, _ -> }
        completed shouldBe 0
        actions.interact(scope, scene, state, player, target, 10_000, { true }) {}
        actions.tick(scope, scene, state, listOf(target), listOf(player), 14_500, { _, _ -> completed++; true }) { _, _ -> }
        completed shouldBe 1
    }

    test("press requires the carried casting and consumes it only after the stroke returns") {
        val state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_INSTALL)
        val targets = MineExpeditionObjectives.targets(scene.plan, state, null)
        val pickup = targets.single { it.interaction == MineExpeditionInteraction.PICKUP }
        val press = targets.single { it.interaction == MineExpeditionInteraction.DELIVER }
        pickup.id shouldBe "crane_load"
        val player = paper.server.addPlayer()
        player.teleport(scene.at(press.position))
        actions.interact(scope, scene, state, player, press, 100, { error("early completion") }) {}
        actions.operationPhase(scope, press.id, 1_000) shouldBe 0.0
        player.teleport(scene.at(pickup.position))
        actions.interact(scope, scene, state, player, pickup, 1_000, { error("pickup completion") }) {}
        actions.claimed(scope, state.stage, 0) shouldBe true
        player.teleport(scene.at(press.position))
        actions.interact(scope, scene, state, player, press, 2_000, { error("early completion") }) {}
        var completed = 0
        actions.tick(scope, scene, state, targets, listOf(player), 4_399, { _, _ -> completed++; true }) { _, _ -> }
        completed shouldBe 0
        actions.carrying(player, scope) shouldBe true
        actions.tick(scope, scene, state, targets, listOf(player), 4_400, { _, step ->
            step.finished shouldBe true
            completed++; true
        }) { _, _ -> }
        completed shouldBe 1
        actions.carrying(player, scope) shouldBe false
        world.entities.filterIsInstance<ItemDisplay>().filter { it.isValid }.size shouldBe 0
    }

    test("walking away cancels the press without losing its cargo and cleanup drops no items") {
        val state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_INSTALL)
        val targets = MineExpeditionObjectives.targets(scene.plan, state, null)
        val player = paper.server.addPlayer()
        val pickup = targets.first()
        val press = targets.last()
        player.teleport(scene.at(pickup.position))
        actions.interact(scope, scene, state, player, pickup, 1_000, { true }) {}
        player.teleport(scene.at(press.position))
        actions.interact(scope, scene, state, player, press, 2_000, { true }) {}
        player.teleport(scene.at(press.position).add(7.0, 0.0, 0.0))
        actions.tick(scope, scene, state, targets, listOf(player), 3_000, { _, _ -> error("cancelled press completed") }) { _, _ -> }
        actions.operationPhase(scope, press.id, 5_000) shouldBe 0.0
        actions.carrying(player, scope) shouldBe true
        actions.clear(scope)
        actions.claimed(scope, state.stage, 0) shouldBe false
        world.entities.filter { it is ItemDisplay || it is org.bukkit.entity.Item }.size shouldBe 0
    }

    test("factory crane uses only current models and its chain remains attached to the carried load") {
        scene.refreshReady(building = false, complete = true)
        val plugin = paper.createSimplePlugin("FactoryMachineryTest")
        val machinery = MineExpeditionMachinery(plugin, { _, _ -> true })
        val roleKey = org.bukkit.NamespacedKey(plugin, "mine_expedition_machine_role")
        var state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_WATER)
        machinery.animate(scene, state, 0)
        val displays = world.entities.filterIsInstance<org.bukkit.entity.BlockDisplay>().associateBy {
            it.persistentDataContainer.get(roleKey, org.bukkit.persistence.PersistentDataType.STRING)
        }
        displays.keys shouldBe setOf("crane", "core", "molten")
        displays.getValue("core").transformation.scale.x shouldBe 0f
        displays.getValue("molten").transformation.scale.x shouldBe 0f
        state = state.copy(stage = MineExpeditionStage.FACTORY_CRANE)
        machinery.turn(scene, "crane_control", Math.PI)
        machinery.animate(scene, state, 2_250)
        val chain = displays.getValue("crane")
        val casting = displays.getValue("core")
        casting.transformation.scale.x shouldBe 1.6f
        (kotlin.math.abs(chain.location.y - chain.transformation.scale.y / 2 - casting.location.y - .5) < .0001) shouldBe true
        chain.location.x shouldBe casting.location.x
        chain.location.z shouldBe casting.location.z
        machinery.animate(scene, state.copy(stage = MineExpeditionStage.FACTORY_INSTALL), 4_500, cargoClaimed = true)
        casting.transformation.scale.x shouldBe 0f
        machinery.clear(scene)
        world.entities.filterIsInstance<org.bukkit.entity.BlockDisplay>().size shouldBe 0
    }
})
