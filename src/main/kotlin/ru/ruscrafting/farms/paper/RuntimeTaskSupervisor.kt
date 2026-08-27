package ru.ruscrafting.farms.paper

import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks

/**
 * Compatibility adapter while ArcFarms feature packages move to the shared
 * [LifecycleTaskScope] name. Epoch ownership and race handling live in core.
 */
internal class RuntimeTaskSupervisor(
    scheduler: TaskScheduler = Tasks.scheduler,
) : AutoCloseable {
    internal class Token internal constructor(internal val delegate: LifecycleTaskScope.Token)

    private val delegate = LifecycleTaskScope(scheduler, initiallyActive = false)

    fun activate() {
        delegate.activate()
    }

    fun token(): Token = Token(delegate.token())

    fun runSync(task: () -> Unit): ScheduledTask? = delegate.runSync(task)

    fun runSync(token: Token, task: () -> Unit): ScheduledTask? = delegate.runSync(token.delegate, task)

    fun runAsync(task: () -> Unit): ScheduledTask? = delegate.runAsync(task)

    fun runAsync(token: Token, task: () -> Unit): ScheduledTask? = delegate.runAsync(token.delegate, task)

    fun runLater(delayTicks: Long, task: () -> Unit): ScheduledTask? = delegate.runLater(delayTicks, task)

    fun runLater(token: Token, delayTicks: Long, task: () -> Unit): ScheduledTask? =
        delegate.runLater(token.delegate, delayTicks, task)

    fun runTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTask? =
        delegate.runTimer(delayTicks, periodTicks, task)

    fun cancelAll() = delegate.cancelAll()

    internal fun trackedCount(): Int = delegate.trackedTaskCount()

    override fun close() = delegate.cancelAll()
}
