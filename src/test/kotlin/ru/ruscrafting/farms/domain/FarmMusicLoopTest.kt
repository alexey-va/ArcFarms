package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmMusicLoopTest : FunSpec({
    val player = UUID(0, 42)

    test("farm music starts once and does not duplicate before its replay boundary") {
        val loop = FarmMusicLoop()

        loop.sync(player, "arc:farm", 1_000, 5_000) shouldBe FarmMusicTransition(playSound = "arc:farm")
        loop.sync(player, "arc:farm", 2_000, 5_000) shouldBe FarmMusicTransition()
        loop.sync(player, "arc:farm", 5_999, 5_000) shouldBe FarmMusicTransition()
    }

    test("farm music is stopped before a scheduled replay or sound change") {
        val loop = FarmMusicLoop()
        loop.sync(player, "arc:first", 1_000, 5_000)

        loop.sync(player, "arc:first", 6_000, 5_000) shouldBe
            FarmMusicTransition(stopSound = "arc:first", playSound = "arc:first")
        loop.sync(player, "arc:second", 7_000, 5_000) shouldBe
            FarmMusicTransition(stopSound = "arc:first", playSound = "arc:second")
    }

    test("leaving the farm retires the active music session exactly once") {
        val loop = FarmMusicLoop()
        loop.sync(player, "arc:farm", 1_000, 5_000)

        loop.sync(player, null, 2_000, 5_000) shouldBe FarmMusicTransition(stopSound = "arc:farm")
        loop.sync(player, null, 3_000, 5_000) shouldBe FarmMusicTransition()
    }
})
