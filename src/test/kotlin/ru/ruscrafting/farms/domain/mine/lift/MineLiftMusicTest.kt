package ru.ruscrafting.farms.domain.mine.lift

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class MineLiftMusicTest : FreeSpec({
    "an occupied casino trip has a bounded quiet phrase that restarts for the next ride" {
        val beats = (0 until 80).mapNotNull(MineLiftMusic::at)
        beats.size shouldBe 16
        beats.count { it.bassPitch != null } shouldBe 4
        beats.all { it.melodyPitch in .5f..2f && (it.bassPitch == null || it.bassPitch in .5f..2f) } shouldBe true
        MineLiftMusic.at(80) shouldBe MineLiftMusic.at(0)
        MineLiftMusic.at(1) shouldBe null
    }
})
