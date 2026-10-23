package ru.ruscrafting.farms.paper.mine.floorwork

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Interaction
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.MineFloorActivity
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineTransitionCoordinator
import ru.ruscrafting.farms.paper.mine.immediateMinePort
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.mineV2Settings

class MineFloorActivitiesMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("winch route resumes its raised cargo after cleanup and commits unloading exactly once") {
        val scene = FloorFixture(paper, MineIncidentType.FREIGHT_WINCH)
        scene.start()
        scene.click("hook", 2.2, 1.2)
        scene.progress() shouldBe 1
        scene.click("rope", MineFloorActivity.PULL_X, MineFloorActivity.PULL_START_Z)
        repeat(7) { step ->
            scene.move(MineFloorActivity.PULL_X, MineFloorActivity.PULL_START_Z + (step + 1) * .4)
            scene.update(100L)
        }
        scene.progress() shouldBe 2
        val oldControl = scene.control("swing")
        scene.controller.cleanup(scene.runtime)
        oldControl.isValid shouldBe false
        scene.progress() shouldBe 2
        scene.recreate()
        scene.click("swing", -.65, 1.6)
        scene.update(1_900L)
        scene.progress() shouldBe 2
        scene.update(100L)
        scene.progress() shouldBe 3
        scene.click("unload", 0.0, 3.4)
        scene.runtime.state.phase shouldBe MinePhase.MINING
        scene.runtime.state.contributors[scene.player.uniqueId] shouldBe 4
        scene.click("unload", 0.0, 3.4)
        scene.runtime.state.contributors[scene.player.uniqueId] shouldBe 4
    }

    test("pump releases a carried pipe then resumes remaining strokes after full owner cleanup") {
        val scene = FloorFixture(paper, MineIncidentType.DRAINAGE_PUMP)
        scene.start()
        scene.click("pipe", -2.2, 2.0)
        scene.progress() shouldBe 0
        scene.controller.releasePlayer(scene.player)
        scene.update(100L)
        scene.control("pipe").interactionWidth shouldBe .9f
        scene.click("pipe", -2.2, 2.0)
        scene.click("socket", 1.2, 1.6)
        scene.progress() shouldBe 1
        scene.click("handle", 0.0, 2.0)
        scene.stroke()
        scene.progress() shouldBe 2

        val foreign = scene.world.spawn(Location(scene.world, 1.0, 64.0, 1.0), Interaction::class.java)
        val oldControl = scene.control("handle")
        scene.controller.cleanupAll()
        oldControl.isValid shouldBe false
        foreign.isValid shouldBe true
        scene.progress() shouldBe 2
        scene.recreate()
        scene.click("handle", 0.0, 2.0)
        repeat(3) { scene.stroke() }
        scene.progress() shouldBe 5
        scene.click("valve", 2.0, 1.5)
        scene.update(1_900L)
        scene.progress() shouldBe 5
        scene.update(100L)
        scene.runtime.state.phase shouldBe MinePhase.MINING
        scene.runtime.state.contributors[scene.player.uniqueId] shouldBe 6
        scene.controller.cleanupAll()
        foreign.isValid shouldBe true
    }
})

/** Real hitboxes, input and shift coordinator; only the unrelated protocol transport is replaced. */
private class FloorFixture(paper: MockBukkitTestRuntime, val type: MineIncidentType) {
    val world = paper.server.addSimpleWorld("floor_test")
    private val plugin = paper.createSimplePlugin("MineFloorActivitiesTest")
    val player = paper.server.addPlayer("FloorMiner")
    private val anchor = Location(world, 8.5, 64.0, 8.5)
    val runtime = MineRuntime(
        mineV2Settings(), CuboidActivityRegion(world, "old_shafts", CuboidBounds(0, 60, 0, 20, 75, 20)), 0L,
        MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 1, mined = 7, orderId = "ore_run"),
    )
    private val port = immediateMinePort().also { every { it.allowInteraction(any(), any()) } returns true }
    private val incidents = MineIncidentCoordinator(MineTransitionCoordinator(port, port, port, null), port)
    var controller = newController()
    private var now = 1_000L

    init {
        plugin.config.set("ui.sounds", false)
        plugin.config.set("ui.particles", false)
        world.getChunkAt(0, 0).load()
        for (x in 4..13) for (z in 4..14) world.getBlockAt(x, 63, z).type = Material.STONE
        player.setOnGround(true)
        move(0.0, 2.0)
    }

    fun start() {
        controller.start(runtime, type, now) shouldBe true
        update(100L)
    }

    fun progress() = runtime.state.incident?.progress
    fun move(x: Double, z: Double) { player.teleport(anchor.clone().add(x, 0.0, z)) }
    fun update(elapsed: Long) {
        now += elapsed
        controller.update(runtime, listOf(player), now)
    }

    fun control(role: String): Interaction {
        val at = MineFloorActivityScene.at(anchor, MineFloorActivityGeometry.controls(type).getValue(role)).subtract(0.0, .45, 0.0)
        return world.entities.filterIsInstance<Interaction>().single { it.isValid && it.location.distanceSquared(at) < .001 }
    }

    fun click(role: String, x: Double, z: Double) {
        move(x, z)
        controller.onInteract(PlayerInteractEntityEvent(player, control(role), EquipmentSlot.HAND), listOf(runtime), now) shouldBe true
    }

    fun stroke() {
        player.isSneaking = true
        update(500L)
        player.isSneaking = false
        update(500L)
    }

    fun recreate() {
        controller = newController()
        update(100L)
    }

    private fun newController() = MineFloorActivities(plugin, { _, key ->
        anchor.takeIf { key == MineFloorActivities.point(type) }
    }, incidents, port, port).also { owner ->
        val renderer = mockk<PaperPacketDisplays>(relaxed = true)
        every { renderer.spawnBlock(any(), any()) } answers { mockk<PacketBlockDisplay>(relaxed = true) }
        MineFloorActivities::class.java.getDeclaredField("renderer").apply { isAccessible = true; set(owner, renderer) }
    }
}
