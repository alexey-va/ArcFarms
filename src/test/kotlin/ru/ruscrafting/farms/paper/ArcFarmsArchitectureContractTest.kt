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
        source.contains("private val taskSupervisor = RuntimeTaskSupervisor()") shouldBe true
        source.contains("taskSupervisor.runSync(lifecycle)") shouldBe true
        source.contains("taskSupervisor.runLater(lifecycle, 1L)") shouldBe true
    }

    test("monolithic service cannot silently grow while extraction is in progress") {
        val lines = Files.readAllLines(servicePath).size

        lines.shouldBeLessThanOrEqual(8_400)
    }

    test("service does not reclaim lumber or mine state machines") {
        val source = Files.readString(servicePath)

        listOf(
            "LumberRuntime",
            "MineRuntime",
            "LumberShiftEngine",
            "MineShiftEngine",
            "mineReservations",
            "pendingPositions",
            "restoreMineBlocks",
        ).forEach { forbidden -> source.contains(forbidden) shouldBe false }
        source.contains("private val auxiliaryWorksites = WorksiteModuleRegistry") shouldBe true
        source.contains("private val runtimeValidator = ArcFarmsRuntimeValidator") shouldBe true
        source.contains("private val worksitePort = PaperWorksiteRuntimePort") shouldBe true
        source.contains("lumbermillController.onBreak") shouldBe false
        source.contains("lumbermillController.onInteract") shouldBe false
        source.contains("mineController.onBreak") shouldBe false
        source.contains("mineController.onInteract") shouldBe false
        source.contains("mineController.onMove") shouldBe false
    }
})
