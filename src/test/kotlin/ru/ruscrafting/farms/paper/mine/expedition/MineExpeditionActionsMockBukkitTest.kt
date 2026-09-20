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
        actions = MineExpeditionActions(paper.createSimplePlugin("FactoryActionsTest"), null)
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

    test("walking crank owns one tether and duplicate clicks do not reset progress") {
        val state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_WATER)
        val target = MineExpeditionObjectives.targets(scene.plan, state, null).first()
        val player = paper.server.addPlayer()
        val center = scene.at(target.position)
        player.teleport(center.clone().add(2.0, 0.0, 0.0))
        actions.interact(scope, scene, state, player, target, 1_000, { error("click completed crank") }) {}
        val anchor = world.entities.single { actions.owns(it) }
        anchor.isInvulnerable shouldBe true
        anchor.isPersistent shouldBe false
        (anchor as org.bukkit.entity.Mob).isInvisible shouldBe true
        // MockBukkit incorrectly treats a Player leash holder as not leashed. We check the
        // real anchor lifecycle here; client rope rendering remains an in-game check.
        var completed = 0
        repeat(41) { step ->
            val angle = step * Math.PI / 24
            player.teleport(center.clone().add(2 * kotlin.math.cos(angle), 0.0, 2 * kotlin.math.sin(angle)))
            if (step == 12) actions.interact(scope, scene, state, player, target, 2_000, { true }) {}
            actions.tick(scope, scene, state, listOf(target), listOf(player), 2_000L + step * 100,
                { _, result -> result.accepted shouldBe true; completed++; true }) { _, _ -> }
        }
        completed shouldBe 1
        world.entities.count { actions.owns(it) } shouldBe 0
        world.entities.filterIsInstance<org.bukkit.entity.Item>().size shouldBe 0
    }

    test("crank tether releases on leaving its ring, stage change, player release and shutdown") {
        val state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_WATER)
        val target = MineExpeditionObjectives.targets(scene.plan, state, null).first()
        val player = paper.server.addPlayer()
        val center = scene.at(target.position)
        fun attach() {
            player.teleport(center.clone().add(2.0, 0.0, 0.0))
            actions.interact(scope, scene, state, player, target, 1_000, { true }) {}
            world.entities.count { actions.owns(it) } shouldBe 1
        }
        attach()
        player.teleport(center.clone().add(4.0, 0.0, 0.0))
        actions.tick(scope, scene, state, listOf(target), listOf(player), 1_100, { _, _ -> error("left ring") }) { _, _ -> }
        world.entities.count { actions.owns(it) } shouldBe 0
        attach()
        actions.tick(scope, scene, state.copy(stage = MineExpeditionStage.FACTORY_COAL), emptyList(), listOf(player), 1_200,
            { _, _ -> error("wrong stage") }) { _, _ -> }
        world.entities.count { actions.owns(it) } shouldBe 0
        attach(); actions.release(player)
        world.entities.count { actions.owns(it) } shouldBe 0
        attach(); actions.clear(scope)
        world.entities.count { actions.owns(it) } shouldBe 0
        attach(); actions.cleanup()
        world.entities.count { actions.owns(it) } shouldBe 0
    }

    test("fuel is visibly carried and only an accepted delivery consumes its lease") {
        var state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_COAL)
        val targets = MineExpeditionObjectives.targets(scene.plan, state, null)
        val pickup = targets.single { it.interaction == MineExpeditionInteraction.PICKUP }
        val delivery = targets.single { it.interaction == MineExpeditionInteraction.DELIVER }
        val player = paper.server.addPlayer()
        repeat(3) { index ->
            player.teleport(scene.at(pickup.position))
            actions.interact(scope, scene, state, player, pickup, 1_000, { error("pickup cannot complete") }) {}
            actions.carrying(player, scope) shouldBe true
            player.teleport(scene.at(delivery.position))
            actions.interact(scope, scene, state, player, delivery, 2_000, { false }) {}
            actions.carrying(player, scope) shouldBe true
            actions.interact(scope, scene, state, player, delivery, 3_000, { step ->
                step.accepted shouldBe true; state = step.state; true
            }) {}
            actions.carrying(player, scope) shouldBe false
            if (index < 2) state.completed.size shouldBe index + 1
        }
        state.stage shouldBe MineExpeditionStage.FACTORY_HEAT
        world.entities.filterIsInstance<ItemDisplay>().size shouldBe 0
        world.entities.filterIsInstance<org.bukkit.entity.Item>().size shouldBe 0
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
