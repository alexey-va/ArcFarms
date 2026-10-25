package ru.ruscrafting.farms.paper.lumber.sawmill

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CedarSawmillPlaybackTest : FunSpec({
    test("each press completes one cut; running presses are ignored; new stock waits for exhaustion") {
        val playback = CedarSawmillPlayback()
        repeat(30) { playback.tick() }
        playback.elapsedTicks shouldBe 0
        playback.start() shouldBe true
        repeat(173) { playback.tick() }
        repeat(20) { playback.start() shouldBe false }
        playback.elapsedTicks shouldBe 173
        playback.cyclesStarted shouldBe 1
        repeat(327) { playback.tick() }
        playback.phase shouldBe CedarSawmillPlayback.Phase.READY
        playback.passIndex shouldBe 0
        repeat(100) { playback.tick() }
        playback.elapsedTicks shouldBe 500
        playback.start() shouldBe true
        playback.passIndex shouldBe 1
        playback.elapsedTicks shouldBe 0
        repeat(500) { playback.tick() }
        playback.phase shouldBe CedarSawmillPlayback.Phase.EXHAUSTED
        playback.cyclesStarted shouldBe 2
        repeat(100) { playback.tick() }
        playback.elapsedTicks shouldBe 500
        playback.start() shouldBe true
        playback.passIndex shouldBe 0
        playback.cyclesStarted shouldBe 3
    }

    test("reload clears only the ephemeral demonstration") {
        val playback = CedarSawmillPlayback()
        playback.start()
        repeat(200) { playback.tick() }
        playback.reset()
        repeat(200) { playback.tick() }
        playback.phase shouldBe CedarSawmillPlayback.Phase.IDLE
        playback.elapsedTicks shouldBe 0
        playback.passIndex shouldBe 0
        playback.cyclesStarted shouldBe 0
    }
})
