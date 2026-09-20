package ru.ruscrafting.farms.paper.mine.working

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

class MineDriveLayoutTest : FunSpec({
    test("three-wide driven tunnel can bypass bedrock and reach the goal in every orientation") {
        for (direction in 0..3) {
            val placement = MineWorkingPlacement(WorksitePosition("world", 50, 80, 50), direction, "middle", 72L)
            val plan = MineWorkingLayout.plan(MineIncidentType.TUNNEL_DRIVE, placement)
            MineWorkingLayout.validate(plan).shouldBeEmpty()
            (plan.blocks.size <= 8000) shouldBe true
            val start = 0 to 2
            val seen = linkedSetOf(start)
            val queue = ArrayDeque<Pair<Int, Int>>().apply { add(start) }
            fun fits(s: Int, f: Int) = (-1..1).all { ds -> (-1..1).all { df -> MineDriveLayout.driveable(s + ds, f + df) } }
            while (queue.isNotEmpty()) {
                val (s,f) = queue.removeFirst()
                for (next in listOf(s-1 to f,s+1 to f,s to f-1,s to f+1)) {
                    if (fits(next.first,next.second) && seen.add(next)) queue.add(next)
                }
            }
            (0 to 41 in seen) shouldBe true
            seen.none { (s,f) -> f in 12..14 && s <= 2 } shouldBe true
            seen.none { (s,f) -> f in 26..28 && s >= -2 } shouldBe true
            val p = placement.position(3,1,20)
            MineDriveLayout.local(placement,p.x+.5,p.z+.5) shouldBe (3.0 to 20.0)
        }
    }

    test("old saved drives retain their exact layer targets") {
        val plan = MineWorkingLayout.plan(MineIncidentType.TUNNEL_DRIVE,
            MineWorkingPlacement(WorksitePosition("world",0,64,0),0,"top",geometryVersion=4))
        plan.excavation.size shouldBe 90
        plan.supports.size shouldBe 3
        MineDriveLayout.CONTRIBUTION_BUDGET shouldBe plan.excavation.size + plan.supports.size
    }
})
