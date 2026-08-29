package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmStallWatchdogTest : FunSpec({
    test("watchdog reminds once and releases only after the full idle timeout") {
        val initial = FarmStallWatchdogState(lastProgressTick = 100)

        FarmStallWatchdog.observe(initial, 339, progressed = false, reminderTicks = 240, releaseTicks = 600) shouldBe
            FarmStallWatchdogResult(initial, FarmStallAction.NONE)

        val reminded = FarmStallWatchdog.observe(initial, 340, progressed = false, reminderTicks = 240, releaseTicks = 600)
        reminded.action shouldBe FarmStallAction.REMIND
        reminded.state.reminded shouldBe true

        FarmStallWatchdog.observe(reminded.state, 699, progressed = false, reminderTicks = 240, releaseTicks = 600).action shouldBe
            FarmStallAction.NONE
        FarmStallWatchdog.observe(reminded.state, 700, progressed = false, reminderTicks = 240, releaseTicks = 600).action shouldBe
            FarmStallAction.RELEASE
    }

    test("any meaningful route or movement progress resets both timers") {
        val reminded = FarmStallWatchdogState(lastProgressTick = 100, reminded = true)

        val progressed = FarmStallWatchdog.observe(
            reminded,
            tick = 500,
            progressed = true,
            reminderTicks = 400,
            releaseTicks = 900,
        )

        progressed shouldBe FarmStallWatchdogResult(
            FarmStallWatchdogState(lastProgressTick = 500, reminded = false),
            FarmStallAction.NONE,
        )
    }

    test("short bumps never cause an immediate rescue action") {
        val state = FarmStallWatchdogState(lastProgressTick = 0)

        FarmStallWatchdog.observe(state, 1, progressed = false, reminderTicks = 400, releaseTicks = 900).action shouldBe
            FarmStallAction.NONE
    }
})
