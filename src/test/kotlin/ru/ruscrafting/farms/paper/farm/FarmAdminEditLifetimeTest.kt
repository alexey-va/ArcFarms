package ru.ruscrafting.farms.paper.farm

import io.kotest.core.spec.style.FunSpec
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import ru.ruscrafting.farms.paper.farm.admin.FarmWorldAdminService
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason

class FarmAdminEditLifetimeTest : FunSpec({
    for (reason in WorksitePlayerReleaseReason.entries) {
        test("edit session lifetime for $reason") {
            val admin = mockk<FarmWorldAdminService>(relaxed = true)
            val router = FarmEventRouter(
                locale = mockk(relaxed = true),
                debug = mockk(relaxed = true),
                access = mockk(relaxed = true),
                audience = mockk(relaxed = true),
                state = mockk(relaxed = true),
                runtimes = { emptyList() },
                worldAdmin = admin,
                ledger = mockk(relaxed = true),
                registry = mockk(relaxed = true),
                fixedCrops = mockk(relaxed = true),
                field = mockk(relaxed = true),
                care = mockk(relaxed = true),
                drought = mockk(relaxed = true),
                pests = mockk(relaxed = true),
                birds = mockk(relaxed = true),
                foodDelivery = mockk(relaxed = true),
                routeAdmin = mockk(relaxed = true),
                perks = mockk(relaxed = true),
                special = mockk(relaxed = true),
                processing = mockk(relaxed = true),
                barnFire = mockk(relaxed = true),
                frost = mockk(relaxed = true),
                greenhouse = mockk(relaxed = true),
                actionIncidents = mockk(relaxed = true),
                delivery = mockk(relaxed = true),
                enterprise = mockk(relaxed = true),
                supplies = mockk(relaxed = true),
                scene = mockk(relaxed = true),
                harvest = mockk(relaxed = true),
                hud = mockk(relaxed = true),
                transitions = mockk(relaxed = true),
                shiftStartPending = { false },
                persistAsync = { java.util.concurrent.CompletableFuture.completedFuture(Unit) },
                clock = { 0L },
            )
            val player = mockk<Player>(relaxed = true)
            router.onQuit(player, reason)
            val transient = reason in setOf(WorksitePlayerReleaseReason.TELEPORT_OUT,
                WorksitePlayerReleaseReason.PORTAL_OUT, WorksitePlayerReleaseReason.ZONE_EXIT,
                WorksitePlayerReleaseReason.OBJECTIVE_REPLACED)
            verify(exactly = if (transient) 0 else 1) { admin.release(player) }
        }
    }
})
