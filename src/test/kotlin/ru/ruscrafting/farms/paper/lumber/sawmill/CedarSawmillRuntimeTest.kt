package ru.ruscrafting.farms.paper.lumber.sawmill

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.inventory.ItemStack
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.lumber.LumberPhase
import ru.ruscrafting.farms.domain.lumber.LumberState
import ru.ruscrafting.farms.domain.lumber.LumberTiming
import ru.ruscrafting.farms.domain.lumber.LumberVisualView

class CedarSawmillRuntimeTest : FunSpec({
    test("empty and collected machine cannot invent stock; both loaded passes retain their exact canonical pose") {
        val stockIds = CedarSawmillGeometry.parts.filter { it.motion.startsWith("timber-") }.map { it.id }.toSet()
        fun frame(phase: LumberPhase, passes: Int = 0, running: Boolean = false, ticks: Int = 0) =
            CedarSawmillController.frame(LumberVisualView(LumberState(phase = phase, sawPasses = passes, sawRunning = running), 0, emptySet(), ticks, 0))
        for (phase in listOf(LumberPhase.IDLE, LumberPhase.HAULING, LumberPhase.LOADING_SAWMILL, LumberPhase.DELIVERING)) {
            frame(phase, 2).poses.any { it.visible && it.partId in stockIds } shouldBe false
        }
        frame(LumberPhase.SAWING).poses shouldBe CedarSawmillAnimation.sample(0.0, 0).poses
        frame(LumberPhase.SAWING, 1).poses shouldBe CedarSawmillAnimation.sample(25.0, 0).poses
        frame(LumberPhase.SAWING, 1, true, LumberTiming.SAW_PASS_TICKS / 2).poses shouldBe
            CedarSawmillAnimation.sample(CedarSawmillAnimation.DURATION_SECONDS / 2, 1).poses
        frame(LumberPhase.COLLECTING_BOARDS, 2).poses shouldBe CedarSawmillAnimation.sample(25.0, 1).poses
    }

    test("keyed machine keeps native model handles across empty loading both cuts and collection without native entities") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld(CedarSawmillController.WORLD_NAME)
            val recording = SawmillDisplayRecording()
            val scene = CedarSawmillDisplays(world, recording.displays)
            val audience = SceneAudience.Public(null)
            val empty = CedarSawmillController.frame(LumberVisualView(LumberState(), 0, emptySet(), 0, 0))
            scene.render(empty, false, audience)
            val ids = scene.entityIds()
            ids.size shouldBe CedarSawmillGeometry.parts.size
            scene.itemCount shouldBe 23
            recording.items.map { it.itemMeta.customModelData }.toSet() shouldBe setOf(271001, 271002, 271003, 271004, 271005)
            val handles = recording.handles.toList()
            for (frame in listOf(CedarSawmillAnimation.sample(0.0), CedarSawmillAnimation.sample(12.0),
                CedarSawmillAnimation.sample(25.0), CedarSawmillAnimation.sample(12.0, 1), CedarSawmillAnimation.sample(25.0, 1), empty)) {
                scene.render(frame, true, audience)
                scene.entityIds() shouldBe ids
                recording.handles shouldBe handles
            }
            world.entities shouldBe emptyList()
            scene.close(); scene.close()
            handles.forEach { verify(exactly = 1) { it.remove() } }
            verify(exactly = 0) { recording.displays.close() }
        }
    }
})

private class SawmillDisplayRecording {
    val displays = mockk<PaperPacketDisplays>(relaxed = true)
    val handles = mutableListOf<PacketDisplay>()
    val items = mutableListOf<ItemStack>()
    private var nextId = 1000
    init {
        every { displays.spawnBlock(any(), any()) } answers {
            val at = firstArg<Location>().clone()
            mockk<PacketBlockDisplay>(relaxed = true).also { prepare(it, at) }
        }
        every { displays.spawnItem(any(), any()) } answers {
            val at = firstArg<Location>().clone()
            items += secondArg<ItemStack>().clone()
            mockk<PacketItemDisplay>(relaxed = true).also { prepare(it, at) }
        }
    }
    private fun prepare(handle: PacketDisplay, at: Location) {
        every { handle.entityId } returns nextId++
        every { handle.isValid } returns true
        every { handle.location } answers { at.clone() }
        handles += handle
    }
}
