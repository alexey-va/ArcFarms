package ru.ruscrafting.farms.domain.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MineCompactExpeditionTest : FunSpec({
    test("nearby caves have complete enclosing shells and safe supported entry points") {
        MineExpeditionKind.entries.forEach { kind ->
            listOf(71L, 999L).forEach { seed ->
                val plan = MineExpeditionGenerator.plan(kind, seed)
                val min = plan.bounds.min; val max = plan.bounds.max
                (max.x - min.x <= 50 && max.z - min.z <= 50 && max.y <= 30) shouldBe true
                for (x in min.x..max.x) for (z in min.z..max.z) {
                    (plan.blocks[ExpeditionPoint(x, min.y, z)] != "minecraft:air") shouldBe true
                    (plan.blocks[ExpeditionPoint(x, max.y, z)] != null) shouldBe true
                }
                for (p in listOf(plan.spawn, plan.exit)) {
                    plan.blocks[p] shouldBe "minecraft:air"
                    plan.blocks[p.offset(dy = 1)] shouldBe "minecraft:air"
                    (plan.blocks[p.offset(dy = -1)] != "minecraft:air") shouldBe true
                }
                (plan.blocks.keys.all(plan.bounds::contains)) shouldBe true
            }
        }
    }
    test("crawler has clearance throughout both compact branches") {
        val p = MineExpeditionGenerator.plan(MineExpeditionKind.DRILLING_ARK, 121L)
        val b = MineExpeditionBuilder(p.kind, p.seed, p.bounds)
        p.routes.values.forEach { route -> route.zipWithNext().forEach { (from, to) ->
            b.line(from, to).forEach { center ->
                MineExpeditionMachines.blocks(MineExpeditionKind.DRILLING_ARK).forEach { (local, _) ->
                    val point = center.offset(local.x, local.y, local.z)
                    if (point.y >= center.y) {
                        val block = p.blocks[point]
                        (block == "minecraft:air" || block?.startsWith("minecraft:light[") == true) shouldBe true
                    }
                }
            }
        } }
    }
})
