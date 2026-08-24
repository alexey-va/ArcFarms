package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class ArcFarmsEntityLifecycleContractTest : FunSpec({
    test("reconstructible activity entities are never saved into world chunks") {
        val sourceRoot = Path.of(requireNotNull(System.getProperty("arcfarms.repositoryRoot")))
            .resolve("ArcFarms/src/main/kotlin")
        val offenders = Files.walk(sourceRoot).use { paths ->
            paths.filter { path -> path.toString().endsWith(".kt") }
                .filter { path -> "isPersistent = true" in Files.readString(path) }
                .map(sourceRoot::relativize)
                .toList()
        }

        offenders shouldBe emptyList()
    }
})
