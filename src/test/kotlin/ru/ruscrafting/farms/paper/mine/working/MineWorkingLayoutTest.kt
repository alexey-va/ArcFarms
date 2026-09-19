package ru.ruscrafting.farms.paper.mine.working

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

class MineWorkingLayoutTest : FunSpec({
    val entrance = WorksitePosition("rc_atelier_compact_mine", 44, 110, 84)

    test("machine faces rotate with the tunnel instead of facing the surrounding wall") {
        listOf("north", "east", "south", "west").forEachIndexed { direction, facing ->
            val plan = MineWorkingLayout.plan(MineIncidentType.ORE_WORKSHOP,
                MineWorkingPlacement(entrance, direction, "top"))
            plan.blocks.getValue(plan.stations.getValue("furnace")) shouldBe
                "minecraft:furnace[facing=$facing,lit=false]"
        }
    }

    test("all directions keep the bounded footprint and stage-specific targets") {
        (0..3).forEach { direction ->
            val placement = MineWorkingPlacement(entrance, direction, "top")
            MineIncidentType.values()
                .filter { it in setOf(
                    MineIncidentType.TUNNEL_DRIVE,
                    MineIncidentType.RAIL_EXTENSION,
                    MineIncidentType.TRACK_DAMAGE,
                    MineIncidentType.ORE_WORKSHOP,
                ) }
                .forEach { type ->
                    val plan = MineWorkingLayout.plan(type, placement)
                    MineWorkingLayout.validate(plan).shouldBeEmpty()
                    (plan.blocks.size <= MineWorkingPlanner.MAX_BLOCKS) shouldBe true
                    plan.entrance shouldBe entrance
                    plan.inside(placement.position(0, 1, 0)) shouldBe true
                    plan.blocks.keys.map { it.world }.toSet() shouldBe setOf(entrance.world)
                    when (type) {
                        MineIncidentType.TUNNEL_DRIVE -> {
                            plan.excavation.size shouldBe 90
                            plan.supports.size shouldBe 3
                            plan.stations shouldBe emptyMap()
                        }
                        MineIncidentType.RAIL_EXTENSION -> {
                            plan.rails.size shouldBe 12
                            plan.rubble.size shouldBe 3
                            plan.cartRoute.all { it.y == entrance.y + 1 } shouldBe true
                        }
                        MineIncidentType.TRACK_DAMAGE -> {
                            plan.rails.size shouldBe 3
                            plan.rubble.size shouldBe 3
                            plan.cartRoute.size shouldBe 12
                        }
                        MineIncidentType.ORE_WORKSHOP -> {
                            plan.stations.keys shouldBe setOf("ore", "crusher", "furnace", "output", "shipping")
                            plan.stations.values.all { point ->
                                val side = when (direction) {
                                    0 -> point.x - entrance.x
                                    1 -> point.z - entrance.z
                                    2 -> entrance.x - point.x
                                    else -> entrance.z - point.z
                                }
                                kotlin.math.abs(side) == 1
                            } shouldBe true
                            plan.walkable.none { it in plan.stations.values } shouldBe true
                        }
                        else -> Unit
                    }
                }
        }
    }

    test("tunnel cave keeps a narrow entry, long side branch and geological palette") {
        val placement = MineWorkingPlacement(
            WorksitePosition("rc_atelier_compact_mine", 72, 110, 27),
            direction = 3,
            floorId = "top",
            layoutSeed = 42L,
        )
        val plan = MineWorkingLayout.plan(MineIncidentType.TUNNEL_DRIVE, placement)

        MineWorkingLayout.validate(plan).shouldBeEmpty()
        plan.excavation.size shouldBe 90
        plan.supports.size shouldBe 3
        plan.blocks.keys.maxOf { placementForward(placement, it) } shouldBe 14
        // The last branch shoulder is three cells off the forward axis and
        // remains inside the real compact-mine corridor for this direction.
        plan.inside(placement.position(-3, 1, 14)) shouldBe true
        plan.blocks.values.any { it == "minecraft:tuff" } shouldBe true
        plan.blocks.values.any { it == "minecraft:andesite" } shouldBe true
        plan.blocks.values.any { it == "minecraft:deepslate" } shouldBe true
        plan.blocks.values.filter { it == "minecraft:iron_chain[axis=y,waterlogged=false]" }.isNotEmpty() shouldBe true
        plan.walkable
            .filter { it.y in placement.entrance.y + 1..placement.entrance.y + 3 }
            .filter { it !in plan.excavation }
            .all { plan.blocks[it] == "minecraft:air" } shouldBe true
    }

    test("support frames follow the noisy roof and keep a usable action anchor") {
        val placement = MineWorkingPlacement(
            WorksitePosition("rc_atelier_compact_mine", 72, 110, 27),
            direction = 3,
            floorId = "top",
            layoutSeed = 42L,
        )
        val plan = MineWorkingLayout.plan(MineIncidentType.TUNNEL_DRIVE, placement)

        plan.supportFrames.forEachIndexed { index, frame ->
            val beam = frame.filterValues { it.contains("axis=z") }
            val beamHeights = beam.keys.map { it.y }.toSet()
            beamHeights.size shouldBe 1
            val beamPositions = beam.keys.sortedBy { it.z }
            beamPositions.zipWithNext().forEach { (left, right) ->
                (kotlin.math.abs(left.z - right.z) + kotlin.math.abs(left.x - right.x)) shouldBe 1
            }
            val anchor = plan.supports[index]
            frame[anchor] shouldBe "minecraft:spruce_log[axis=y]"
            (anchor.y in placement.entrance.y + 2..placement.entrance.y + 3) shouldBe true
            (anchor !in plan.walkable) shouldBe true
        }
    }

    test("planner accepts air only at the entrance band and geological rock beyond it") {
        val plan = MineWorkingLayout.plan(
            MineIncidentType.TUNNEL_DRIVE,
            MineWorkingPlacement(entrance, 0, "top"),
        )
        val snapshot = buildMap {
            plan.shell.forEach { put(it, Material.STONE) }
            plan.fixtures.forEach { put(it, Material.STONE) }
            plan.walkable.forEach { position ->
                put(position, if (position.z - entrance.z <= 1) Material.AIR else Material.STONE)
            }
            plan.excavation.forEach { put(it, Material.STONE) }
            put(plan.placement.position(0, 0, -1), Material.STONE)
            (1..3).forEach { up -> put(plan.placement.position(0, up, -1), Material.AIR) }
        }
        MineWorkingPlanner.validateSnapshot(plan, snapshot) shouldBe null

        val blockedEntry = snapshot.toMutableMap()
        blockedEntry[plan.walkable.first { it.z - entrance.z <= 1 }] = Material.CHEST
        MineWorkingPlanner.validateSnapshot(plan, blockedEntry) shouldBe "entry_not_clear"

        val nonGeologicalShell = snapshot.toMutableMap()
        nonGeologicalShell[plan.shell.first()] = Material.BEDROCK
        MineWorkingPlanner.validateSnapshot(plan, nonGeologicalShell) shouldBe "shell_not_geological"
    }

    test("track damage projects rails at floor plus one and leaves three repair gaps") {
        val plan = MineWorkingLayout.plan(
            MineIncidentType.TRACK_DAMAGE,
            MineWorkingPlacement(entrance, 1, "middle"),
        )
        plan.rails.all { it.y == entrance.y + 1 } shouldBe true
        plan.cartRoute.size shouldBe 12
        plan.rubble.all { it.y == entrance.y + 1 } shouldBe true
        plan.rubble.all { plan.blocks[it] == "minecraft:cobblestone" } shouldBe true
        plan.rails.all { it in plan.rubble } shouldBe true
        plan.cartRoute.filter { it !in plan.rubble }.all { plan.blocks[it]?.startsWith("minecraft:rail[") == true } shouldBe true
    }

    test("track placement validates the original volume under every future rail") {
        val placement = MineWorkingPlacement(entrance, 0, "top")
        val plan = MineWorkingLayout.plan(MineIncidentType.RAIL_EXTENSION, placement)
        val snapshot = plan.blocks.keys.associateWith { Material.STONE }.toMutableMap()
        (plan.walkable + plan.cartRoute).filter { it.z - entrance.z <= 1 }.forEach { snapshot[it] = Material.AIR }
        snapshot[placement.position(0, 0, -1)] = Material.STONE
        (1..3).forEach { snapshot[placement.position(0, it, -1)] = Material.AIR }
        MineWorkingPlanner.validateSnapshot(plan, snapshot) shouldBe null

        val deepRail = plan.cartRoute.last()
        snapshot[deepRail] = Material.DIAMOND_BLOCK
        MineWorkingPlanner.validateSnapshot(plan, snapshot) shouldBe "track_volume_not_geological"
        snapshot[deepRail] = Material.STONE
        snapshot[plan.cartRoute.first()] = Material.STONE
        MineWorkingPlanner.validateSnapshot(plan, snapshot) shouldBe "track_entry_not_clear"
    }
})

private fun placementForward(placement: MineWorkingPlacement, position: WorksitePosition): Int = when (placement.direction) {
    0 -> position.z - placement.entrance.z
    1 -> placement.entrance.x - position.x
    2 -> placement.entrance.z - position.z
    else -> position.x - placement.entrance.x
}
