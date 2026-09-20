package ru.ruscrafting.farms.domain.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Material
import kotlin.math.abs

class MineExpeditionGeometryTest : FunSpec({
    test("each scene is replayable, seed-sensitive and bounded") {
        MineExpeditionKind.entries.forEach { kind ->
            val first = MineExpeditionGenerator.plan(kind, 0x51A7L, geometryVersion = 1)
            val replay = MineExpeditionGenerator.plan(kind, 0x51A7L, geometryVersion = 1)
            val other = MineExpeditionGenerator.plan(kind, 0x51A8L, geometryVersion = 1)

            first shouldBe replay
            first.blocks shouldNotBe other.blocks
            first.blocks.keys.all(first.bounds::contains) shouldBe true
            first.blocks.size shouldBe first.blocks.keys.size
            (first.blocks.size <= 180_000) shouldBe true
            first.blocks.size shouldBeGreaterThan 1_000
            first.blocks.values.any { it == MineExpeditionBuilder.AIR } shouldBe true
            first.blocks.values.any { it.startsWith("minecraft:lantern") } shouldBe true
            when (kind) {
                MineExpeditionKind.LAST_DESCENT -> {
                    first.walkingRoutes.size shouldBe 8
                    first.blocks.values.any { it.contains("water") } shouldBe true
                    first.blocks.values.any { it.contains("redstone_block") } shouldBe true
                    first.blocks[ExpeditionPoint(0, 18, -20)] shouldBe "minecraft:redstone_block"
                }
                MineExpeditionKind.DRILLING_ARK -> {
                    first.walkingRoutes.size shouldBe 6
                    first.blocks.values.any { it.contains("beacon") } shouldBe true
                    first.blocks.values.any { it.contains("iron_chain") } shouldBe true
                    first.blocks.values.any { it.contains("water_cauldron") } shouldBe true
                }
                MineExpeditionKind.DEAD_FACTORY -> {
                    first.walkingRoutes.size shouldBe 5
                    first.blocks.values.any { it.contains("magma_block") } shouldBe true
                    first.blocks.values.any { it.contains("water") } shouldBe true
                }
            }
        }
    }

    test("descent exposes every stop, supported walk and unobstructed front sightline") {
        val plan = MineExpeditionGenerator.plan(MineExpeditionKind.LAST_DESCENT, 19L, geometryVersion = 1)
        plan.stations.keys.containsAll(setOf(
            "entry", "exit", "lift_top", "lift_middle", "lift_bottom", "power_supply", "power_socket",
            "counterweight_0", "counterweight_1", "counterweight_2", "core_valve_0", "core_valve_1",
            "core_valve_2", "core_start",
        )) shouldBe true
        plan.routes.getValue("lift") shouldBe listOf(
            plan.stations.getValue("lift_top"), plan.stations.getValue("lift_middle"), plan.stations.getValue("lift_bottom"),
        )
        assertSupportedAndClear(plan)
        listOf("lift_top", "lift_middle", "lift_bottom").forEach { id ->
            val stop = plan.stations.getValue(id)
            for (y in stop.y..stop.y + 4) for (x in stop.x - 3..stop.x + 3) {
                for (z in stop.z..stop.z + 14) {
                    val cell = plan.blocks[ExpeditionPoint(x, y, z)]
                    (cell == MineExpeditionBuilder.AIR || cell?.startsWith("minecraft:light[") == true) shouldBe true
                }
            }
        }
        for (y in 8..55) for (x in -3..3) for (z in -2..2) {
            plan.blocks[ExpeditionPoint(x, y, z)] shouldBe MineExpeditionBuilder.AIR
        }
    }

    test("ark has a meaningful plus-Z fork and the full crawler sweep is explicit") {
        val plan = MineExpeditionGenerator.plan(MineExpeditionKind.DRILLING_ARK, 77L, geometryVersion = 1)
        val left = plan.routes.getValue("ark_left")
        val right = plan.routes.getValue("ark_right")
        left.first() shouldBe right.first()
        left.last() shouldBe right.last()
        left[3] shouldBe right[3]
        left.any { it.x < -8 } shouldBe true
        right.any { it.x > 8 } shouldBe true
        plan.walkingRoutes[0].zipWithNext().all { (a, b) -> manhattan(a, b) == 1 } shouldBe true
        plan.walkingRoutes[1].zipWithNext().all { (a, b) -> manhattan(a, b) == 1 } shouldBe true
        assertSupportedAndClear(plan)
        plan.stations.values.all { station ->
            plan.walkingRoutes.any { route -> route.any { distance(station, it) <= 3 } }
        } shouldBe true
        val machinePaths = listOf(
            listOf(plan.stations.getValue("ark_start"), plan.stations.getValue("ark_mid")),
            plan.routes.getValue("ark_left"),
            plan.routes.getValue("ark_right"),
        )
        machinePaths.flatMap(::cardinalPath).distinct().forEach { center ->
            for (dx in -5..5) for (dz in -8..8) {
                plan.blocks[center.offset(dx = dx, dy = -3, dz = dz)] shouldNotBe null
                plan.blocks[center.offset(dx = dx, dy = 0, dz = dz)] shouldBe MineExpeditionBuilder.AIR
                plan.blocks[center.offset(dx = dx, dy = 8, dz = dz)] shouldBe MineExpeditionBuilder.AIR
            }
        }
    }

    test("factory stations are within interaction range of a walking route") {
        val plan = MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY, 101L, geometryVersion = 1)
        plan.stations.keys.containsAll(setOf(
            "entry", "exit", "water_valve_0", "water_valve_1", "water_valve_2", "fuel_supply", "furnace_input",
            "furnace_control", "pour_control", "crane_control", "assembly_socket",
        )) shouldBe true
        assertSupportedAndClear(plan)
        plan.stations.values.all { station ->
            plan.walkingRoutes.any { route -> route.any { distance(station, it) <= 3 } }
        } shouldBe true
        plan.blocks.values.any { it.contains("magma") } shouldBe true
        plan.blocks.values.any { it.contains("water") } shouldBe true
    }

    test("every serialized block uses a known vanilla material id") {
        MineExpeditionKind.entries.flatMap { kind ->
            MineExpeditionGenerator.plan(kind, 0xB10CL, geometryVersion = 1).blocks.values
        }.map { it.substringAfter("minecraft:").substringBefore('[').uppercase() }.distinct().forEach { id ->
            Material.matchMaterial(id) shouldNotBe null
        }
    }
}) {
    companion object {
        private fun assertSupportedAndClear(plan: MineExpeditionPlan) {
            plan.walkingRoutes.flatten().distinct().forEach { feet ->
                plan.blocks[feet] shouldBe MineExpeditionBuilder.AIR
                plan.blocks[feet.offset(dy = 1)] shouldBe MineExpeditionBuilder.AIR
                plan.blocks[feet.offset(dy = 2)] shouldBe MineExpeditionBuilder.AIR
                val deckFloor = plan.kind == MineExpeditionKind.LAST_DESCENT && feet.x in -3..3 && feet.z in -2..2
                if (deckFloor) {
                    val stop = plan.routes.getValue("lift").single { it.y == feet.y }
                    MineExpeditionMachines.blocks(plan.kind)[ExpeditionPoint(feet.x - stop.x, -1, feet.z - stop.z)] shouldNotBe null
                } else plan.blocks[feet.offset(dy = -1)] shouldNotBe MineExpeditionBuilder.AIR
            }
        }

        private fun distance(left: ExpeditionPoint, right: ExpeditionPoint): Int =
            abs(left.x - right.x) + abs(left.y - right.y) + abs(left.z - right.z)

        private fun manhattan(left: ExpeditionPoint, right: ExpeditionPoint): Int = distance(left, right)

        private fun cardinalPath(waypoints: List<ExpeditionPoint>): List<ExpeditionPoint> {
            val result = mutableListOf<ExpeditionPoint>()
            waypoints.zipWithNext().forEach { (from, to) ->
                val segment = mutableListOf(from)
                var point = from
                while (point.x != to.x) {
                    point = point.offset(dx = if (to.x > point.x) 1 else -1)
                    segment += point
                }
                while (point.y != to.y) {
                    point = point.offset(dy = if (to.y > point.y) 1 else -1)
                    segment += point
                }
                while (point.z != to.z) {
                    point = point.offset(dz = if (to.z > point.z) 1 else -1)
                    segment += point
                }
                if (result.isEmpty()) result += segment else result += segment.drop(1)
            }
            if (waypoints.size == 1) result += waypoints
            return result
        }
    }
}
