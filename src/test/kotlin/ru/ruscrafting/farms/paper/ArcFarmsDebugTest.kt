package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ArcFarmsDebugTest : StringSpec({
    "debug trail is disabled by default and escapes multiline values" {
        val output = mutableListOf<String>()
        var enabled = false
        val debug = ArcFarmsDebug({ enabled }, output::add)

        debug.event("player break", "player" to "Alex", "text" to "hidden")
        output shouldBe emptyList()

        enabled = true
        debug.event("player break", "player" to "Alex", "text" to "line 1\nline \"2\"")
        output shouldBe listOf(
            "ARCFARMS_DEBUG event=player_break player=Alex text=\"line 1 line \\\"2\\\"\"",
        )
    }
})
