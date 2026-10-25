package ru.ruscrafting.farms.paper.navigation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.config.NetworkSettings
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.network.ActivityNetworkGateway
import ru.ruscrafting.farms.network.TravelTicket
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.WorksiteModuleRegistry
import ru.ruscrafting.farms.paper.farm.point.FarmPointService
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LumberTravelPermissionTest : FunSpec({
    test("network fallback and administrator permission never replace explicit lumber beta admission") {
        val player = mockk<Player> {
            every { hasPermission(LumbermillSettings.BETA_PERMISSION) } returns false
            every { hasPermission("arcfarms.admin") } returns true
        }
        val config = mockk<ArcFarmsConfig> {
            every { network } returns NetworkSettings(true, setOf("spawn", "survival"), false, true, true, 30)
        }
        val modules = mockk<WorksiteModuleRegistry>()
        val service = ActivityTravelService({ config }, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), modules)
        service.canAccess(player, ActivityKind.LUMBER) shouldBe false
        verify { modules wasNot Called }
    }

    test("a claimed lumber ticket with revoked permission cannot resolve a destination or teleport") {
        val id = UUID.randomUUID()
        val player = mockk<Player>(relaxed = true) {
            every { uniqueId } returns id
            every { isOnline } returns true
            every { hasPermission(LumbermillSettings.BETA_PERMISSION) } returns false
        }
        val config = mockk<ArcFarmsConfig> {
            every { serverId } returns "spawn"
            every { network } returns NetworkSettings(true, setOf("spawn"), false, true, true, 30)
        }
        val token = mockk<RuntimeTaskSupervisor.Token>()
        val tasks = mockk<WorksiteTaskPort> {
            every { lifecycleToken() } returns token
            every { runLater(token, 1L, any()) } answers { thirdArg<() -> Unit>().invoke(); true }
        }
        val network = mockk<ActivityNetworkGateway> {
            every { claimTravelTicket(id, "spawn") } returns CompletableFuture.completedFuture(
                TravelTicket(ActivityKind.LUMBER, "spawn", 1L, 1000L))
        }
        val points = mockk<FarmPointService>()
        val access = mockk<WorksiteAccessPort> { every { isOperational() } returns true }
        val service = ActivityTravelService({ config }, mockk(), access, mockk(), tasks, network, mockk(), points, mockk(), mockk())
        service.claimJoin(player)
        verify { points wasNot Called }
        verify(exactly = 0) { player.teleportAsync(any<org.bukkit.Location>()) }
    }
})
