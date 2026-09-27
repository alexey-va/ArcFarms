package ru.ruscrafting.farms.paper.farm.incident.action

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.fixtures.RecordingFarmRaidDebrisVisuals
import java.util.Random
import kotlin.math.hypot

class FarmRivalRaidBlastDebrisMockBukkitTest : FunSpec({
    test("packet debris stays low and local, expires, and creates no world entities") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("sp11")
            val center = world.getBlockAt(8, 65, 8).location.toCenterLocation()
            val visuals = RecordingFarmRaidDebrisVisuals()
            val debris = ru.ruscrafting.farms.paper.farm.presentation.FarmBlastDebris(visuals, Random(17))
            val entitiesBefore = world.entities.size
            debris.spawn("farm", center, emptyList(), 24, 34)
            visuals.fragments shouldHaveSize 24
            visuals.fragments.all { it.block.material == Material.DIRT } shouldBe true
            repeat(34) { debris.tick("farm") }
            visuals.fragments.all { it.removed } shouldBe true
            val positions = visuals.fragments.flatMap { it.positions }
            (positions.maxOf { it.y - center.y } < 2.8) shouldBe true
            (positions.maxOf { hypot(it.x - center.x, it.z - center.z) } < 5.5) shouldBe true
            world.entities.size shouldBe entitiesBefore
            world.getBlockAt(8, 65, 8).type shouldBe Material.AIR
        }
    }

    test("burst budget, expiry and cleanup are scoped and idempotent") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("sp11")
            val center = world.getBlockAt(8, 65, 8).location.toCenterLocation()
            val visuals = RecordingFarmRaidDebrisVisuals()
            val debris = ru.ruscrafting.farms.paper.farm.presentation.FarmBlastDebris(visuals, Random(73))
            repeat(12) { debris.spawn("farm", center, emptyList(), 24, 34) }
            visuals.fragments.count { !it.removed } shouldBe 192
            debris.spawn("other", center, emptyList(), 24, 5)
            repeat(5) { debris.tick("other") }
            visuals.fragments.takeLast(24).all { it.removed } shouldBe true
            visuals.fragments.count { !it.removed } shouldBe 192
            debris.clear("farm")
            debris.clear("farm")
            visuals.fragments.all { it.removed } shouldBe true
            debris.spawn("farm", center, emptyList(), 24, 34)
            debris.cleanup()
            visuals.fragments.all { it.removed } shouldBe true
            visuals.cleanups shouldBe 1
            val count = visuals.fragments.size
            debris.spawn("farm", center, emptyList(), 0, 34)
            visuals.fragments.size shouldBe count
        }
    }
})
