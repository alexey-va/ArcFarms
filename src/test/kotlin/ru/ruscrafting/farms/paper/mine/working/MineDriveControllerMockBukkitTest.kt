package ru.ruscrafting.farms.paper.mine.working

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.GameMode
import org.bukkit.Input
import org.bukkit.Location
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
        test("excavation buffer requires durable permission and handles $outcome completion") {
            val paper = MockBukkitTestRuntime.open()
            try {
                val f = driveFixture(paper)
                f.tick()
                f.controller.busy(f.runtime.settings.id) shouldBe true
                f.runtime.state.incident!!.working!!.drive!!.prepared.isNotEmpty() shouldBe true
                f.runtime.state.incident!!.working!!.drive!!.carved.isEmpty() shouldBe true
                verify(exactly=0) { f.world.project(any(), any(), any()) }
                f.cart.velocity shouldBe Vector()
                if (outcome == "stale") f.runtime.state = f.runtime.state.copy(sequence=99)
                if (outcome == "failed") f.saves.first().completeExceptionally(IllegalStateException("disk full"))
                else f.saves.first().complete(Unit)
                f.controller.busy(f.runtime.settings.id) shouldBe false
                // A completed write authorizes excavation, but does not remove distant blocks itself.
                verify(exactly=0) { f.world.project(any(), any(), any()) }
                f.tick()
                if (outcome == "saved") {
                    f.runtime.state.incident!!.working!!.drive!!.carved.isNotEmpty() shouldBe true
                    (f.cart.velocity.length() > 0) shouldBe true
                } else {
                    f.runtime.state.incident!!.working!!.drive!!.carved.isEmpty() shouldBe true
                    f.cart.velocity shouldBe Vector()
                }
            } finally { paper.close() }
        }
    }
    test("straight driving cuts every new face without a zero-speed tick during slow checkpoint writes") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val f = driveFixture(paper, startForward=5, startSide=4.5)
            f.tick()
            f.saves.first().complete(Unit)
            repeat(100) { tick ->
                f.tick()
                check(f.cart.velocity.z > 0) { "stopped tick=$tick at=${f.at} prepared=${f.runtime.state.incident!!.working!!.drive!!.prepared.size} saves=${f.saves.map { it.isDone }}" }
                f.at.add(f.cart.velocity)
                if (tick % 20 == 19) f.saves.filterNot { it.isDone }.forEach { it.complete(Unit) }
            }
            (f.at.z > 16) shouldBe true
            (f.runtime.state.incident!!.working!!.drive!!.carved.size > 30) shouldBe true
            f.runtime.state.incident!!.working!!.drive!!.carved.all {
                MineDriveLayout.driveable(MineDriveLayout.side(it),MineDriveLayout.forward(it))
            } shouldBe true
        } finally { paper.close() }
    }
    test("sideways steering and reverse excavation are allowed inside the owned volume") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val f = driveFixture(paper, startForward=20, prepared=true, heading=90f)
            every { f.input.isForward } returns false
            every { f.input.isBackward } returns true
            repeat(12) { f.tick(); (f.cart.velocity.x > 0) shouldBe true; f.at.add(f.cart.velocity) }
            every { f.input.isBackward } returns false
            every { f.input.isRight } returns true
            repeat(50) { f.tick() }
            (f.rig.heading > 180f) shouldBe true
        } finally { paper.close() }
    }
    test("bedrock and outer walls stop the complete machine without escaping or carving protected cells") {
        for (location in listOf(0.0 to 10, 14.0 to 20)) {
            val paper = MockBukkitTestRuntime.open()
            try {
                val (side,forward) = location
                val f = driveFixture(paper, startForward=forward, startSide=side, prepared=true,
                    heading=if(side>0) 270f else 0f)
                repeat(30) { f.tick(); f.at.add(f.cart.velocity) }
                f.cart.velocity shouldBe Vector()
                f.runtime.state.incident!!.working!!.drive!!.carved.all {
                    MineDriveLayout.driveable(MineDriveLayout.side(it),MineDriveLayout.forward(it))
                } shouldBe true
                f.scene.plan.blocks.filterValues { it=="minecraft:bedrock" }.keys.all { p ->
                    f.at.world.getBlockAt(p.x,p.y,p.z).type==Material.BEDROCK
                } shouldBe true
            } finally { paper.close() }
        }
    }
    test("cleanup fences an old write completion from a replacement operation") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val f=driveFixture(paper)
            f.tick()
            val old=f.saves.first()
            f.controller.cleanup(f.runtime.settings.id)
            f.runtime.state=f.runtime.state.copy(incident=f.runtime.state.incident!!.copy(
                objectiveNonce=2,working=f.runtime.state.incident!!.working!!.copy(drive=null)))
            f.tick()
            old.complete(Unit)
            f.controller.busy(f.runtime.settings.id) shouldBe true
            verify(exactly=0) { f.world.project(any(),any(),any()) }
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
    val player: Player, val cart: Minecart, val saves: MutableList<CompletableFuture<Unit>>,
    val at: Location, val input: Input, val rig: MineDriveRig.Rig) {
    private var now = 1000L
    fun tick() { now+=50; controller.tick(runtime,scene,now,{true}) { _,_ -> error("goal not reached") } }
}

