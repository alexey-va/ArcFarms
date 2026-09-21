package ru.ruscrafting.farms.paper.mine.working

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.GameMode
import org.bukkit.Input
import org.bukkit.Material
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.immediateMinePort
import ru.ruscrafting.farms.paper.mine.mineV2Settings
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene
import java.util.concurrent.CompletableFuture

class MineDriveControllerMockBukkitTest : FunSpec({
    for (outcome in listOf("saved", "failed", "stale")) {
        test("drilling waits for durable state and handles $outcome completion") {
            val paper = MockBukkitTestRuntime.open()
            try {
                val fixture = driveFixture(paper)
                val before = fixture.runtime.state
                fixture.tick()
                fixture.controller.busy(fixture.runtime.settings.id) shouldBe true
                fixture.runtime.state.incident!!.working!!.drive!!.carved.isNotEmpty() shouldBe true
                verify(exactly=0) { fixture.world.project(any(), any(), any()) }
                fixture.cart.velocity shouldBe Vector()
                if (outcome == "stale") fixture.runtime.state = fixture.runtime.state.copy(sequence=99)
                if (outcome == "failed") fixture.saved.completeExceptionally(IllegalStateException("disk full"))
                else fixture.saved.complete(Unit)
                fixture.controller.busy(fixture.runtime.settings.id) shouldBe false
                verify(exactly=if(outcome=="saved") 1 else 0) { fixture.world.project(any(), any(), any()) }
                if (outcome == "failed") fixture.runtime.state shouldBe before
            } finally { paper.close() }
        }
    }
    test("bedrock stops the carrier while ordinary rock is removed to expose the obstacle") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val f = driveFixture(paper, startForward=10)
            f.tick()
            val carved = f.runtime.state.incident!!.working!!.drive!!.carved
            carved.any { MineDriveLayout.bedrock(MineDriveLayout.side(it), MineDriveLayout.forward(it)) } shouldBe false
            carved.isNotEmpty() shouldBe true
            f.controller.busy(f.runtime.settings.id) shouldBe true
            f.cart.velocity shouldBe Vector()
        } finally { paper.close() }
    }
    test("reaching the goal reports completion and releases the native passenger") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val f = driveFixture(paper, startForward=40)
            var reached = 0
            f.controller.tick(f.runtime,f.scene,1000,{true}) { _,_ -> reached++ }
            reached shouldBe 1
            verify(exactly=1) { f.rigs.release(f.player) }
            verify(exactly=0) { f.world.project(any(),any(),any()) }
        } finally { paper.close() }
    }
})

private class DriveFixture(val controller: MineDriveController, val runtime: MineRuntime,
    val scene: MineWorkingScene, val world: MineWorkingWorld, val rigs: MineDriveRig,
    val player: Player, val cart: Minecart, val saved: CompletableFuture<Unit>) {
    fun tick() = controller.tick(runtime,scene,1000,{true}) { _,_ -> error("goal not reached") }
}

private fun driveFixture(paper: MockBukkitTestRuntime, startForward: Int = 2): DriveFixture {
    val world = paper.server.addSimpleWorld("world")
    val plugin = paper.createSimplePlugin("DriveTest")
    for (x in -1..1) for (z in -1..3) world.getChunkAt(x,z).load()
    val p = MineWorkingPlacement(WorksitePosition(world.name,0,64,0),0,"test")
    val plan = MineDriveLayout.plan(p)
    plan.blocks.forEach { (pos,data) -> world.getBlockAt(pos.x,pos.y,pos.z).type = Material.valueOf(data.substringAfter(':').substringBefore('[').uppercase()) }
    val runtime = MineRuntime(mineV2Settings(), CuboidActivityRegion(world,"test",CuboidBounds(-20,50,-20,50,100,60)),5000,
        MineShiftState(engineVersion=2, phase=MinePhase.INCIDENT, sequence=1, resumePhase=MinePhase.MINING,
            incident=MineIncidentState(MineIncidentType.TUNNEL_DRIVE,required=93,objectiveNonce=1,
                working=MineWorkingState(p,MineWorkingStage.EXCAVATE))))
    val at = p.position(0,1,startForward).location(world)
    val blocks = WorksitePreparedScene(world,runtime.settings.id,1,1,at,at,at,emptyList())
    val scene = MineWorkingScene(plan,blocks)
    val sceneWorld = mockk<MineWorkingWorld>(relaxed=true)
    every { sceneWorld.scene(runtime) } returns scene
    val input = mockk<Input>(relaxed=true) { every { isForward } returns true }
    val player = mockk<Player>(relaxed=true) { every { currentInput } returns input; every { gameMode } returns GameMode.CREATIVE }
    var velocity = Vector()
    val cart = mockk<Minecart>(relaxed=true) {
        every { location } answers { at.clone() }
        every { this@mockk.world } returns world
        every { passengers } returns listOf(player)
        every { getVelocity() } answers { velocity.clone() }
        every { setVelocity(any()) } answers { velocity = firstArg<Vector>().clone() }
    }
    val rigs = mockk<MineDriveRig>(relaxed=true)
    every { rigs.ensure(runtime) } returns MineDriveRig.Rig(cart,emptyList(),0f)
    val port = immediateMinePort()
    val saved = CompletableFuture<Unit>()
    every { port.persistAsync() } returns saved
    val controller = MineDriveController(plugin,sceneWorld,port,port,port,mockk(relaxed=true),rigs)
    return DriveFixture(controller,runtime,scene,sceneWorld,rigs,player,cart,saved)
}
