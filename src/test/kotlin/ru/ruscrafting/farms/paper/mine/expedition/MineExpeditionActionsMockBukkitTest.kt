package ru.ruscrafting.farms.paper.mine.expedition

import com.google.gson.Gson
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
    lateinit var cartVisuals: RecordingCartVisuals

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("factory_operations")
        // Keep the long-standing action contract on a legacy station map; the
        // connected-line flow below is covered by its own station extension.
        // Retain crane_load so the existing press lease tests exercise their
        // original casting cart path.
        val currentPlan = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 73L)
        val plan = currentPlan.copy(stations = currentPlan.stations - "crusher_feed")
        val placement = MineExpeditionPlacement(world.name, 0, 60, 0, 73L)
        val surface = Location(world, 0.5, 65.0, 24.5)
        scene = MineExpeditionScene(plan, placement, world, "factory", 1, 1, 1, surface,
            WorksitePreparedScene(world, "factory", 1, 1, surface, surface, surface, emptyList()))
        val plugin=paper.createSimplePlugin("FactoryActionsTest")
        // Packet transport is outside MockBukkit; retain real cargo/tether/lifecycle behavior.
        cartVisuals=RecordingCartVisuals()
        actions = MineExpeditionActions(plugin, null, MineFactoryCarts(plugin,cartVisuals))
    }
    afterEach { try { actions.cleanup();cartVisuals.bodies.all { it.removed } shouldBe true } finally { paper.close() } }

    test("variant pump and crusher run timed cycles before credit and stop after departure") {
        val state=MineExpeditionState(scene.placement,MineExpeditionStage.FACTORY_WATER,factoryProgram=1)
        val targets=MineExpeditionObjectives.targets(scene.plan,state,null)
        val target=targets.first { it.id=="control_pump_left" }
        val player=paper.server.addPlayer().also { it.teleport(scene.at(target.position)) }
        actions.interact(scope,scene,state,player,target,1_000,{error("early credit")}) {}
        actions.interact(scope,scene,state,player,target,2_000,{error("duplicate credit")}) {}
        player.teleport(scene.at(target.position).add(30.0,0.0,0.0))
        var completed=0
        actions.tick(scope,scene,state,targets,listOf(player),3_999,{_,_->completed++;true}) {_,_->}
        completed shouldBe 0
        actions.tick(scope,scene,state,targets,listOf(player),4_000,{_,step->completed++;step.state.completed shouldBe setOf(1);true}) {_,_->}
        completed shouldBe 1
        val crusher=targets.first { it.id=="control_crusher_left" }
        player.teleport(scene.at(crusher.position))
        actions.interact(scope,scene,state,player,crusher,5_000,{error("early credit")}) {}
        actions.tick(scope,scene,state,targets,emptyList(),6_000,{_,_->error("departed operator")}) {_,_->}
        actions.operationPhase(scope,crusher.id,9_000) shouldBe 0.0
    }

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
        player.teleport(scene.at(target.position).add(20.0,0.0,0.0))
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

    test("walking away leaves the press running with its casting on the table and completes once") {
        val state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_INSTALL)
        val targets = MineExpeditionObjectives.targets(scene.plan, state, null)
        val player = paper.server.addPlayer()
        val pickup = targets.first()
        val press = targets.last()
        player.teleport(scene.at(pickup.position))
        actions.interact(scope, scene, state, player, pickup, 1_000, { true }) {}
        player.teleport(scene.at(press.position))
        actions.interact(scope, scene, state, player, press, 2_000, { true }) {}
        // Cross the aisle while remaining inside the new production room.
        player.teleport(scene.at(press.position).add(0.0, 0.0, 20.0))
        actions.tick(scope, scene, state, targets, listOf(player), 3_000, { _, _ -> error("early press completion") }) { _, _ -> }
        world.entities.filterIsInstance<ItemDisplay>().size shouldBe 0
        world.entities.count { actions.owns(it) } shouldBe 0 // unloaded cart releases its rope immediately
        actions.carrying(player, scope) shouldBe true
        var completed=0
        actions.tick(scope, scene, state, targets, listOf(player), 4_400, { _,step -> completed++; step.finished shouldBe true; true }) { _,_ -> }
        actions.tick(scope, scene, state, targets, listOf(player), 5_000, { _,_ -> error("duplicate press completion") }) { _,_ -> }
        completed shouldBe 1
        actions.operationPhase(scope, press.id, 5_000) shouldBe 0.0
        actions.carrying(player,scope) shouldBe false
        actions.clear(scope)
        actions.claimed(scope, state.stage, 0) shouldBe false
        world.entities.filter { it is ItemDisplay || it is org.bukkit.entity.Item }.size shouldBe 0
    }

    test("factory valves turn from shared right-clicks without a rope and reject duplicate clicks") {
        var state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_WATER)
        val target = MineExpeditionObjectives.targets(scene.plan, state, null).first()
        target.interaction shouldBe MineExpeditionInteraction.VALVE
        val players = listOf(paper.server.addPlayer(), paper.server.addPlayer())
        players.forEach { it.teleport(scene.at(target.position).add(0.0, 0.0, 2.0)) }
        var completed = 0
        val complete: (MineExpeditionStep) -> Boolean = { step ->
            completed++; step.accepted shouldBe true; state = step.state; true
        }
        repeat(8) { i ->
            actions.interact(scope, scene, state, players[i % 2], target, 1000L + i * 300, complete) {}
            actions.interact(scope, scene, state, players[1 - i % 2], target, 1000L + i * 300, complete) {}
            world.entities.count { actions.owns(it) } shouldBe 0
            if (i < 7) {
                completed shouldBe 0
                var radians = 0.0
                actions.tick(scope, scene, state, listOf(target), players, 1000L + i * 300, { _, _ -> error("tick cannot turn valve") }) { _, angle -> radians = angle }
                radians shouldBe (i + 1) * Math.PI * 2 / 8
            }
        }
        completed shouldBe 1
        state.completed shouldBe setOf(0)
        actions.interact(scope, scene, state, players.first(), target, 5000, complete) {}
        completed shouldBe 1
        actions.hint(scope, players.first(), 5000) shouldBe null
    }

    test("valve progress retires on stage change and factory stages never ask for walking cranks") {
        val state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_WATER)
        val target = MineExpeditionObjectives.targets(scene.plan, state, null).first()
        val player = paper.server.addPlayer().also { it.teleport(scene.at(target.position)) }
        actions.interact(scope, scene, state, player, target, 1000, { error("one click completed") }) {}
        actions.tick(scope, scene, state.copy(stage = MineExpeditionStage.FACTORY_COAL), emptyList(), listOf(player), 2000,
            { _, _ -> error("retired valve completed") }) { _, _ -> }
        actions.hint(scope, player, 2000) shouldBe null
        MineExpeditionStage.entries.filter { it.name.startsWith("FACTORY_") }.forEach { stage ->
            MineExpeditionObjectives.targets(scene.plan, state.copy(stage = stage), null)
                .none { it.interaction == MineExpeditionInteraction.CRANK } shouldBe true
        }
    }

    test("walking crank owns one tether and duplicate clicks do not reset progress") {
        val state = MineExpeditionState(scene.placement, MineExpeditionStage.FACTORY_WATER)
        val target = MineExpeditionObjectives.targets(scene.plan, state, null).first().copy(interaction = MineExpeditionInteraction.CRANK)
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
        val target = MineExpeditionObjectives.targets(scene.plan, state, null).first().copy(interaction = MineExpeditionInteraction.CRANK)
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

    test("connected factory repairs in order and carries raw charge through crusher to furnace") {
        val connectedPlan = scene.plan.copy(stations = scene.plan.stations + connectedStationsForActions())
        val connected = MineExpeditionScene(
            connectedPlan,
            scene.placement,
            world,
            "connected-factory",
            2,
            2,
            2,
            scene.surface,
            WorksitePreparedScene(world, "connected-factory", 2, 2, scene.surface, scene.surface, scene.surface, emptyList()),
        ).also { it.refreshReady(building = false, complete = true) }
        val player = paper.server.addPlayer()
        var state = MineExpeditionState(connected.placement, MineExpeditionStage.FACTORY_WATER, factoryProgram = 2)
        fun complete(step: MineExpeditionStep): Boolean {
            if (step.accepted) state = step.state
            return step.accepted
        }

        val waterTargets = MineExpeditionObjectives.targets(connected.plan, state, null)
        val repairPickup = waterTargets.single { it.interaction == MineExpeditionInteraction.PICKUP }
        player.teleport(connected.at(repairPickup.position))
        actions.interact("connected-factory", connected, state, player, repairPickup, 1_100, ::complete) {}
        actions.carrying(player, "connected-factory") shouldBe true

        val wrongReceiver = MineExpeditionObjective(
            "furnace_input", connected.plan.stations.getValue("furnace_input"),
            MineExpeditionInteraction.DELIVER, "IRON_NUGGET", 0,
        )
        player.teleport(connected.at(wrongReceiver.position))
        actions.interact("connected-factory", connected, state, player, wrongReceiver, 1_200, ::complete) {}
        actions.carrying(player, "connected-factory") shouldBe true

        val repairReceiver = MineExpeditionObjectives.targets(connected.plan, state, null)
            .single { it.interaction == MineExpeditionInteraction.DELIVER }
        player.teleport(connected.at(repairReceiver.position))
        actions.interact("connected-factory", connected, state, player, repairReceiver, 1_300, ::complete) {}
        state.completed shouldBe setOf(0)
        actions.carrying(player, "connected-factory") shouldBe false

        val restored = Gson().fromJson(Gson().toJson(state), MineExpeditionState::class.java)
        restored.validate()
        MineExpeditionObjectives.targets(connected.plan, restored, null).single().id shouldBe "water_valve_1"
        state = restored

        repeat(8) { index ->
            val valve = MineExpeditionObjectives.targets(connected.plan, state, null).single()
            player.teleport(connected.at(valve.position))
            actions.interact("connected-factory", connected, state, player, valve, 2_000L + index * 300L, ::complete) {}
        }
        state.completed shouldBe setOf(0, 1)
        val crusher = MineExpeditionObjectives.targets(connected.plan, state, null).single()
        player.teleport(connected.at(crusher.position))
        actions.interact("connected-factory", connected, state, player, crusher, 5_000, ::complete) {}
        player.teleport(Location(world, 20.5, 65.0, 20.5)) // still inside the bounded expedition
        actions.tick("connected-factory", connected, state, listOf(crusher), listOf(player), 7_999,
            { _, step -> complete(step) }) { _, _ -> }
        state.stage shouldBe MineExpeditionStage.FACTORY_WATER
        actions.tick("connected-factory", connected, state, listOf(crusher), listOf(player), 8_000,
            { _, step -> complete(step) }) { _, _ -> }
        state.stage shouldBe MineExpeditionStage.FACTORY_COAL

        var chargeTargets = MineExpeditionObjectives.targets(connected.plan, state, null)
        val rawPickup = chargeTargets.single { it.interaction == MineExpeditionInteraction.PICKUP }
        player.teleport(connected.at(rawPickup.position))
        actions.interact("connected-factory", connected, state, player, rawPickup, 9_000, ::complete) {}
        val rawDelivery = MineExpeditionObjectives.targets(connected.plan, state, null)
            .single { it.interaction == MineExpeditionInteraction.DELIVER }
        player.teleport(connected.at(rawDelivery.position))
        actions.interact("connected-factory", connected, state, player, rawDelivery, 9_100, ::complete) {}
        state.completed shouldBe setOf(0)

        val process = MineExpeditionObjectives.targets(connected.plan, state, null).single()
        player.teleport(connected.at(process.position))
        actions.interact("connected-factory", connected, state, player, process, 10_000, ::complete) {}
        player.teleport(Location(world, 20.5, 65.0, 20.5))
        actions.tick("connected-factory", connected, state, listOf(process), listOf(player), 15_999,
            { _, step -> complete(step) }) { _, _ -> }
        state.completed shouldBe setOf(0)
        actions.tick("connected-factory", connected, state, listOf(process), listOf(player), 16_000,
            { _, step -> complete(step) }) { _, _ -> }
        state.completed shouldBe setOf(0, 1)

        // The processed charge now stays on the visible top-deck belt.  The
        // action owner credits its third durable checkpoint after its one-way journey,
        // without exposing a second cart or a manual furnace receiver.
        chargeTargets = MineExpeditionObjectives.targets(connected.plan, state, null)
        chargeTargets shouldBe emptyList()
        actions.tick("connected-factory", connected, state, emptyList(), listOf(player),
            17_000L, { _, step -> complete(step) }) { _, _ -> }
        actions.clear("connected-factory") // transient belt timing is safe to rebuild after reload
        repeat(7) { index ->
            actions.tick("connected-factory", connected, state, emptyList(), listOf(player),
                18_000L + index * 1_000L, { _, step -> complete(step) }) { _, _ -> }
        }
        state.stage shouldBe MineExpeditionStage.FACTORY_HEAT
        actions.carrying(player, "connected-factory") shouldBe false
        world.entities.count { actions.owns(it) } shouldBe 0

        // The same connected line places the billet before the automatic press
        // stroke; no one-metre press cart or receiver objective is created.
        state = state.copy(stage = MineExpeditionStage.FACTORY_INSTALL, completed = emptySet(), heatStartedAt = 0L)
        MineExpeditionObjectives.targets(connected.plan, state, null) shouldBe emptyList()
        repeat(5) { index ->
            actions.tick("connected-factory", connected, state, emptyList(), listOf(player),
                25_000L + index * 1_000L, { _, step -> complete(step) }) { _, _ -> }
            actions.operationPhase("connected-factory", "assembly_socket", 29_000L) shouldBe 0.0
        }
        state.stage shouldBe MineExpeditionStage.FACTORY_INSTALL
        actions.transferPhase("connected-factory", "roller_transfer") shouldBe Math.PI * 2
        for (now in listOf(30_000L, 31_000L, 31_399L)) {
            actions.tick("connected-factory", connected, state, emptyList(), listOf(player),
                now, { _, _ -> error("press finished before its return stroke") }) { _, _ -> }
        }
        actions.tick("connected-factory", connected, state, emptyList(), listOf(player),
            31_400L, { _, step -> complete(step) }) { _, _ -> }
        state.stage shouldBe MineExpeditionStage.COMPLETE
        world.entities.count { actions.owns(it) } shouldBe 0
    }

    test("connected furnace has a visible air control and preserves its ready result until clicked") {
        val connected = scene.copyForConnectedActions()
        var state = MineExpeditionState(connected.placement, MineExpeditionStage.FACTORY_HEAT)
        val target = MineExpeditionObjectives.targets(connected.plan, state, null).single()
        val player = paper.server.addPlayer().also { it.teleport(connected.at(target.position)) }
        fun complete(step: MineExpeditionStep): Boolean {
            if (step.accepted) state = step.state
            return step.accepted
        }
        actions.interact(scope, connected, state, player, target, 1_000, ::complete) {}
        actions.factoryHeat(scope)!!.running shouldBe true
        actions.interact(scope, connected, state, player, target, 2_000, ::complete) {}
        actions.factoryHeat(scope)!!.running shouldBe true
        actions.factoryHeat(scope)!!.elapsedMillis shouldBe 1_000L
        var now = 2_000L
        repeat(5) {
            now += 1_000L
            actions.tick(scope, connected, state, listOf(target), listOf(player), now,
                { _, _ -> error("heat must wait for the player to confirm") }) { _, _ -> }
        }
        actions.factoryHeat(scope)!!.ready shouldBe true
        val ready = actions.factoryHeat(scope)
        actions.tick(scope, connected, state, listOf(target), listOf(player), now + 60_000,
            { _, _ -> error("ready does not expire or auto-credit") }) { _, _ -> }
        actions.factoryHeat(scope) shouldBe ready
        actions.interact(scope, connected, state, player, target, now + 60_000, ::complete) {}
        state.stage shouldBe MineExpeditionStage.FACTORY_POUR
        actions.factoryHeat(scope) shouldBe null
    }

    test("connected conveyor pauses in an empty room without catching up or accepting a stale cart") {
        val connected = scene.copyForConnectedActions()
        var state = MineExpeditionState(connected.placement, MineExpeditionStage.FACTORY_COAL, completed = setOf(0, 1))
        val player = paper.server.addPlayer().also { it.teleport(connected.surface) }
        fun tick(now: Long, present: Boolean) {
            actions.tick(scope, connected, state, emptyList(), if (present) listOf(player) else emptyList(), now,
                { _, step -> if (step.accepted) state = step.state; step.accepted }) { _, _ -> }
        }
        tick(1_000, true)
        tick(2_000, true)
        val phase = actions.transferPhase(scope, "charge_transfer")
        tick(60_000, false)
        actions.transferPhase(scope, "charge_transfer") shouldBe phase
        tick(60_000, true)
        actions.transferPhase(scope, "charge_transfer") shouldBe phase
        val stale = MineExpeditionObjective("furnace_input", connected.plan.stations.getValue("furnace_input"),
            MineExpeditionInteraction.DELIVER, "IRON_NUGGET", 2)
        actions.interact(scope, connected, state, player, stale, 60_000, { error("stale receiver") }) {}
        state.completed shouldBe setOf(0, 1)
        repeat(5) { tick(61_000L + it * 1_000L, true) }
        state.stage shouldBe MineExpeditionStage.FACTORY_HEAT
        actions.carrying(player, scope) shouldBe false
    }

    test("casting is a measured pour and cannot be completed by click spam or another player") {
        var state=MineExpeditionState(scene.placement,MineExpeditionStage.FACTORY_POUR)
        val target=MineExpeditionObjectives.targets(scene.plan,state,null).single()
        target.id shouldBe "pour_console"
        target.interaction shouldBe MineExpeditionInteraction.POUR
        val player=paper.server.addPlayer().also { it.teleport(scene.at(target.position)) }
        val other=paper.server.addPlayer().also { it.teleport(player.location) }
        var completed=0
        val complete:(MineExpeditionStep)->Boolean={ completed++;state=it.state;it.accepted }
        actions.interact(scope,scene,state,player,target,1000,complete) {}
        actions.interact(scope,scene,state,player,target,1100,complete) {}
        actions.interact(scope,scene,state,other,target,11_500,complete) {}
        completed shouldBe 0
        actions.pourReady(scope,10_999) shouldBe false
        actions.pourReady(scope,11_000) shouldBe true
        actions.pourReady(scope,14_000) shouldBe true
        actions.pourReady(scope,14_001) shouldBe false
        actions.pourValues(scope,11_000) shouldBe mapOf("percent" to net.kyori.adventure.text.Component.text(100))
        actions.pourLabel(scope,11_000) shouldBe "pour-close"
        actions.interact(scope,scene,state,player,target,11_000,{ false }) {}
        actions.pourLabel(scope,11_000) shouldBe "pour-close"
        actions.interact(scope,scene,state,player,target,11_000,complete) {}
        completed shouldBe 1
        state.stage shouldBe MineExpeditionStage.FACTORY_CRANE
        actions.pourReady(scope,11_000) shouldBe false
        actions.interact(scope,scene,state,player,target,8000,complete) {}
        completed shouldBe 1
    }

    test("underdose overflow and departure reset a pour without credit") {
        val state=MineExpeditionState(scene.placement,MineExpeditionStage.FACTORY_POUR)
        val target=MineExpeditionObjectives.targets(scene.plan,state,null).single()
        val player=paper.server.addPlayer().also { it.teleport(scene.at(target.position)) }
        fun open(now:Long) { actions.interact(scope,scene,state,player,target,now,{ error("unexpected casting credit") }) {} }
        open(1000);open(3000)
        actions.pourLabel(scope,3000) shouldBe "control.pour_console"
        open(4000)
        var phase=1.0
        actions.tick(scope,scene,state,listOf(target),listOf(player),17_001,{_,_->error("overflow credited") }) { _,p->phase=p }
        phase shouldBe 0.0
        actions.pourLabel(scope,17_001) shouldBe "control.pour_console"
        actions.pourReady(scope,17_001) shouldBe false
        actions.hint(scope, player, 17_500) shouldBe net.kyori.adventure.text.Component.text("pour-overflow")
        open(18000)
        actions.hint(scope, player, 18_000) shouldBe net.kyori.adventure.text.Component.text("pour-filling")
        actions.tick(scope,scene,state,listOf(target),emptyList(),19000,{_,_->error("departed casting credited") }) { _,_-> }
        actions.pourLabel(scope,19000) shouldBe "control.pour_console"
        actions.hint(scope, player, 19_100) shouldBe null
    }

    test("cart contact and click delivery share one lease and leaving the factory retires every part") {
        var state=MineExpeditionState(scene.placement,MineExpeditionStage.FACTORY_COAL)
        val targets=MineExpeditionObjectives.targets(scene.plan,state,null)
        val player=paper.server.addPlayer()
        val pickup=targets.first();val delivery=targets.last()
        fun take() {
            player.teleport(scene.at(pickup.position))
            actions.interact(scope,scene,state,player,pickup,1000,{ error("pickup credit") }) {}
            actions.interact(scope,scene,state,player,pickup,1100,{ error("duplicate pickup credit") }) {}
            world.entities.count { actions.owns(it) } shouldBe 1
            world.entities.filterIsInstance<ItemDisplay>().size shouldBe 0
        }
        take()
        val anchor=world.entities.single { actions.owns(it) }
        val at=anchor.location
        player.teleport(player.location.apply { yaw=180f })
        actions.tick(scope,scene,state,targets,listOf(player),1200,{_,_->error("rotation credit") }) {_,_->}
        anchor.location.x shouldBe at.x
        anchor.location.z shouldBe at.z
        val start=player.location
        val end=scene.at(delivery.position)
        var credits=0
        repeat(70) { step ->
            player.teleport(start.clone().add((end.x-start.x)*(step+1)/70,0.0,(end.z-start.z)*(step+1)/70))
            actions.tick(scope,scene,state,MineExpeditionObjectives.targets(scene.plan,state,null),listOf(player),1300L+step*100,
                {_,result-> credits++;state=result.state;result.accepted }) {_,_->}
        }
        credits shouldBe 1
        actions.interact(scope,scene,state,player,delivery,9000,{ error("click after auto delivery") }) {}
        actions.carrying(player,scope) shouldBe false
        take()
        player.teleport(Location(world,1000.0,65.0,1000.0))
        actions.tick(scope,scene,state,targets,listOf(player),10000,{_,_->error("outside factory credit") }) {_,_->}
        actions.carrying(player,scope) shouldBe false
        world.entities.count { actions.owns(it) } shouldBe 0
        actions.claimed(scope,state.stage,1) shouldBe false
        take();actions.release(player)
        world.entities.count { actions.owns(it) } shouldBe 0
        take();actions.clear(scope)
        world.entities.count { actions.owns(it) } shouldBe 0
        take();actions.cleanup()
        world.entities.count { actions.owns(it) } shouldBe 0
        world.entities.filterIsInstance<org.bukkit.entity.BlockDisplay>().size shouldBe 0
    }

    test("factory crane uses only current models and its chain remains attached to the carried load") {
        scene = scene.copyForConnectedActions()
        // Exercise the persisted geometry-v3 anchor; machinery must migrate
        // this unchanged legacy value to the casting-side table end.
        scene = MineExpeditionScene(scene.plan.copy(stations = scene.plan.stations +
            ("crane_load" to ExpeditionPoint(17, 5, -6))), scene.placement, scene.world,
            scene.zoneId, scene.sequence, scene.objectiveNonce, scene.journalSequence, scene.surface, scene.prepared)
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
        val manual = casting.location.clone().add(.75, -.25, 1.25)
        machinery.manualCrane(scene, manual)
        machinery.animate(scene, state, 2_300)
        casting.location shouldBe manual
        chain.location.x shouldBe manual.x
        chain.location.z shouldBe manual.z
        (kotlin.math.abs(chain.location.y - chain.transformation.scale.y / 2 - manual.y - .5) < .0001) shouldBe true
        world.entities.filterIsInstance<org.bukkit.entity.BlockDisplay>().size shouldBe 3
        // A stale optional pose must not move the billet back after its durable landing.
        val installing = state.copy(stage = MineExpeditionStage.FACTORY_INSTALL)
        machinery.animate(scene, installing, 4_500, cargoClaimed = true)
        casting.transformation.scale.x shouldBe 1.6f
        val craneLoad = MineFactoryLine.effectiveStation(scene.plan, "crane_load")
        casting.location.x shouldBe scene.at(craneLoad).x
        casting.location.z shouldBe scene.at(craneLoad).z
        machinery.turn(scene, "roller_transfer", Math.PI * 2)
        machinery.animate(scene, installing, 8_500)
        casting.location.x shouldBe scene.at(scene.plan.stations.getValue("assembly_socket")).x
        casting.location.z shouldBe scene.at(scene.plan.stations.getValue("assembly_socket")).z
        machinery.clear(scene)
        world.entities.filterIsInstance<org.bukkit.entity.BlockDisplay>().size shouldBe 0
    }
})

