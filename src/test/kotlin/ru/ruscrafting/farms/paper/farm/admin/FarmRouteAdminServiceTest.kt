package ru.ruscrafting.farms.paper.farm.admin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.ruscrafting.farms.domain.FarmDeliveryRoute
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRouteState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.persistence.FarmRouteRepository
import java.nio.file.Files

class FarmRouteAdminServiceTest : FunSpec({
    test("named routes are random and avoid the previous route when alternatives exist") {
        val root = Files.createTempDirectory("arcfarms-named-route-test")
        val main = route(0.0)
        val orchard = route(20.0)
        FarmRouteRepository(root).use { repository ->
            repository.saveBlocking(
                FarmRouteState(
                    routes = mapOf(
                        "communal_farm" to main,
                        "communal_farm~orchard" to orchard,
                    ),
                ),
            )
            val service = FarmRouteAdminService(
                repository = repository,
                debug = ArcFarmsDebug({ false }) {},
                port = mockk<WorksiteRuntimePort>(relaxed = true),
                runtimes = { emptyList() },
            )

            service.names("communal_farm") shouldBe listOf("main", "orchard")
            service.select("communal_farm", java.util.Random(0))?.name shouldBe "orchard"
            service.select("communal_farm", java.util.Random(0), excludedName = "orchard")?.name shouldBe "main"
            service.select("communal_farm", java.util.Random(0), excludedName = "main")?.name shouldBe "orchard"
        }
    }
})

private fun route(offset: Double) = FarmDeliveryRoute(
    listOf(
        FarmPointPosition("sp11", offset, 64.0, 0.0),
        FarmPointPosition("sp11", offset + 2.0, 64.0, 0.0),
    ),
)
