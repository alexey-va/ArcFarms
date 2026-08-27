package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class ArcFarmsEntityLifecycleContractTest : FunSpec({
    test("reconstructible activity entities are never saved into world chunks") {
        val sourceRoot = Path.of(requireNotNull(System.getProperty("arcfarms.projectDir")))
            .resolve("src/main/kotlin")
        val offenders = Files.walk(sourceRoot).use { paths ->
            paths.filter { path -> path.toString().endsWith(".kt") }
                .filter { path -> "isPersistent = true" in Files.readString(path) }
                .map(sourceRoot::relativize)
                .toList()
        }

        offenders shouldBe emptyList()
    }

    test("farm contract cart is display-only and cannot reintroduce minecart collision ticks") {
        val repositoryRoot = Path.of(requireNotNull(System.getProperty("arcfarms.projectDir")))
        val source = Files.readString(
            repositoryRoot.resolve("src/main/kotlin/ru/ruscrafting/farms/paper/FarmContractSceneManager.kt"),
        )

        source.contains("org.bukkit.entity.Minecart") shouldBe false
        source.contains("Minecart::class.java") shouldBe false
        source.contains("FarmContractSceneRole.CART, FarmContractSceneRole.CART_LOAD") shouldBe true
        source.contains("ItemDisplay::class.java") shouldBe true
    }
})
