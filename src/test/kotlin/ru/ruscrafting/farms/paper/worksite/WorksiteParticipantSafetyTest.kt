package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player

class WorksiteParticipantSafetyTest : FunSpec({
    test("one failing owner cannot prevent other release handlers") {
        val player = mockk<Player>()
        val serviceItems = mockk<WorksiteServiceItemController>()
        every { serviceItems.cleanupPlayer(player, WorksitePlayerReleaseReason.RELOAD) } returns 2
        val calls = mutableListOf<String>()
        val broken = WorksiteParticipantOwner { _, _ ->
            calls += "broken"
            error("boom")
        }
        val healthy = WorksiteParticipantOwner { _, _ -> calls += "healthy" }
        val safety = WorksiteParticipantSafety(serviceItems, listOf(broken, healthy))

        val report = safety.release(player, WorksitePlayerReleaseReason.RELOAD)

        calls shouldContainExactly listOf("broken", "healthy")
        report.removedItems shouldBe 2
        report.ownerFailures.size shouldBe 1
        report.ownerFailures.single().message shouldBe "boom"
    }
})
