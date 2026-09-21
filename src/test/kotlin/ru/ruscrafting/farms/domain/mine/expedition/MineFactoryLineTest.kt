package ru.ruscrafting.farms.domain.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MineFactoryLineTest : FunSpec({
    test("modern factory exposes one connected west to east line and one portal") {
        val plan = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 18L)

        plan.spawn shouldBe ExpeditionPoint(0, 5, 24)
        plan.exit shouldBe plan.spawn
        plan.stations.keys.containsAll(setOf(
            "crusher_feed", "control_crusher_left", "crushed_output", "furnace_input", "furnace_control",
            "pour_control", "pour_console", "crane_control", "crane_load", "repair_supply_0", "repair_supply_1",
            "repair_supply_2", "crusher_repair",
        )) shouldBe true
        listOf(
            "decor_crusher_left", "decor_conveyor_raw", "decor_furnace_left",
            "pour_control", "decor_roller_table", "assembly_socket",
        ).map(MineFactoryLine.machines::getValue).map(ExpeditionPoint::z).distinct() shouldBe listOf(-6)
        plan.stations.getValue("fuel_supply") shouldBe ExpeditionPoint(-25, 5, 15)
        plan.stations.getValue("water_valve_1") shouldBe ExpeditionPoint(-25, 5, -15)
    }

    test("new floor keeps the dark technical pad and light aisles") {
        val plan = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 18L)

        plan.blocks[ExpeditionPoint(0, 4, 0)] shouldBe "minecraft:polished_deepslate"
        plan.blocks[ExpeditionPoint(-28, 4, 0)] shouldBe "minecraft:polished_andesite"
        plan.blocks[ExpeditionPoint(0, 4, 6)] shouldBe "minecraft:polished_andesite"
        plan.blocks[ExpeditionPoint(-24, 4, 12)] shouldBe "minecraft:polished_deepslate"
        plan.blocks[ExpeditionPoint(-33, 4, -20)] shouldBe "minecraft:polished_andesite"
        MineFactoryLine.formerFloorMaterial(ExpeditionPoint(0, 4, 0)) shouldBe "minecraft:polished_andesite"
    }

    test("repair socket migration clears the hopper while preserving custom anchors") {
        val plan = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 18L)

        plan.stations.getValue("crusher_repair") shouldBe ExpeditionPoint(-24, 5, -3)
        MineFactoryLine.effectiveStation(plan, "crusher_repair") shouldBe ExpeditionPoint(-23, 5, -2)

        val custom = ExpeditionPoint(-20, 5, -1)
        MineFactoryLine.effectiveStation(
            plan.copy(stations = plan.stations + ("crusher_repair" to custom)), "crusher_repair",
        ) shouldBe custom
    }
})
