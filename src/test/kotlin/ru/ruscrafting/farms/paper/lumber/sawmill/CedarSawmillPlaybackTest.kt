package ru.ruscrafting.farms.paper.lumber.sawmill

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CedarSawmillPlaybackTest : FunSpec({
    test("one shared 25 second cycle pauses resumes finishes and replays without duplicating a cycle") {
        val playback = CedarSawmillPlayback()
        repeat(30) { playback.tick() }
        playback.elapsedTicks shouldBe 0
        playback.phase shouldBe CedarSawmillPlayback.Phase.IDLE

        playback.toggle()
        repeat(173) { playback.tick() }
        playback.elapsedSeconds shouldBe 8.65
        playback.toggle()
        repeat(100) { playback.tick() }
        playback.elapsedTicks shouldBe 173
        playback.phase shouldBe CedarSawmillPlayback.Phase.PAUSED

        playback.toggle()
        playback.cyclesStarted shouldBe 1
        repeat(326) { playback.tick() }
        playback.phase shouldBe CedarSawmillPlayback.Phase.RUNNING
        playback.tick()
        playback.phase shouldBe CedarSawmillPlayback.Phase.FINISHED
        playback.elapsedSeconds shouldBe 25.0
        repeat(40) { playback.tick() }
        playback.elapsedTicks shouldBe 500

        playback.toggle()
        playback.phase shouldBe CedarSawmillPlayback.Phase.RUNNING
        playback.elapsedTicks shouldBe 0
        playback.cyclesStarted shouldBe 2
        playback.tick()
        playback.elapsedTicks shouldBe 1
    }

    test("reload clears only the ephemeral playback and stops further clock advancement") {
        val playback = CedarSawmillPlayback()
        playback.toggle()
        repeat(200) { playback.tick() }
        playback.reset()
        repeat(200) { playback.tick() }

        playback.phase shouldBe CedarSawmillPlayback.Phase.IDLE
        playback.elapsedTicks shouldBe 0
        playback.cyclesStarted shouldBe 0
    }
})
