package ru.ruscrafting.farms.persistence

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import ru.arc.persistence.AtomicFileStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AtomicJsonStore<T : Any>(
    private val path: Path,
    private val type: Class<T>,
    private val emptyValue: () -> T,
    private val validate: (T) -> Unit,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create(),
) : AutoCloseable {
    private val store = AtomicFileStore(
        root = requireNotNull(path.parent) { "Atomic JSON path must have a parent" },
        relativePath = path.fileName,
        maxBytes = MAX_FILE_BYTES,
        encode = { value: T -> gson.toJson(value).toByteArray(StandardCharsets.UTF_8) },
        decode = { bytes ->
            requireNotNull(gson.fromJson(bytes.toString(StandardCharsets.UTF_8), type)) {
                "State file $path is empty"
            }
        },
        validate = validate,
    )
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "arcfarms-state-writer").apply { isDaemon = true }
    }

    fun load(): T = store.loadOrDefault(emptyValue)

    fun saveAsync(value: T): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        executor.execute {
            try {
                store.write(value)
                future.complete(Unit)
            } catch (failure: Throwable) {
                future.completeExceptionally(failure)
            }
        }
        return future
    }

    fun saveBlocking(value: T) {
        saveAsync(value).get(10, TimeUnit.SECONDS)
    }

    override fun close() {
        executor.shutdown()
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "ArcFarms state writer did not stop" }
        }
    }

    companion object {
        const val MAX_FILE_BYTES: Long = 16L * 1024L * 1024L
    }
}
