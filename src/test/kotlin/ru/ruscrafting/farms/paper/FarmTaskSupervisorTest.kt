package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.TestTaskScheduler

class FarmTaskSupervisorTest : FunSpec({
    test("reload cancels delayed work from the previous runtime") {
        val scheduler = TestTaskScheduler()
        val tasks = FarmTaskSupervisor(scheduler)
        var executions = 0

        tasks.activate()
        tasks.runLater(5L) { executions++ }
        tasks.cancelAll()
        tasks.activate()
        scheduler.tick(5L)

        executions shouldBe 0
        tasks.trackedCount() shouldBe 0
    }

    test("an async completion cannot enter a replacement runtime with an old token") {
        val scheduler = TestTaskScheduler()
        val tasks = FarmTaskSupervisor(scheduler)
        var executions = 0

        tasks.activate()
        val oldRuntime = tasks.token()
        tasks.cancelAll()
        tasks.activate()

        tasks.runSync(oldRuntime) { executions++ } shouldBe null
        scheduler.executeImmediate()
        executions shouldBe 0
    }

    test("a token from another service instance is never accepted") {
        val scheduler = TestTaskScheduler()
        val first = FarmTaskSupervisor(scheduler)
        val second = FarmTaskSupervisor(scheduler)
        first.activate()
        second.activate()

        second.runSync(first.token()) { error("foreign callback executed") } shouldBe null
        scheduler.executeImmediate()
    }

    test("completed one shot work is untracked") {
        val scheduler = TestTaskScheduler()
        val tasks = FarmTaskSupervisor(scheduler)
        tasks.activate()

        tasks.runLater(1L) {}
        tasks.trackedCount() shouldBe 1
        scheduler.tick()

        tasks.trackedCount() shouldBe 0
    }

    test("shutdown cancels repeating work") {
        val scheduler = TestTaskScheduler()
        val tasks = FarmTaskSupervisor(scheduler)
        var executions = 0
        tasks.activate()
        tasks.runTimer(1L, 1L) { executions++ }

        scheduler.tick()
        executions shouldBe 1
        tasks.cancelAll()
        scheduler.tick(5L)

        executions shouldBe 1
        scheduler.timerCount() shouldBe 0
    }

    test("cancellation racing task-handle attachment still cancels the submitted work") {
        val delegate = TestTaskScheduler()
        lateinit var tasks: FarmTaskSupervisor
        val scheduler = object : TaskScheduler by delegate {
            override fun runLater(delayTicks: Long, task: Runnable) =
                delegate.runLater(delayTicks, task).also { tasks.cancelAll() }
        }
        tasks = FarmTaskSupervisor(scheduler)
        tasks.activate()

        tasks.runLater(1L) {} shouldBe null
        delegate.tick()

        tasks.trackedCount() shouldBe 0
        delegate.pendingCount() shouldBe 0
    }

    test("scheduler submission failure does not leak a tracked task") {
        val delegate = TestTaskScheduler()
        val scheduler = object : TaskScheduler by delegate {
            override fun runLater(delayTicks: Long, task: Runnable) = error("scheduler unavailable")
        }
        val tasks = FarmTaskSupervisor(scheduler)
        tasks.activate()

        shouldThrow<IllegalStateException> { tasks.runLater(1L) {} }

        tasks.trackedCount() shouldBe 0
    }

    test("one broken cancellation does not leave the remaining tasks alive") {
        val delegate = TestTaskScheduler()
        val handles = mutableListOf<RecordingTask>()
        val scheduler = object : TaskScheduler by delegate {
            override fun runLater(delayTicks: Long, task: Runnable): ScheduledTask =
                RecordingTask(handles.size + 1, failOnCancel = handles.isEmpty()).also(handles::add)
        }
        val tasks = FarmTaskSupervisor(scheduler)
        tasks.activate()
        tasks.runLater(1L) {}
        tasks.runLater(1L) {}

        shouldThrow<IllegalStateException> { tasks.cancelAll() }

        handles.map(RecordingTask::isCancelled) shouldBe listOf(true, true)
        tasks.trackedCount() shouldBe 0
    }
})

private class RecordingTask(
    override val id: Int,
    private val failOnCancel: Boolean,
) : ScheduledTask {
    override var isCancelled: Boolean = false
        private set

    override fun cancel() {
        isCancelled = true
        if (failOnCancel) error("cancel failed")
    }
}
