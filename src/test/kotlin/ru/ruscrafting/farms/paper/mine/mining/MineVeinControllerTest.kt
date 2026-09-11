package ru.ruscrafting.farms.paper.mine.mining

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

class MineVeinControllerTest : FunSpec({
    test("veins form connected bounded patches across decorative wall materials") {
        listOf(Material.SMOOTH_SANDSTONE, Material.TUFF, Material.POLISHED_BASALT,
            Material.ORANGE_TERRACOTTA, Material.GRAY_CONCRETE, Material.DEEPSLATE_BRICKS,
            Material.MOSSY_STONE_BRICKS, Material.PACKED_MUD, Material.MUD_BRICKS,
            Material.TUFF_BRICKS, Material.BONE_BLOCK, Material.MOSS_BLOCK,
            Material.ROOTED_DIRT).forEach { MineVeinController.host(it) shouldBe true }
        listOf(Material.CHEST, Material.BARREL, Material.RAIL, Material.AIR, Material.IRON_ORE,
            Material.OAK_PLANKS).forEach { MineVeinController.host(it) shouldBe false }
        val wall = (0..4).flatMap { x -> (0..4).map { y -> WorksitePosition("world", x, y, 0) } }.toSet()
        val result = MineVeinController.connected(wall.first(), wall, 16)
        result.size shouldBe 16
        result.drop(1).forEachIndexed { i, p ->
            result.take(i + 1).any { q -> kotlin.math.abs(p.x-q.x)+kotlin.math.abs(p.y-q.y)+kotlin.math.abs(p.z-q.z)==1 } shouldBe true
        }
        MineVeinController.connected(wall.first(), wall, 3).size shouldBe 3
    }
})
