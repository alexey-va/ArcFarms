package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.paper.display.PacketDisplayAudienceGroup
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.concurrent.CompletableFuture

class LumberLifecycleTest : FunSpec({
    test("startup cleanup and reload create fresh live scene owners and close retired audiences once") {
        MockBukkitTestRuntime.open().use { paper ->
            val rules = LumbermillSettings()
            val world = paper.server.addSimpleWorld(rules.world)
            val groups = mutableListOf<PacketDisplayAudienceGroup>()
            val displays = mockk<PaperPacketDisplays>(relaxed = true) {
                every { createAudienceGroup(any()) } answers { mockk<PacketDisplayAudienceGroup>(relaxed = true).also(groups::add) }
            }
            val token = mockk<RuntimeTaskSupervisor.Token>()
            val tasks = mockk<WorksiteTaskPort>(relaxed = true) {
                every { lifecycleToken() } returns token
                every { runSync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
            }
            val storage = mockk<WorksiteStatePort>(relaxed = true) {
                every { persistAsync() } returns CompletableFuture.completedFuture(Unit)
            }
            val module = LumbermillVersionedModule(paper.createSimplePlugin("LumberLifecycle"), "spawn", displays, rules,
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), storage, tasks, mockk(relaxed = true), { 1000L })
            module.rebuild(rules, emptyMap())
            module.cleanup("service_start")
            module.activateLoadedState()
            module.updateVisuals() // Fails if the startup cleanup's closed SpatialControls were reused.
            val retained = module.states()
            module.beforeReload("reload")
            module.reconfigure(rules, retained)
            module.activateLoadedState()
            module.updateVisuals()
            module.states().keys shouldBe retained.keys
            world.entities shouldBe emptyList()
            module.cleanup("shutdown")
            module.cleanup("shutdown_again")
            groups.size shouldBe 4
            groups.forEach { group -> verify(exactly = 1) { group.close() } }
        }
    }
})