private fun driveFixture(paper: MockBukkitTestRuntime, startForward: Int = 2,
    startSide: Double = 0.0, prepared: Boolean = false, heading: Float = 0f): DriveFixture {
    val world = paper.server.addSimpleWorld("world")
    val plugin = paper.createSimplePlugin("DriveTest")
    for (x in -1..1) for (z in -1..3) world.getChunkAt(x,z).load()
    val p = MineWorkingPlacement(WorksitePosition(world.name,0,64,0),0,"test")
    val plan = MineDriveLayout.plan(p)
    plan.blocks.forEach { (pos,data) -> world.getBlockAt(pos.x,pos.y,pos.z).type = Material.valueOf(data.substringAfter(':').substringBefore('[').uppercase()) }
    val reserved = (0 until MineDriveLayout.MAX_CELLS).filterTo(linkedSetOf()) {
        MineDriveLayout.driveable(MineDriveLayout.side(it),MineDriveLayout.forward(it))
    }
    val runtime = MineRuntime(mineV2Settings(), CuboidActivityRegion(world,"test",CuboidBounds(-20,50,-20,50,100,60)),5000,
        MineShiftState(engineVersion=2, phase=MinePhase.INCIDENT, sequence=1, resumePhase=MinePhase.MINING,
            incident=MineIncidentState(MineIncidentType.TUNNEL_DRIVE,required=93,objectiveNonce=1,
                working=MineWorkingState(p,MineWorkingStage.EXCAVATE,
                    drive=if(prepared) MineDriveProgress(prepared=reserved) else null))))
    val at = p.position(0,1,startForward).location(world).add(startSide,0.0,0.0)
    val blocks = WorksitePreparedScene(world,runtime.settings.id,1,1,at,at,at,emptyList())
    val scene = MineWorkingScene(plan,blocks)
    val sceneWorld = mockk<MineWorkingWorld>(relaxed=true)
    every { sceneWorld.scene(runtime) } returns scene
    every { sceneWorld.project(runtime,any(),any()) } answers {
        runtime.state.incident!!.working!!.drive!!.carved.forEach { id ->
            for(up in 1..4) {
                val pos=MineDriveLayout.position(p,id,up)
                if(world.getBlockAt(pos.x,pos.y,pos.z).type!=Material.BEDROCK)
                    world.getBlockAt(pos.x,pos.y,pos.z).type=Material.AIR
            }
        }
    }
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
    val rig=MineDriveRig.Rig(cart,emptyList(),heading)
    every { rigs.ensure(runtime) } returns rig
    every { rigs.get(runtime.settings.id) } returns rig
    val port = immediateMinePort()
    val saves = mutableListOf<CompletableFuture<Unit>>()
    every { port.persistAsync() } answers { CompletableFuture<Unit>().also { saves+=it } }
    val controller = MineDriveController(plugin,sceneWorld,port,port,port,mockk(relaxed=true),rigs)
    return DriveFixture(controller,runtime,scene,sceneWorld,rigs,player,cart,saves,at,input,rig)
}
