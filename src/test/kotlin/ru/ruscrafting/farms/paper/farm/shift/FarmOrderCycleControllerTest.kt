package ru.ruscrafting.farms.paper.farm.shift

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import java.util.concurrent.CompletableFuture

class FarmOrderCycleControllerTest : FunSpec({
    test("persists a pause mutation and exposes an immutable snapshot") {
        var saves = 0
        val controller = orderCycleController {
            saves++
            CompletableFuture.completedFuture(Unit)
        }

        controller.set("communal_farm", true).shouldBeTrue()
        controller.isPaused("communal_farm").shouldBeTrue()
        controller.snapshot().shouldContainExactlyInAnyOrder("communal_farm")
        saves shouldBe 1
    }

    test("rolls the mutation back when durable persistence fails") {
        val controller = orderCycleController {
            CompletableFuture.failedFuture(IllegalStateException("disk unavailable"))
        }

        controller.set("communal_farm", true).shouldBeTrue()
        controller.isPaused("communal_farm").shouldBeFalse()
    }

    test("retains only zones present after configuration reload") {
        val controller = orderCycleController { CompletableFuture.completedFuture(Unit) }
        controller.replace(listOf("one", "two", "removed"))

        controller.retain(setOf("one", "two"))

        controller.snapshot().shouldContainExactlyInAnyOrder("one", "two")
    }
})

private fun immediatePort(): WorksiteRuntimePort = mockk(relaxed = true) {
    every { lifecycleToken() } returns mockk<RuntimeTaskSupervisor.Token>()
    every { runSync(any(), any()) } answers {
        secondArg<() -> Unit>().invoke()
        true
    }
}

private fun orderCycleController(persist: () -> CompletableFuture<Unit>): FarmOrderCycleController {
    val port = immediatePort()
    return FarmOrderCycleController(port, port, persist)
}
