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
            repository.saveAsync(MineFurnishingState(poses = mapOf("16/decor_furnace_left" to pose))).join()
            // Production constructs the graph before ArcFarmsService.start activates this scope.
            val editor = MineFurnishingEditor(plugin, tasks, null, { emptyList() }, markers)
            try {
                tasks.tokenRequests shouldBe 0
                editor.pose(16, "decor_furnace_left") shouldBe MineFurnishingPose()
                supervisor.activate()
                editor.initialize()
                val deadline = System.nanoTime() + 2_000_000_000L
                while (editor.pose(16, "decor_furnace_left") != pose && System.nanoTime() < deadline) {
                    scheduler.executeImmediate()
                    Thread.sleep(5)
                }
                editor.pose(16, "decor_furnace_left") shouldBe pose
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