private fun connectedStationsForActions(): Map<String, ExpeditionPoint> = mapOf(
    "crusher_feed" to ExpeditionPoint(-4, 5, 6),
    "crushed_output" to ExpeditionPoint(4, 5, 6),
    "crusher_repair" to ExpeditionPoint(-4, 5, 8),
    "repair_supply_0" to ExpeditionPoint(-10, 5, 8),
    "repair_supply_1" to ExpeditionPoint(0, 5, 8),
    "repair_supply_2" to ExpeditionPoint(10, 5, 8),
    "control_crusher_left" to ExpeditionPoint(-4, 5, 10),
)

/** Records packet bodies without replacing world, player, native leash or cargo transitions. */
private class RecordingCartVisuals : MineFactoryCartVisuals {
    class Body : MineFactoryCartVisuals.Body {
        var removed=false
        override fun render(at: Location,yaw: Float,phase: Float) { check(!removed) }
        override fun remove() { removed=true }
    }
    val bodies=mutableListOf<Body>()
    override fun spawn(at: Location,parts: List<MineDisplayBlueprints.Part>)=Body().also { bodies+=it }
    override fun close() { check(bodies.all { it.removed }) }
}

private fun MineExpeditionScene.copyForConnectedActions(): MineExpeditionScene = MineExpeditionScene(
    plan.copy(stations = plan.stations + connectedStationsForActions()), placement, world,
    "connected-thermal", 3, 3, 3, surface,
    WorksitePreparedScene(world, "connected-thermal", 3, 3, surface, surface, surface, emptyList()),
).also { it.refreshReady(building = false, complete = true) }
