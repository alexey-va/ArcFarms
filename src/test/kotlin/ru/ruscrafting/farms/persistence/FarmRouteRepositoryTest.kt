package ru.ruscrafting.farms.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.FarmDeliveryRoute
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRouteState
import java.nio.file.Files

class FarmRouteRepositoryTest : FunSpec({
    test("delivery route survives an atomic repository reopen") {
        val root = Files.createTempDirectory("arcfarms-route-test")
        val expected = FarmRouteState(
            routes = mapOf(
                "communal_farm" to FarmDeliveryRoute(
                    listOf(
                        FarmPointPosition("sp11", 200.5, 50.0, 450.5),
                        FarmPointPosition("sp11", 204.5, 50.0, 452.5),
                        FarmPointPosition("sp11", 208.5, 50.0, 454.5),
                    ),
                ),
                "communal_farm~orchard" to FarmDeliveryRoute(
                    listOf(
                        FarmPointPosition("sp11", 210.5, 50.0, 460.5),
                        FarmPointPosition("sp11", 214.5, 50.0, 462.5),
                    ),
                ),
            ),
        )

        FarmRouteRepository(root).use { it.saveBlocking(expected) }
        FarmRouteRepository(root).use { it.load() shouldBe expected }
    }

    test("deserialized route with an unsafe segment is rejected") {
        val root = Files.createTempDirectory("arcfarms-route-invalid-test")
        val data = root.resolve("data")
        Files.createDirectories(data)
        Files.writeString(
            data.resolve("farm-routes.json"),
            """
            {
              "schemaVersion": 1,
              "routes": {
                "communal_farm": {
                  "points": [
                    {"world":"sp11","x":0.0,"y":64.0,"z":0.0,"yaw":0.0,"pitch":0.0},
                    {"world":"sp11","x":20.0,"y":64.0,"z":0.0,"yaw":0.0,"pitch":0.0}
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> { FarmRouteRepository(root).use(FarmRouteRepository::load) }
    }
})
