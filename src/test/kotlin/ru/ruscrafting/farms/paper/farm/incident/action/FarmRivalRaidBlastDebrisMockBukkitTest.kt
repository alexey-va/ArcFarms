package ru.ruscrafting.farms.paper.farm.incident.action

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime

class FarmRivalRaidBlastDebrisMockBukkitTest : FunSpec({
    test("every grenade explosion throws a full debris burst even without a detected crop plot") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("sp11")
            world.getChunkAt(0, 0).load()
            val center = world.getBlockAt(8, 65, 8).location.toCenterLocation()

            val debris = FarmRivalRaidBlastDebris.spawn(center, emptyList(), 48)

            debris shouldHaveSize 48
            debris.all { it.blockData.material == Material.DIRT && it.velocity.y >= 0.72 } shouldBe true
        } finally {
            paper.close()
        }
    }
})
