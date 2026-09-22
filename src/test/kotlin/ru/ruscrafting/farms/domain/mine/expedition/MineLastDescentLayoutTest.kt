package ru.ruscrafting.farms.domain.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Material

class MineLastDescentLayoutTest : FunSpec({
    val seeds = listOf(0L, 73L, 999L, -9817L)
    fun passable(block:String?) = block=="minecraft:air" || block?.startsWith("minecraft:light[")==true

    test("new abyss has a bounded solid shell and a reproducible continuous forty eight block drop") {
        seeds.forEach { seed ->
            val plan=MineLastDescentLayout.build(seed)
            plan shouldBe MineLastDescentLayout.build(seed)
            plan.routes.getValue("lift").map { it.y } shouldBe listOf(57,33,9)
            plan.exit shouldBe plan.spawn
            plan.blocks.keys.all(plan.bounds::contains) shouldBe true
            plan.blocks.filterKeys { p -> p.x in setOf(-33,33) || p.y in setOf(0,68) || p.z in setOf(-29,32) }
                .values.all { !passable(it) && !it.startsWith("minecraft:water") } shouldBe true
            for(y in 8..60) for(x in -3..3) for(z in -2..2)
                passable(plan.blocks[ExpeditionPoint(x,y,z)]) shouldBe true
        }
    }
    test("all walking lanes preserve three block width with support after structures and decoration") {
        seeds.forEach { seed ->
            val plan=MineLastDescentLayout.build(seed)
            plan.walkingRoutes.flatten().distinct().forEach { p ->
                for(dx in -1..1) for(dz in -1..1) {
                    val q=p.offset(dx=dx,dz=dz)
                    for(dy in 0..2) {
                        check(passable(plan.blocks[q.offset(dy=dy)])) { "Obstruction at ${q.offset(dy=dy)} seed=$seed: ${plan.blocks[q.offset(dy=dy)]}" }
                    }
                    check(!passable(plan.blocks[q.offset(dy=-1)])) { "Unsupported lane $q seed=$seed" }
                }
            }
        }
    }
    test("both walking cranks have a supported clear service ring and every dock meets the deck") {
        val plan=MineLastDescentLayout.build(73)
        listOf("counterweight_1","core_valve_1").forEach { id ->
            val p=plan.stations.getValue(id)
            for(dx in -3..3) for(dz in -3..3) {
                if(dx*dx+dz*dz !in 2..9) continue
                for(dy in 0..1) passable(plan.blocks[p.offset(dx,dy,dz)]) shouldBe true
                passable(plan.blocks[p.offset(dx,-1,dz)]) shouldBe false
            }
        }
        listOf(57,33,9).forEach { y ->
            for(x in -2..2) for(z in 3..5) {
                passable(plan.blocks[ExpeditionPoint(x,y,z)]) shouldBe true
                passable(plan.blocks[ExpeditionPoint(x,y-1,z)]) shouldBe false
            }
        }
    }
    test("lake cannot escape horizontally or drain through the bottom") {
        val plan=MineLastDescentLayout.build(73)
        plan.blocks.filter { (p,block) -> p.y==5 && block.startsWith("minecraft:water") }.keys.forEach { p ->
            passable(plan.blocks[p.offset(dy=-1)]) shouldBe false
            listOf(p.offset(dx=1),p.offset(dx=-1),p.offset(dz=1),p.offset(dz=-1)).forEach { bank ->
                passable(plan.blocks[bank]) shouldBe false
            }
        }
    }
    test("all final palette states use known materials and seeds affect the cave") {
        val plan=MineLastDescentLayout.build(73)
        plan.blocks shouldNotBe MineLastDescentLayout.build(74).blocks
        plan.blocks.values.map { it.substringAfter(':').substringBefore('[').uppercase() }.distinct().forEach {
            Material.matchMaterial(it) shouldNotBe null
        }
    }
})
