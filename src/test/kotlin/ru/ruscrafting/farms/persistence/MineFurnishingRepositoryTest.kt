package ru.ruscrafting.farms.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import java.nio.file.Files
import java.util.concurrent.CompletionException

class MineFurnishingRepositoryTest : FunSpec({
    test("placement survives a restart with its stable site identity") {
        val root=Files.createTempDirectory("mine-furnishing")
        val first=MineFurnishingRepository(root)
        val state=MineFurnishingState(poses=mapOf("17/water_valve_0" to MineFurnishingPose(3,0,-2,345)))
        first.saveAsync(state).join();first.close()
        val reopened=MineFurnishingRepository(root)
        reopened.loadAsync().join() shouldBe state
        reopened.close()
    }
    test("invalid placement does not replace the last durable placement") {
        val root=Files.createTempDirectory("mine-furnishing-rejected")
        val store=MineFurnishingRepository(root)
        val valid=MineFurnishingState(poses=mapOf("1/pump" to MineFurnishingPose(1,0,2,15)))
        store.saveAsync(valid).join()
        shouldThrow<CompletionException> { store.saveAsync(valid.copy(poses=mapOf("1/pump" to MineFurnishingPose(500,0,0)))).join() }
        store.loadAsync().join() shouldBe valid
        store.close()
    }
})
