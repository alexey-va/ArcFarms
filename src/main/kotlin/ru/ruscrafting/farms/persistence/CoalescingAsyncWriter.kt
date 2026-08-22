package ru.ruscrafting.farms.persistence

import java.util.concurrent.CompletableFuture

/**
 * Keeps at most one write in flight and one latest-value write waiting behind it.
 * Every caller still completes only after a snapshot at least as new as its own
 * has been written.
 */
internal class CoalescingAsyncWriter<T>(
    private val write: (T) -> CompletableFuture<Unit>,
) {
    private data class Batch<T>(
        var value: T,
        val waiters: MutableList<CompletableFuture<Unit>>,
    )

    private val lock = Any()
    private var inFlight = false
    private var accepting = true
    private var pending: Batch<T>? = null
    private val idleWaiters = mutableListOf<CompletableFuture<Unit>>()

    fun submit(value: T): CompletableFuture<Unit> {
        val waiter = CompletableFuture<Unit>()
        val first = synchronized(lock) {
            if (!accepting) {
                waiter.completeExceptionally(IllegalStateException("ArcFarms state writer is closed"))
                return waiter
            }
            if (!inFlight) {
                inFlight = true
                Batch(value, mutableListOf(waiter))
            } else {
                val queued = pending
                if (queued == null) pending = Batch(value, mutableListOf(waiter))
                else {
                    queued.value = value
                    queued.waiters += waiter
                }
                null
            }
        }
        if (first != null) write(first)
        return waiter
    }

    fun close(): CompletableFuture<Unit> = synchronized(lock) {
        accepting = false
        if (!inFlight) CompletableFuture.completedFuture(Unit)
        else CompletableFuture<Unit>().also(idleWaiters::add)
    }

    private fun write(batch: Batch<T>) {
        val operation = runCatching { write.invoke(batch.value) }
            .getOrElse { failure -> CompletableFuture.failedFuture(failure) }
        operation.whenComplete { _, failure ->
            val next = synchronized(lock) {
                pending.also {
                    pending = null
                    if (failure != null && it != null) it.waiters.addAll(0, batch.waiters)
                    if (it == null) {
                        inFlight = false
                        idleWaiters.forEach { waiter ->
                            if (failure == null) waiter.complete(Unit) else waiter.completeExceptionally(failure)
                        }
                        idleWaiters.clear()
                    }
                }
            }
            if (failure == null || next == null) {
                batch.waiters.forEach { waiter ->
                    if (failure == null) waiter.complete(Unit) else waiter.completeExceptionally(failure)
                }
            }
            if (next != null) write(next)
        }
    }
}
