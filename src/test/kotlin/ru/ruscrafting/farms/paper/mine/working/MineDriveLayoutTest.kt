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
            (plan.blocks.size <= 12000) shouldBe true
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

    test("headings wrap without an inward angle clamp") {
        for (heading in -1080..1080 step 9) {
            val actual = MineDriveMotion.heading(heading.toFloat())
            (actual >= 0f && actual < 360f) shouldBe true
            (kotlin.math.abs(kotlin.math.sin(Math.toRadians((actual-heading).toDouble()))) < .000001) shouldBe true
        }
        MineDriveMotion.heading(180f) shouldBe 180f
        MineDriveMotion.heading(270f) shouldBe 270f
    }

    test("full chassis can turn sideways around both ribs and return through the same area") {
        fun fits(s: Double, f: Double, yaw: Float) = MineDriveMotion.footprint(s,f,yaw).all { (x,z) ->
            MineDriveLayout.insideBoundary(x,z) && !MineDriveLayout.bedrock(x,z)
        }
        for (yaw in 0..360 step 2) fits(0.0,20.0,yaw.toFloat()) shouldBe true
        // Sideways traversals previously forbidden by the +/-65 degree heading clamp.
        for (x in 0..45) fits(x/10.0,7.0,270f) shouldBe true
        for (f in 70..190) fits(4.5,f/10.0,0f) shouldBe true
        for (x in -45..45) fits(x/10.0,20.0,90f) shouldBe true
        for (f in 200..330) fits(-4.5,f/10.0,0f) shouldBe true
        fits(0.0,12.0,0f) shouldBe false
        for (yaw in 0..359 step 5) {
            fits(16.0,20.0,yaw.toFloat()) shouldBe false
            fits(0.0,44.0,yaw.toFloat()) shouldBe false
        }
    }

    test("diamond discovery has a vaulted irregular shell and exposed glowing ore targets") {
        for(seed in 1L..12L) {
            val p=MineWorkingPlacement(WorksitePosition("world",0,64,0),0,"top",seed)
            val plan=MineDriveLayout.plan(p)
            val ore=MineDriveLayout.goalOres(plan)
            (ore.size >= 12) shouldBe true
            val tops=(-14..14).map { s -> (1..9).lastOrNull { y -> plan.blocks[p.position(s,y,40)]=="minecraft:air" } ?: 0 }
            (tops.distinct().size >= 3) shouldBe true
            plan.blocks[p.position(0,1,41)] shouldBe "minecraft:air"
            (0..9).all { up -> (-8..8).all { side -> plan.blocks[p.position(side,up,44)] != "minecraft:air" } } shouldBe true
        }
    }

    test("wide workings preserve legacy cell coordinates and accept broad chamber arrivals") {
        val p=MineWorkingPlacement(WorksitePosition("world",0,64,0),0,"top",19L)
        val old=p.copy(geometryVersion=6)
        val newPlan=MineDriveLayout.plan(p);val oldPlan=MineDriveLayout.plan(old)
        (newPlan.blocks.size>oldPlan.blocks.size*1.8) shouldBe true
        for(s in -7..7) for(f in 1..43) {
            val legacy=f*17+s+8
            MineDriveLayout.position(old,legacy) shouldBe old.position(s,1,f)
            MineDriveLayout.position(p,MineDriveLayout.id(s,f)) shouldBe p.position(s,1,f)
        }
        val legacyCells=MineDriveMotion.excavationCells(old)
        legacyCells.all { it<765 } shouldBe true
        MineDriveMotion.excavationCells(p).all { it<1485 } shouldBe true
        ru.ruscrafting.farms.domain.MineDriveProgress(prepared=legacyCells).validate(6)
        ru.ruscrafting.farms.domain.MineDriveProgress(prepared=MineDriveMotion.excavationCells(p)).validate(7)
        for(s in listOf(-8.0,0.0,8.0)) MineDriveLayout.reached(s,39.0,7) shouldBe true
        MineDriveLayout.reached(0.0,32.0,7) shouldBe false
        MineDriveLayout.reached(14.0,39.0,7) shouldBe false
        newPlan.blocks[MineDriveLayout.returnPoint(p)] shouldBe "minecraft:air"
        (newPlan.blocks.values.count { it.startsWith("minecraft:light[level=13") }>=4) shouldBe true
    }

    test("old saved drives retain their exact layer targets") {
        val plan = MineWorkingLayout.plan(MineIncidentType.TUNNEL_DRIVE,
            MineWorkingPlacement(WorksitePosition("world",0,64,0),0,"top",geometryVersion=4))
        plan.excavation.size shouldBe 90
        plan.supports.size shouldBe 3
        MineDriveLayout.CONTRIBUTION_BUDGET shouldBe plan.excavation.size + plan.supports.size
    }
})
