package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class MineScenarioTest : FreeSpec({
    "catalog contains every scenario exactly once and resolves string ids" {
        val expected = listOf(
            "creature_nest", "cave_in", "flooding", "gas_leak", "power_failure", "injured_miner", "runaway_cart", "convoy",
            "lift_breakdown", "bat_swarm", "fungal_bloom", "root_invasion", "lava_breach", "ancient_door", "old_warehouse", "drill_trial",
        )
        MineScenarioCatalog.definitions.keys.shouldContainExactlyInAnyOrder(expected)
        MineScenarioCatalog.definitions.size shouldBe 16
        expected.forEach { id -> MineScenarioCatalog.definition(id) shouldBe MineScenarioCatalog.definitions.getValue(id) }
        MineScenarioCatalog.definition("missing") shouldBe null
    }

    "stages are bounded, safe block materials and not sixteen clones" {
        val definitions = MineScenarioCatalog.definitions.values
        definitions.flatMap { it.stages }.forEach { stage ->
            (stage.required in 1..8) shouldBe true
            (stage.targetCount in stage.required..8) shouldBe true
            (stage.durationSeconds in 0..20) shouldBe true
            stage.material shouldBe stage.material.uppercase()
            (stage.material !in setOf("WATER", "LAVA")) shouldBe true
        }
        (definitions.map { it.stages.map(MineScenarioStage::action) }.distinct().size > 12) shouldBe true
        definitions.flatMap { it.stages }.map(MineScenarioStage::action).distinct().size shouldBe 9
    }

    "definitions preserve each event's intended action sequence" {
        val expected = mapOf(
            "creature_nest" to listOf(MineScenarioAction.DEFEND, MineScenarioAction.BREAK),
            "cave_in" to listOf(MineScenarioAction.BREAK, MineScenarioAction.INTERACT),
            "flooding" to listOf(MineScenarioAction.INTERACT, MineScenarioAction.SUSTAIN),
            "gas_leak" to listOf(MineScenarioAction.ORDERED_INTERACT, MineScenarioAction.SUSTAIN),
            "power_failure" to listOf(MineScenarioAction.BREAK, MineScenarioAction.ORDERED_INTERACT),
            "injured_miner" to listOf(MineScenarioAction.INTERACT, MineScenarioAction.CARRY),
            "runaway_cart" to listOf(MineScenarioAction.STEER, MineScenarioAction.INTERACT, MineScenarioAction.ESCORT),
            "convoy" to listOf(MineScenarioAction.BREAK, MineScenarioAction.ESCORT),
            "lift_breakdown" to listOf(MineScenarioAction.INTERACT, MineScenarioAction.CARRY, MineScenarioAction.ORDERED_INTERACT),
            "bat_swarm" to listOf(MineScenarioAction.HERD, MineScenarioAction.INTERACT),
            "fungal_bloom" to listOf(MineScenarioAction.BREAK, MineScenarioAction.BREAK),
            "root_invasion" to listOf(MineScenarioAction.BREAK, MineScenarioAction.BREAK),
            "lava_breach" to listOf(MineScenarioAction.CARRY, MineScenarioAction.ORDERED_INTERACT),
            "ancient_door" to listOf(MineScenarioAction.CARRY, MineScenarioAction.ORDERED_INTERACT),
            "old_warehouse" to listOf(MineScenarioAction.CARRY, MineScenarioAction.INTERACT),
            "drill_trial" to listOf(MineScenarioAction.CARRY, MineScenarioAction.SUSTAIN, MineScenarioAction.BREAK),
        )
        expected.forEach { (id, actions) -> MineScenarioCatalog.definition(id)!!.stages.map(MineScenarioStage::action) shouldBe actions }
    }

    "floor resolution is nearest and deterministic on ties" {
        val floors = listOf(MineScenarioFloor("top", 100.0), MineScenarioFloor("bottom", 0.0), MineScenarioFloor("middle", 50.0))
        MineScenarioCatalog.resolveFloor(floors, 61.0)?.id shouldBe "middle"
        MineScenarioCatalog.resolveFloor(floors, 75.0)?.id shouldBe "middle"
        MineScenarioCatalog.resolveFloor(listOf(MineScenarioFloor("zeta", 50.0), MineScenarioFloor("alpha", 50.0)), 50.0)?.id shouldBe "alpha"
        MineScenarioCatalog.resolveFloor(floors, 200.0)?.id shouldBe "top"
    }

    "scenario scopes and transport stages enforce destination policy" {
        MineScenarioCatalog.definition("lift_breakdown")!!.scope shouldBe MineScenarioScope.MINE_WIDE
        MineScenarioCatalog.definition("convoy")!!.scope shouldBe MineScenarioScope.MULTI_FLOOR
        MineScenarioCatalog.definition("cave_in")!!.scope shouldBe MineScenarioScope.FLOOR
        MineScenarioCatalog.requiresDifferentFloorDestination("injured_miner") shouldBe true
        MineScenarioCatalog.requiresDifferentFloorDestination("convoy") shouldBe true
        MineScenarioCatalog.requiresDifferentFloorDestination("runaway_cart") shouldBe true
        MineScenarioCatalog.requiresDifferentFloorDestination("cave_in") shouldBe false
        MineScenarioCatalog.requiresDifferentFloorDestination("ancient_door") shouldBe false
        MineScenarioCatalog.requiresDifferentFloorDestination("drill_trial") shouldBe false
        MineScenarioCatalog.requiresDifferentFloorDestination("missing") shouldBe false
        MineScenarioCatalog.destinationAllowed("injured_miner", "top", "bottom") shouldBe true
        MineScenarioCatalog.destinationAllowed("convoy", "top", "top") shouldBe false
        MineScenarioCatalog.destinationAllowed("cave_in", "top", "top") shouldBe true
        MineScenarioCatalog.destinationAllowed("ancient_door", "top", "top") shouldBe true
        MineScenarioCatalog.destinationAllowed("missing", "top", "bottom") shouldBe false
    }

    "stage view spans quotas and returns null after completion" {
        val definition = MineScenarioCatalog.definition("lift_breakdown")!!
        definition.totalRequired shouldBe 3
        definition.stageAt(0) shouldBe MineScenarioStageView(0, definition, 0)
        definition.stageAt(1) shouldBe MineScenarioStageView(1, definition, 0)
        definition.stageAt(2) shouldBe MineScenarioStageView(2, definition, 0)
        definition.stageAt(definition.totalRequired) shouldBe null
        definition.stageAt(definition.totalRequired + 4) shouldBe null
        shouldThrow<IllegalArgumentException> { definition.stageAt(-1) }

        val drill = MineScenarioCatalog.definition("drill_trial")!!
        drill.totalRequired shouldBe 4
        drill.stageAt(1) shouldBe MineScenarioStageView(1, drill, 0)
        drill.stageAt(3) shouldBe MineScenarioStageView(2, drill, 0)
    }
})
