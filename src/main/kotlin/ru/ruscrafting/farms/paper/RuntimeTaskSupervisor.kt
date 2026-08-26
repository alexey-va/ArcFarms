package ru.ruscrafting.farms.paper

import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Owns every service-level scheduled callback for one ArcFarms runtime epoch.
 * Reload and shutdown invalidate the epoch before cancelling its task handles,
 * so a racing async completion cannot enter a replacement runtime.
 */
internal class RuntimeTaskSupervisor(
    private val scheduler: TaskScheduler = Tasks.scheduler,
) : AutoCloseable {
    internal class Token internal constructor(
        internal val owner: RuntimeTaskSupervisor,
        internal val generation: Long,
    )

    private class Entry(val token: Token, val repeating: Boolean) {
        @Volatile
        var handle: ScheduledTask? = null
    }

    private val monitor = Any()
    private val tracked = Collections.newSetFromMap(IdentityHashMap<Entry, Boolean>())
    private var generation = 0L
    private var active = false

    fun activate() {
        synchronized(monitor) {
            check(!active) { "Worksite task runtime is already active" }
            generation = nextGeneration(generation)
            active = true
        }
    }

    fun token(): Token = synchronized(monitor) {
        check(active) { "Worksite task runtime is not active" }
        Token(this, generation)
    }

    fun runSync(task: () -> Unit): ScheduledTask? =
        currentToken()?.let { runSync(it, task) }

    fun runSync(token: Token, task: () -> Unit): ScheduledTask? =
        schedule(token, repeating = false, { runnable -> scheduler.runSync(runnable) }, task)

    fun runLater(delayTicks: Long, task: () -> Unit): ScheduledTask? =
        currentToken()?.let { runLater(it, delayTicks, task) }

    fun runLater(token: Token, delayTicks: Long, task: () -> Unit): ScheduledTask? {
        require(delayTicks >= 0L) { "Runtime task delay must not be negative" }
        return schedule(token, repeating = false, { runnable -> scheduler.runLater(delayTicks, runnable) }, task)
    }

    fun runTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTask? {
        require(delayTicks >= 0L) { "Runtime timer delay must not be negative" }
        require(periodTicks >= 1L) { "Runtime timer period must be positive" }
        val token = currentToken() ?: return null
        return schedule(token, repeating = true, { runnable ->
            scheduler.runTimer(delayTicks, periodTicks, runnable)
        }, task)
    }

    fun cancelAll() {
        val cancelled = synchronized(monitor) {
            active = false
            generation = nextGeneration(generation)
            tracked.toList().also { tracked.clear() }
        }
        var firstFailure: RuntimeException? = null
        cancelled.forEach { entry ->
            try {
                entry.handle?.cancel()
            } catch (failure: RuntimeException) {
                val previous = firstFailure
                if (previous == null) firstFailure = failure else previous.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw IllegalStateException("Could not cancel every worksite runtime task", it) }
    }

    internal fun trackedCount(): Int = synchronized(monitor) { tracked.size }

    override fun close() = cancelAll()

    private fun schedule(
        token: Token,
        repeating: Boolean,
        submit: (Runnable) -> ScheduledTask,
        task: () -> Unit,
    ): ScheduledTask? {
        val entry = Entry(token, repeating)
        synchronized(monitor) {
            if (!isCurrent(token)) return null
            tracked += entry
        }
        val runnable = Runnable {
            try {
                if (synchronized(monitor) { isCurrent(token) }) task()
            } finally {
                if (!entry.repeating) synchronized(monitor) { tracked.remove(entry) }
            }
        }
        val handle = try {
            submit(runnable)
        } catch (failure: RuntimeException) {
            synchronized(monitor) { tracked.remove(entry) }
            throw failure
        }
        entry.handle = handle
        val cancelledBeforeAttach = synchronized(monitor) { entry !in tracked || !isCurrent(token) }
        if (cancelledBeforeAttach) handle.cancel()
        return handle.takeUnless { cancelledBeforeAttach }
    }

    private fun currentToken(): Token? = synchronized(monitor) {
        if (active) Token(this, generation) else null
    }

    private fun isCurrent(token: Token): Boolean = token.owner === this && active && token.generation == generation

    private fun nextGeneration(current: Long): Long = if (current == Long.MAX_VALUE) 0L else current + 1L
}
