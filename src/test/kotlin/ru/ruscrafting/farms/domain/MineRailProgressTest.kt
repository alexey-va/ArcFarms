package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MineRailProgressTest : FunSpec({
    fun id(x:Int,z:Int)=z*33+x+16
    test("continuous route follows bends and rewinds reversed or looped sections") {
        var p=MineRailProgress()
        for(z in 2..12) p=MineRailProgression.follow(p,id(0,z))
        for(x in 1..5) p=MineRailProgression.follow(p,id(x,12))
        p.validate(1485)
        p.route.last() shouldBe id(5,12)
        p=MineRailProgression.follow(p,id(4,12))
        (id(5,12) in p.route) shouldBe false
        for(z in 13..15) p=MineRailProgression.follow(p,id(4,z))
        for(x in 3 downTo 0) p=MineRailProgression.follow(p,id(x,15))
        for(z in 14 downTo 10) p=MineRailProgression.follow(p,id(0,z))
        p.route shouldBe (0..10).map { id(0,it) }
        p.validate(1485)
        MineRailProgression.follow(p,id(10,30)).route shouldBe p.route
    }
    test("diagonal samples connect by cardinal rail edges and completed track has no gaps") {
        var p=MineRailProgress()
        for(n in 2..10) p=MineRailProgression.follow(p,id(n-2,n))
        p.validate(1485)
        val laid=MineRailProgression.laid(p,p.route.last())
        laid.all { val a=it%33-p.route.last()%33;val b=it/33-p.route.last()/33;a*a+b*b>9 } shouldBe true
        MineRailProgression.laid(p.copy(finished=true),p.route.last()) shouldBe p.route
    }
    test("maintenance is optional seeded and survives retry without rerolling or repeating") {
        val totals=mutableSetOf<Int>()
        val kinds=mutableSetOf<MineRailServiceKind>()
        for(seed in 1L..100L) {
            var p=MineRailProgress()
            MineRailProgression.due(p,seed,13.9) shouldBe null
            var count=0
            while(true) {
                val service=MineRailProgression.due(p,seed,40.0) ?: break
                p=p.copy(service=service)
                MineRailProgression.due(p,seed,2.0) shouldBe service
                p.validate(1485)
                kinds+=service.kind
                p=MineRailProgression.finishService(p)
                count++
                check(count<=2)
            }
            totals+=count
            MineRailProgression.due(p,seed,42.0) shouldBe null
        }
        totals shouldBe setOf(0,1,2)
        kinds shouldBe MineRailServiceKind.entries.toSet()
    }
})
