package ru.ruscrafting.farms.paper.fixtures

import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BukkitTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** Real worker and the production shared native queue; no immediate fake compute boundary. */
internal class FarmAsyncHarness(name: String) : AutoCloseable {
    val nativeThread = Thread.currentThread()
    val paper = MockBukkitTestRuntime.open()
    val plugin = paper.createSimplePlugin(name)
    private val supervisor = RuntimeTaskSupervisor(BukkitTaskScheduler(plugin)).also { it.activate() }
    private val worker = BoundedAsyncExecutor("farm-async-test")
    val workerCalls = AtomicInteger()
    var tickSteps = 0
    var maximumTickSteps = 0
    var ticks = 0
    var gate: CompletableFuture<Unit>? = null
    val tasks = object : WorksiteTaskPort {
        override fun guarded(scope: String, task: () -> Unit) = task()
        override fun lifecycleToken() = supervisor.token()
        override fun capture(token: RuntimeTaskSupervisor.Token, isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> {
            check(Thread.currentThread() === nativeThread)
            return supervisor.capture(token, isCurrent) {
                check(Thread.currentThread() === nativeThread)
                tickSteps++
                step()
            }
        }
        override fun <T> callSync(token: RuntimeTaskSupervisor.Token, task: () -> T) = supervisor.callSync(token, task)
        override fun <T> compute(task: () -> T): CompletableFuture<T> {
            val capturedGate = gate
            return worker.submit {
                check(Thread.currentThread() !== nativeThread)
                capturedGate?.join()
                workerCalls.incrementAndGet()
                task()
            }
        }
        override fun runSync(token: RuntimeTaskSupervisor.Token, task: () -> Unit) = supervisor.runSync(token, task) != null
        override fun runAsync(token: RuntimeTaskSupervisor.Token, task: () -> Unit) = supervisor.runAsync(token, task) != null
        override fun runLater(delayTicks: Long, task: () -> Unit) = supervisor.runLater(delayTicks, task) != null
        override fun runLater(token: RuntimeTaskSupervisor.Token, delayTicks: Long, task: () -> Unit) = supervisor.runLater(token, delayTicks, task) != null
    }

    fun tick(before: () -> Unit = {}) {
        tickSteps = 0
        before()
        paper.performTicks(1)
        maximumTickSteps = maxOf(maximumTickSteps, tickSteps)
        ticks++
        Thread.sleep(1)
    }

    fun until(before: () -> Unit = {}, done: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!done()) {
            check(System.nanoTime() < deadline) { "Async farm work did not complete after $ticks ticks" }
            tick(before)
        }
    }

    override fun close() {
        gate?.complete(Unit)
        supervisor.close()
        worker.closeAsync().join()
        paper.close()
    }
}
