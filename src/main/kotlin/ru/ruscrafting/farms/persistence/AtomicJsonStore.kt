package ru.ruscrafting.farms.persistence

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
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
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "arcfarms-state-writer").apply { isDaemon = true }
    }

    fun load(): T {
        if (!Files.isRegularFile(path)) return emptyValue()
        val size = Files.size(path)
        require(size in 1..MAX_FILE_BYTES) { "State file $path has invalid size $size" }
        val value = Files.newBufferedReader(path, StandardCharsets.UTF_8).use { gson.fromJson(it, type) }
            ?: error("State file $path is empty")
        validate(value)
        return value
    }

    fun saveAsync(value: T): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        executor.execute {
            try {
                write(value)
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

    private fun write(value: T) {
        validate(value)
        Files.createDirectories(path.parent)
        val bytes = gson.toJson(value).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size.toLong() <= MAX_FILE_BYTES) { "State file $path exceeds $MAX_FILE_BYTES bytes" }
        val temporary = path.resolveSibling(".${path.fileName}.new")
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { channel ->
            var buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
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
