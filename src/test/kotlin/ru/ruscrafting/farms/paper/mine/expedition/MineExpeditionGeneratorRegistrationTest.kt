package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.ArcFarmsPlugin

class MineExpeditionGeneratorRegistrationTest : FunSpec({
    test("world managers can resolve the owned generator before plugin enable") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val plugin = paper.server.pluginManager.loadPlugin(ArcFarmsPlugin::class.java) as ArcFarmsPlugin
            val worldsBefore = paper.server.worlds.toList()
            plugin.isEnabled shouldBe false
            plugin.getDefaultWorldGenerator(MineExpeditionWorldGenerator.WORLD_NAME, "")
                .shouldBeInstanceOf<MineExpeditionWorldGenerator>()
            plugin.getDefaultWorldGenerator("world", null).shouldBeNull()
            plugin.getDefaultWorldGenerator("${MineExpeditionWorldGenerator.WORLD_NAME}_copy", "").shouldBeNull()
            plugin.isEnabled shouldBe false
            paper.server.worlds shouldBe worldsBefore
        } finally {
            paper.close()
        }
    }
})
