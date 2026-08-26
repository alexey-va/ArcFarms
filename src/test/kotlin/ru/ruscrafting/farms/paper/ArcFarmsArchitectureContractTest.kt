package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class ArcFarmsArchitectureContractTest : FunSpec({
    val repositoryRoot = Path.of(requireNotNull(System.getProperty("arcfarms.repositoryRoot")))
    val servicePath = repositoryRoot.resolve(
        "ArcFarms/src/main/kotlin/ru/ruscrafting/farms/paper/ArcFarmsService.kt",
    )

    test("service callbacks cannot bypass the reload-aware task supervisor") {
        val source = Files.readString(servicePath)

        source.contains("Tasks.scheduler") shouldBe false
        source.contains("private val taskSupervisor = FarmTaskSupervisor()") shouldBe true
        source.contains("taskSupervisor.runSync(lifecycle)") shouldBe true
        source.contains("taskSupervisor.runLater(lifecycle, 1L)") shouldBe true
    }

    test("monolithic service cannot silently grow while extraction is in progress") {
        val lines = Files.readAllLines(servicePath).size

        lines.shouldBeLessThanOrEqual(9_400)
    }
})
