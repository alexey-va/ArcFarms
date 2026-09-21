package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.persistence.MineFurnishingPose
import ru.ruscrafting.farms.persistence.MineFurnishingRepository
import ru.ruscrafting.farms.persistence.MineFurnishingState
import ru.ruscrafting.farms.domain.mine.expedition.*
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene
import org.bukkit.Location

class MineFurnishingEditorLifecycleTest : FunSpec({
    test("cold construction needs no active epoch and saved poses load only after activation") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("FurnishingColdStart")
            val scheduler = TestTaskScheduler()
            val supervisor = RuntimeTaskSupervisor(scheduler)
            val tasks = EditorTestTasks(supervisor)
            val markers = MineExpeditionMarkers(plugin)
            val repository = MineFurnishingRepository(plugin.dataFolder.toPath())
            val pose = MineFurnishingPose(x = 4, yaw = 90)
            repository.saveAsync(MineFurnishingState(poses = mapOf("16/decor_furnace_left" to pose,"16/decor_crusher_left" to pose))).join()
            val world=paper.server.addSimpleWorld("furnishing_turn")
            val plan=MineExpeditionGenerator.plan(MineExpeditionKind.DEAD_FACTORY,73L)
            val placement=MineExpeditionPlacement(world.name,0,60,0,73L)
            val surface=Location(world,.5,65.0,.5)
            val scene=MineExpeditionScene(plan,placement,world,"factory",1,1,16,surface,
                WorksitePreparedScene(world,"factory",1,1,surface,surface,surface,emptyList()))
            // Production constructs the graph before ArcFarmsService.start activates this scope.
            val editor = MineFurnishingEditor(plugin, tasks, null, { emptyList() }, markers)
            try {
                tasks.tokenRequests shouldBe 0
                editor.pose(16, "decor_furnace_left") shouldBe MineFurnishingPose()
                editor.yaw(scene,"decor_crusher_left") shouldBe 180
                MineExpeditionFurnishings.targets(scene,null,emptySet()).filter { it.id.startsWith("decor_crusher_") }
                    .map { it.yaw } shouldBe listOf(180,180)
                supervisor.activate()
                editor.initialize()
                val deadline = System.nanoTime() + 2_000_000_000L
                while (editor.pose(16, "decor_furnace_left") != pose && System.nanoTime() < deadline) {
                    scheduler.executeImmediate()
                    Thread.sleep(5)
                }
                editor.pose(16, "decor_furnace_left") shouldBe pose
                editor.yaw(scene,"decor_furnace_left") shouldBe 90
                editor.yaw(scene,"decor_crusher_left") shouldBe 270
                val crusher=MineExpeditionFurnishings.fixtures(scene).first { it.id=="decor_crusher_left" }
                editor.position(scene,crusher.id,crusher.at) shouldBe crusher.at.offset(dx=4)
                MineExpeditionFurnishings.targets(scene,editor,emptySet()).first { it.id==crusher.id }.yaw shouldBe 270
                tasks.tokenRequests shouldBe 1
            } finally {
                supervisor.close(); editor.close(); repository.close(); markers.cleanup()
            }
        }
    }
})

private class EditorTestTasks(private val supervisor: RuntimeTaskSupervisor) : WorksiteTaskPort {
    var tokenRequests = 0
    override fun lifecycleToken(): RuntimeTaskSupervisor.Token { tokenRequests++; return supervisor.token() }
    override fun guarded(scope: String, task: () -> Unit) = task()
    override fun runSync(token: RuntimeTaskSupervisor.Token, task: () -> Unit) = supervisor.runSync(token, task) != null
    override fun runAsync(token: RuntimeTaskSupervisor.Token, task: () -> Unit) = supervisor.runAsync(token, task) != null
    override fun runLater(delayTicks: Long, task: () -> Unit) = supervisor.runLater(delayTicks, task) != null
    override fun runLater(token: RuntimeTaskSupervisor.Token, delayTicks: Long, task: () -> Unit) = supervisor.runLater(token, delayTicks, task) != null
}
