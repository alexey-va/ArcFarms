package ru.ruscrafting.farms.persistence

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Files
import ru.arc.persistence.AtomicFileStore
import java.util.concurrent.CompletableFuture
import ru.arc.core.BoundedAsyncExecutor
import java.util.concurrent.TimeUnit

class AtomicJsonStore<T : Any>(
    private val path: Path,
    private val type: Class<T>,
    private val emptyValue: () -> T,
    private val validate: (T) -> Unit,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create(),
) : AutoCloseable {
    private val store by lazy { AtomicFileStore(
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
    ) }
    private val executor = BoundedAsyncExecutor("arcfarms-state-writer")

    @Volatile private var loaded: T? = null
    private var persisted: T? = null
    private var hasPersisted = false

    /** Cold load is prepared by the plugin worker; subsequent runtime reads are memory-only. */
    fun load(): T = loaded ?: run {
        val existed = Files.isRegularFile(path)
        store.loadOrDefault(emptyValue).also {
            loaded = it
            if (existed) { persisted = it; hasPersisted = true }
        }
    }

    fun loadAsync(): CompletableFuture<T> = submit(::load)

    fun saveAsync(value: T): CompletableFuture<Unit> = submit {
        // Deep equality belongs to this serial worker, never to the server snapshot lock.
        if (!hasPersisted || persisted != value) {
            loaded = store.write(value)
            persisted = loaded
            hasPersisted = true
        }
        Unit
    }

    private fun <R> submit(operation: () -> R): CompletableFuture<R> = executor.submit(operation)

    /** Already accepted durable writes drain without blocking the server thread. */
    fun shutdown() = executor.close()

    fun closeAsync(): CompletableFuture<Unit> = executor.closeAsync()

    fun saveBlocking(value: T) {
        saveAsync(value).get(10, TimeUnit.SECONDS)
    }

    override fun close() { closeAsync() }

    companion object {
        const val MAX_FILE_BYTES: Long = 16L * 1024L * 1024L
    }
}
