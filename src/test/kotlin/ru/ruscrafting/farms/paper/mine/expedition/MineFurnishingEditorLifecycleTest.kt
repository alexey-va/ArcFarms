package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
    test("each geometry version retains its exact ordered fixture output in one immutable scene list") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("furnishing_versions")
            val surface = Location(world, .5, 65.0, .5)
            for (kind in MineExpeditionKind.entries) {
                for (version in 1..MineExpeditionGenerator.currentGeometryVersion(kind)) {
                    val plan = MineExpeditionGenerator.plan(kind, 73L, version)
                    val expected = MineExpeditionFurnishings.fixtures(plan)
                    val placement = MineExpeditionPlacement(world.name, 0, 60, 0, 73L, version)
                    val scene = MineExpeditionScene(plan, placement, world, "fixture", 1, 1, 16, surface,
                        WorksitePreparedScene(world, "fixture", 1, 1, surface, surface, surface, emptyList()))
                    val actual = MineExpeditionFurnishings.fixtures(scene)
                    actual shouldBe expected
                    (MineExpeditionFurnishings.fixtures(scene) === actual) shouldBe true
                    shouldThrow<UnsupportedOperationException> {
                        (actual as MutableList<MineExpeditionFurnishings.Fixture>).clear()
                    }
                }
            }
        }
    }

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
            var fixtureBuilds = 0
            mockkObject(MineExpeditionFurnishings)
            every { MineExpeditionFurnishings.fixtures(plan) } answers { fixtureBuilds++; callOriginal() }
            try {
                fixtureBuilds shouldBe 0
                tasks.tokenRequests shouldBe 0
                editor.pose(16, "decor_furnace_left") shouldBe MineFurnishingPose()
                editor.yaw(scene,"decor_crusher_left") shouldBe 0
                MineExpeditionFurnishings.targets(scene,null,emptySet()).filter { it.id.startsWith("decor_crusher_") }
                    .map { it.yaw } shouldBe listOf(0)
                // Idle decorative machines must not intercept ray clicks intended for active controls.
                MineExpeditionFurnishings.targets(scene,null,emptySet()).all { !it.interactive } shouldBe true
                val console=MineExpeditionFurnishings.fixtures(scene).first { it.id=="control_crusher_left" }
                editor.position(scene,console.id,console.at) shouldBe console.at
                supervisor.activate()
                editor.initialize()
                val deadline = System.nanoTime() + 2_000_000_000L
                while (editor.pose(16, "decor_furnace_left") != pose && System.nanoTime() < deadline) {
                    scheduler.executeImmediate()
                    Thread.sleep(5)
                }
                editor.pose(16, "decor_furnace_left") shouldBe pose
                editor.yaw(scene,"decor_furnace_left") shouldBe 180
                editor.yaw(scene,"decor_crusher_left") shouldBe 90
                val crusher=MineExpeditionFurnishings.fixtures(scene).first { it.id=="decor_crusher_left" }
                editor.position(scene,crusher.id,crusher.at) shouldBe crusher.at.offset(dx=4)
                MineExpeditionFurnishings.targets(scene,editor,emptySet()).first { it.id==crusher.id }.yaw shouldBe 90
                // Moving and turning the assembly carries its mounted control with it.
                editor.position(scene,console.id,console.at) shouldBe ExpeditionPoint(-14,5,-8)
                repeat(20) {
                    editor.position(scene,console.id,console.at) shouldBe ExpeditionPoint(-14,5,-8)
                    editor.yaw(scene,console.id) shouldBe 90
                }
                fixtureBuilds shouldBe 1
                val retainedTargets = MineExpeditionFurnishings.targets(scene,editor,emptySet())
                every { MineExpeditionFurnishings.fixtures(scene) } answers { MineExpeditionFurnishings.fixtures(plan) }
                MineExpeditionFurnishings.targets(scene,editor,emptySet()) shouldBe retainedTargets
                tasks.tokenRequests shouldBe 1
            } finally {
                unmockkObject(MineExpeditionFurnishings)
                supervisor.close(); editor.close(); repository.close(); markers.cleanup()
            }
        }
    }
})

private class EditorTestTasks(private val supervisor: RuntimeTaskSupervisor) : WorksiteTaskPort {
    override fun capture(token: RuntimeTaskSupervisor.Token, isCurrent: () -> Boolean, step: () -> Boolean) =
        supervisor.capture(token, isCurrent, step)
    var tokenRequests = 0
    override fun lifecycleToken(): RuntimeTaskSupervisor.Token { tokenRequests++; return supervisor.token() }
    override fun guarded(scope: String, task: () -> Unit) = task()
    override fun runSync(token: RuntimeTaskSupervisor.Token, task: () -> Unit) = supervisor.runSync(token, task) != null
    override fun runAsync(token: RuntimeTaskSupervisor.Token, task: () -> Unit) = supervisor.runAsync(token, task) != null
    override fun runLater(delayTicks: Long, task: () -> Unit) = supervisor.runLater(delayTicks, task) != null
    override fun runLater(token: RuntimeTaskSupervisor.Token, delayTicks: Long, task: () -> Unit) = supervisor.runLater(token, delayTicks, task) != null
}
