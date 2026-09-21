package ru.ruscrafting.farms.paper.mine.incident.rescue

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import ru.ruscrafting.farms.paper.mine.immediateMinePort
import java.util.logging.Logger

class MineLostMinerCavePreparationTest : FunSpec({
    test("background preparation rejects callbacks retired by reload") {
        val tasks=immediateMinePort()
        val jobs=mutableListOf<()->Unit>()
        every { tasks.runAsync(any(),any()) } answers { jobs+=secondArg<()->Unit>();true }
        val owner=MineLostMinerCavePreparation(tasks,Logger.getAnonymousLogger())
        owner.prepare("mine","1",73) shouldBe null
        owner.clear()
        owner.prepare("mine","2",74) shouldBe null
        jobs[0]()
        owner.prepare("mine","2",74) shouldBe null
        jobs[1]()
        owner.prepare("mine","2",74)!!.seed shouldBe 74
        jobs.size shouldBe 2
    }
})
