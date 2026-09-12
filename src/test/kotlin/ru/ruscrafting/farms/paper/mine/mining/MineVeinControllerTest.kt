package ru.ruscrafting.farms.paper.mine.mining

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.WorksiteTickBudget

class MineVeinControllerTest : FunSpec({
    test("veins use natural quarry stone and never replace built roads") {
        listOf(Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE,
            Material.DEEPSLATE, Material.TUFF, Material.CALCITE, Material.NETHERRACK,
            Material.BASALT, Material.SMOOTH_BASALT, Material.BLACKSTONE, Material.END_STONE,
            Material.DRIPSTONE_BLOCK, Material.COBBLED_DEEPSLATE).forEach {
            MineVeinController.host(it) shouldBe true
        }
        listOf(Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.DEEPSLATE_BRICKS,
            Material.POLISHED_BASALT, Material.SMOOTH_SANDSTONE, Material.GRAY_CONCRETE,
            Material.ORANGE_TERRACOTTA, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE,
            Material.DIRT, Material.SAND, Material.GRAVEL, Material.MUD,
            Material.CHEST, Material.BARREL, Material.RAIL, Material.AIR, Material.IRON_ORE,
            Material.OAK_PLANKS).forEach { MineVeinController.host(it) shouldBe false }
        val wall = (0..4).flatMap { x -> (0..4).map { y -> WorksitePosition("world", x, y, 0) } }.toSet()
        val result = MineVeinController.connected(wall.first(), wall, 16)
        result.size shouldBe 16
        result.drop(1).forEachIndexed { i, p ->
            result.take(i + 1).any { q -> kotlin.math.abs(p.x-q.x)+kotlin.math.abs(p.y-q.y)+kotlin.math.abs(p.z-q.z)==1 } shouldBe true
        }
        MineVeinController.connected(wall.first(), wall, 3).size shouldBe 3
    }

    test("surface survey resumes within the shared budget and caches target neighbors") {
        val first = WorksitePosition("world", 0, 64, 0)
        val second = WorksitePosition("world", 1, 64, 0)
        val positions = listOf(first, second)
        var reads = 0
        val survey = MineVeinSurfaceSurvey(
            positions = positions,
            isNeighborLoaded = { true },
            readType = { position ->
                reads++
                if (position.x == -1 || position.x == 2) Material.AIR else Material.STONE
            },
        )

        survey.advance(WorksiteTickBudget(maxOperations = 2)) shouldBe false
        survey.complete shouldBe false
        survey.snapshots shouldBe emptyMap()

        survey.advance(WorksiteTickBudget(maxOperations = 5)) shouldBe false
        survey.snapshots.size shouldBe 1
        survey.advance(WorksiteTickBudget(maxOperations = 1_024)) shouldBe true

        survey.complete shouldBe true
        survey.snapshots.size shouldBe positions.size
        survey.snapshots.getValue(first).exposed shouldBe true
        survey.snapshots.getValue(second).exposed shouldBe true
        // The adjacent target is read once as first's neighbor and reused as second's type.
        reads shouldBe 12
    }
})
