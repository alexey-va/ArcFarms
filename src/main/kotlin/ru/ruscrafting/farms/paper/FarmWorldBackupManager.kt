package ru.ruscrafting.farms.paper

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.util.SideEffectSet
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.plugin.Plugin
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.ruscrafting.farms.domain.FarmPlotPosition
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.UUID
import java.util.logging.Level

internal enum class FarmBackupPhase { COPYING, WRITING, READING, SAFETY_BACKUP, RESTORING }

internal data class FarmBackupStatus(
    val zoneId: String,
    val phase: FarmBackupPhase,
    val done: Long,
    val total: Long,
    val backupId: String?,
)

internal data class FarmBackupManifest(
    val schemaVersion: Int,
    val id: String,
    val zoneId: String,
    val world: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
    val volume: Long,
    val createdAt: Long,
    val reason: String,
    val sha256: String,
) {
    fun minimum() = FarmPlotPosition(world, minX, minY, minZ)
    fun maximum() = FarmPlotPosition(world, maxX, maxY, maxZ)
}

internal sealed interface FarmBackupStart {
    data class Started(val status: FarmBackupStatus) : FarmBackupStart
    data class Busy(val status: FarmBackupStatus) : FarmBackupStart
    data class Rejected(val reason: FarmBackupRejection) : FarmBackupStart
}

internal enum class FarmBackupRejection { SELECTION_REQUIRED, CUBOID_REQUIRED, WRONG_WORLD, OUTSIDE_ZONE, TOO_LARGE, UNKNOWN_BACKUP }

/** Owns bounded WorldEdit clipboard capture/restore jobs and their durable catalog. */
internal class FarmWorldBackupManager(
    private val plugin: Plugin,
    private val root: Path,
    private val clock: () -> Long = System::currentTimeMillis,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create(),
) : AutoCloseable {
    @Volatile
    private var active: ActiveJob? = null
    @Volatile
    private var closed = false
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ArcFarms-world-backups").apply { isDaemon = true }
    }

    fun status(zoneId: String): FarmBackupStatus? = active?.status()?.takeIf { it.zoneId == zoneId }

    fun activeStatus(): FarmBackupStatus? = active?.status()

    fun save(
        zoneId: String,
        region: ActivityRegion,
        selection: FarmWorldEditSelection,
        blocksPerTick: Int,
        maxBlocks: Int,
        onComplete: (FarmBackupManifest) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): FarmBackupStart {
        active?.let { return FarmBackupStart.Busy(it.status()) }
        validateSelection(region, selection, maxBlocks)?.let { return FarmBackupStart.Rejected(it) }
        return startCapture(
            zoneId,
            region,
            selection.minimum,
            selection.maximum,
            blocksPerTick,
            maxBlocks,
            "manual",
            FarmBackupPhase.COPYING,
            onComplete,
            onFailure,
        )
    }

    fun restore(
        zoneId: String,
        region: ActivityRegion,
        backupId: String,
        blocksPerTick: Int,
        maxBlocks: Int,
        onSafetyBackup: (FarmBackupManifest) -> Unit,
        onComplete: (FarmBackupManifest) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): FarmBackupStart {
        active?.let { return FarmBackupStart.Busy(it.status()) }
        if (!isValidId(backupId)) return FarmBackupStart.Rejected(FarmBackupRejection.UNKNOWN_BACKUP)
        val pending = PendingJob(FarmBackupStatus(zoneId, FarmBackupPhase.READING, 0, 1, backupId))
        active = pending
        executor.execute {
            runCatching { readBackup(zoneId, backupId, region.world, maxBlocks) }.fold(
                onSuccess = { loaded -> onMain {
                    if (closed || active !== pending) return@onMain
                    val safetyStart = startCapture(
                        zoneId = zoneId,
                        region = region,
                        minimum = loaded.manifest.minimum(),
                        maximum = loaded.manifest.maximum(),
                        blocksPerTick = blocksPerTick,
                        maxBlocks = maxBlocks,
                        reason = "pre_restore",
                        initialPhase = FarmBackupPhase.SAFETY_BACKUP,
                        onComplete = { safety ->
                            runCatching { onSafetyBackup(safety) }.onFailure { failure ->
                                plugin.logger.log(Level.WARNING, "Could not render farm safety-backup confirmation", failure)
                            }
                            startRestore(region, loaded, blocksPerTick, onComplete, onFailure)
                        },
                        onFailure = onFailure,
                        replace = pending,
                    )
                    if (safetyStart is FarmBackupStart.Rejected) failPending(pending, onFailure, IllegalStateException(safetyStart.reason.name))
                } },
                onFailure = { failure -> onMain { failPending(pending, onFailure, failure) } },
            )
        }
        return FarmBackupStart.Started(pending.status())
    }

    fun list(zoneId: String, onComplete: (List<FarmBackupManifest>) -> Unit, onFailure: (Throwable) -> Unit) {
        require(isValidZone(zoneId)) { "Invalid farm zone id" }
        executor.execute {
            runCatching<List<FarmBackupManifest>> {
                val directory = zoneDirectory(zoneId)
                if (!Files.isDirectory(directory)) return@runCatching emptyList<FarmBackupManifest>()
                Files.list(directory).use { paths ->
                    paths.iterator().asSequence()
                        .filter { it.fileName.toString().endsWith(MANIFEST_SUFFIX) }
                        .mapNotNull { path -> runCatching { readManifest(path) }.getOrNull() }
                        .filter { it.zoneId == zoneId }
                        .sortedByDescending(FarmBackupManifest::createdAt)
                        .take(MAX_LISTED_BACKUPS)
                        .toList()
                }
            }.fold(
                onSuccess = { records -> onMain { if (!closed) onComplete(records) } },
                onFailure = { failure -> onMain { if (!closed) onFailure(failure) } },
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        active?.cancel()
        active = null
        executor.shutdownNow()
    }

    private fun validateSelection(
        region: ActivityRegion,
        selection: FarmWorldEditSelection,
        maxBlocks: Int,
    ): FarmBackupRejection? = when {
        !selection.cuboid -> FarmBackupRejection.CUBOID_REQUIRED
        selection.world != region.world.name -> FarmBackupRejection.WRONG_WORLD
        selection.volume > maxBlocks -> FarmBackupRejection.TOO_LARGE
        !contains(region, selection.minimum) || !contains(region, selection.maximum) -> FarmBackupRejection.OUTSIDE_ZONE
        else -> null
    }

    private fun startCapture(
        zoneId: String,
        region: ActivityRegion,
        minimum: FarmPlotPosition,
        maximum: FarmPlotPosition,
        blocksPerTick: Int,
        maxBlocks: Int,
        reason: String,
        initialPhase: FarmBackupPhase,
        onComplete: (FarmBackupManifest) -> Unit,
        onFailure: (Throwable) -> Unit,
        replace: ActiveJob? = null,
    ): FarmBackupStart {
        if (!isValidZone(zoneId)) return FarmBackupStart.Rejected(FarmBackupRejection.OUTSIDE_ZONE)
        if (active != null && active !== replace) return FarmBackupStart.Busy(requireNotNull(active).status())
        val geometry = BackupGeometry(minimum, maximum)
        if (geometry.volume > maxBlocks) return FarmBackupStart.Rejected(FarmBackupRejection.TOO_LARGE)
        if (minimum.world != region.world.name || maximum.world != region.world.name) {
            return FarmBackupStart.Rejected(FarmBackupRejection.WRONG_WORLD)
        }
        if (!contains(region, minimum) || !contains(region, maximum)) {
            return FarmBackupStart.Rejected(FarmBackupRejection.OUTSIDE_ZONE)
        }
        val id = nextId(reason)
        val job = CaptureJob(
            zoneId,
            region,
            geometry,
            id,
            reason,
            initialPhase,
            blocksPerTick,
            onComplete,
            onFailure,
        )
        active = job
        job.start()
        return FarmBackupStart.Started(job.status())
    }

    private fun startRestore(
        region: ActivityRegion,
        loaded: LoadedBackup,
        blocksPerTick: Int,
        onComplete: (FarmBackupManifest) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        if (closed) return
        val job = RestoreJob(region, loaded, blocksPerTick, onComplete, onFailure)
        active = job
        job.start()
    }

    private inner class CaptureJob(
        private val zoneId: String,
        private val region: ActivityRegion,
        private val geometry: BackupGeometry,
        private val id: String,
        private val reason: String,
        private val initialPhase: FarmBackupPhase,
        private val blocksPerTick: Int,
        private val onComplete: (FarmBackupManifest) -> Unit,
        private val onFailure: (Throwable) -> Unit,
    ) : ActiveJob {
        private val worldEditWorld = BukkitAdapter.adapt(region.world)
        private val clipboard = BlockArrayClipboard(
            CuboidRegion(worldEditWorld, geometry.minimumVector, geometry.maximumVector),
        ).also { it.origin = geometry.minimumVector }
        private var phase = initialPhase
        private var done = 0L
        private var chunkIndex = 0
        private var chunkCursor = 0L
        private var chunk: Chunk? = null
        private var ticket = false
        private var task: ScheduledTask? = null
        private var finished = false

        override fun status() = FarmBackupStatus(zoneId, phase, done, geometry.volume, id)

        fun start() = requestChunk()

        override fun cancel() {
            if (finished) return
            finished = true
            task?.cancel()
            releaseChunk()
        }

        private fun requestChunk() {
            if (finished || closed) return
            if (chunkIndex >= geometry.chunks.size) return write()
            val coordinate = geometry.chunks[chunkIndex]
            region.world.getChunkAtAsync(coordinate.x, coordinate.z, true).whenComplete { loaded, failure ->
                onMain {
                    if (finished || closed) return@onMain
                    if (failure != null || loaded == null) return@onMain fail(failure ?: error("Chunk did not load"))
                    chunk = loaded
                    ticket = loaded.addPluginChunkTicket(plugin)
                    chunkCursor = 0
                    copyStep()
                }
            }
        }

        private fun copyStep() {
            if (finished) return
            val current = chunk ?: return fail(IllegalStateException("Backup lost its current chunk"))
            val slice = geometry.slice(current.x, current.z)
            val end = minOf(slice.volume, chunkCursor + blocksPerTick)
            while (chunkCursor < end) {
                val position = slice.vectorAt(chunkCursor++)
                val location = Location(region.world, position.x().toDouble(), position.y().toDouble(), position.z().toDouble())
                if (!region.contains(location)) return fail(IllegalArgumentException("WorldEdit selection leaves the farm region"))
                clipboard.setBlock(position, worldEditWorld.getFullBlock(position))
                done++
            }
            if (chunkCursor < slice.volume) {
                task = Tasks.scheduler.runLater(1L, ::copyStep)
                return
            }
            releaseChunk()
            chunkIndex++
            task = Tasks.scheduler.runLater(1L, ::requestChunk)
        }

        private fun write() {
            if (finished) return
            phase = FarmBackupPhase.WRITING
            executor.execute {
                runCatching { writeBackup(zoneId, id, reason, geometry, clipboard) }.fold(
                    onSuccess = { manifest -> onMain {
                        if (finished || closed) return@onMain
                        finished = true
                        if (active === this) active = null
                        onComplete(manifest)
                    } },
                    onFailure = { failure -> onMain { fail(failure) } },
                )
            }
        }

        private fun fail(failure: Throwable) {
            if (finished) return
            finished = true
            task?.cancel()
            releaseChunk()
            if (active === this) active = null
            plugin.logger.log(Level.SEVERE, "Farm backup capture failed for $zoneId", failure)
            onFailure(failure)
        }

        private fun releaseChunk() {
            val current = chunk ?: return
            if (ticket) current.removePluginChunkTicket(plugin)
            chunk = null
            ticket = false
        }
    }

    private inner class RestoreJob(
        private val region: ActivityRegion,
        private val loaded: LoadedBackup,
        private val blocksPerTick: Int,
        private val onComplete: (FarmBackupManifest) -> Unit,
        private val onFailure: (Throwable) -> Unit,
    ) : ActiveJob {
        private val geometry = BackupGeometry(loaded.manifest.minimum(), loaded.manifest.maximum())
        private val worldEditWorld = BukkitAdapter.adapt(region.world)
        private val sourceMinimum = loaded.clipboard.minimumPoint
        private var done = 0L
        private var chunkIndex = 0
        private var chunkCursor = 0L
        private var chunk: Chunk? = null
        private var ticket = false
        private var task: ScheduledTask? = null
        private var finished = false

        override fun status() = FarmBackupStatus(
            loaded.manifest.zoneId,
            FarmBackupPhase.RESTORING,
            done,
            geometry.volume,
            loaded.manifest.id,
        )

        fun start() = requestChunk()

        override fun cancel() {
            if (finished) return
            finished = true
            task?.cancel()
            releaseChunk()
        }

        private fun requestChunk() {
            if (finished || closed) return
            if (chunkIndex >= geometry.chunks.size) return complete()
            val coordinate = geometry.chunks[chunkIndex]
            region.world.getChunkAtAsync(coordinate.x, coordinate.z, true).whenComplete { loadedChunk, failure ->
                onMain {
                    if (finished || closed) return@onMain
                    if (failure != null || loadedChunk == null) return@onMain fail(failure ?: error("Chunk did not load"))
                    chunk = loadedChunk
                    ticket = loadedChunk.addPluginChunkTicket(plugin)
                    chunkCursor = 0
                    restoreStep()
                }
            }
        }

        private fun restoreStep() {
            if (finished) return
            val current = chunk ?: return fail(IllegalStateException("Farm restore lost its current chunk"))
            val slice = geometry.slice(current.x, current.z)
            val end = minOf(slice.volume, chunkCursor + blocksPerTick)
            while (chunkCursor < end) {
                val target = slice.vectorAt(chunkCursor++)
                val source = sourceMinimum.add(
                    target.x() - geometry.minimum.x,
                    target.y() - geometry.minimum.y,
                    target.z() - geometry.minimum.z,
                )
                worldEditWorld.setBlock(target, loaded.clipboard.getFullBlock(source), SideEffectSet.defaults())
                done++
            }
            if (chunkCursor < slice.volume) {
                task = Tasks.scheduler.runLater(1L, ::restoreStep)
                return
            }
            releaseChunk()
            chunkIndex++
            task = Tasks.scheduler.runLater(1L, ::requestChunk)
        }

        private fun complete() {
            if (finished) return
            finished = true
            if (active === this) active = null
            onComplete(loaded.manifest)
        }

        private fun fail(failure: Throwable) {
            if (finished) return
            finished = true
            task?.cancel()
            releaseChunk()
            if (active === this) active = null
            plugin.logger.log(Level.SEVERE, "Farm backup restore failed for ${loaded.manifest.zoneId}", failure)
            onFailure(failure)
        }

        private fun releaseChunk() {
            val current = chunk ?: return
            if (ticket) current.removePluginChunkTicket(plugin)
            chunk = null
            ticket = false
        }
    }

    private fun writeBackup(
        zoneId: String,
        id: String,
        reason: String,
        geometry: BackupGeometry,
        clipboard: Clipboard,
    ): FarmBackupManifest {
        val directory = zoneDirectory(zoneId)
        Files.createDirectories(directory)
        val schematic = schematicPath(zoneId, id)
        val manifestPath = manifestPath(zoneId, id)
        val schematicPart = schematic.resolveSibling("${schematic.fileName}.part")
        val manifestPart = manifestPath.resolveSibling("${manifestPath.fileName}.part")
        try {
            Files.newOutputStream(schematicPart, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).buffered().use { output ->
                BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(output).use { it.write(clipboard) }
            }
            val hash = sha256(schematicPart)
            val manifest = FarmBackupManifest(
                schemaVersion = SCHEMA_VERSION,
                id = id,
                zoneId = zoneId,
                world = geometry.minimum.world,
                minX = geometry.minimum.x,
                minY = geometry.minimum.y,
                minZ = geometry.minimum.z,
                maxX = geometry.maximum.x,
                maxY = geometry.maximum.y,
                maxZ = geometry.maximum.z,
                volume = geometry.volume,
                createdAt = clock(),
                reason = reason,
                sha256 = hash,
            )
            Files.writeString(
                manifestPart,
                gson.toJson(manifest),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            moveNew(schematicPart, schematic)
            moveNew(manifestPart, manifestPath)
            return manifest
        } finally {
            Files.deleteIfExists(schematicPart)
            Files.deleteIfExists(manifestPart)
        }
    }

    private fun readBackup(zoneId: String, id: String, world: World, maxBlocks: Int): LoadedBackup {
        val manifest = readManifest(manifestPath(zoneId, id))
        require(manifest.id == id && manifest.zoneId == zoneId && manifest.world == world.name) { "Farm backup target mismatch" }
        val geometry = BackupGeometry(manifest.minimum(), manifest.maximum())
        require(geometry.volume == manifest.volume && geometry.volume <= maxBlocks) { "Farm backup bounds are invalid" }
        val schematic = schematicPath(zoneId, id)
        require(Files.isRegularFile(schematic) && sha256(schematic) == manifest.sha256) { "Farm backup hash mismatch" }
        val format = ClipboardFormats.findByFile(schematic.toFile()) ?: error("Unknown farm backup format")
        val clipboard = Files.newInputStream(schematic).buffered().use { input -> format.getReader(input).use { it.read() } }
        require(clipboard.dimensions == geometry.dimensions) { "Farm backup dimensions do not match its manifest" }
        return LoadedBackup(manifest, clipboard)
    }

    private fun readManifest(path: Path): FarmBackupManifest {
        require(Files.isRegularFile(path)) { "Farm backup manifest does not exist" }
        val raw = Files.readString(path, StandardCharsets.UTF_8)
        require(raw.length <= MAX_MANIFEST_BYTES) { "Farm backup manifest is too large" }
        return requireNotNull(gson.fromJson(raw, FarmBackupManifest::class.java)) { "Farm backup manifest is empty" }.also {
            require(it.schemaVersion == SCHEMA_VERSION) { "Unsupported farm backup schema" }
            require(isValidId(it.id) && isValidZone(it.zoneId)) { "Farm backup identity is invalid" }
            require(it.world.matches(WORLD_PATTERN)) { "Farm backup world is invalid" }
            require(it.reason in BACKUP_REASONS) { "Farm backup reason is invalid" }
            require(it.createdAt >= 0L) { "Farm backup timestamp is invalid" }
            require(it.sha256.matches(SHA256_PATTERN)) { "Farm backup hash is invalid" }
            val geometry = BackupGeometry(it.minimum(), it.maximum())
            require(geometry.volume == it.volume) { "Farm backup volume does not match its bounds" }
        }
    }

    private fun failPending(pending: ActiveJob, callback: (Throwable) -> Unit, failure: Throwable) {
        if (active !== pending) return
        active = null
        plugin.logger.log(Level.SEVERE, "Farm backup operation failed", failure)
        callback(failure)
    }

    private fun nextId(reason: String): String {
        val timestamp = ID_TIME_FORMAT.format(Instant.ofEpochMilli(clock()))
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "${timestamp}_${reason}_$suffix"
    }

    private fun zoneDirectory(zoneId: String): Path {
        require(isValidZone(zoneId)) { "Invalid farm zone id" }
        return root.resolve(zoneId)
    }

    private fun schematicPath(zoneId: String, id: String): Path {
        require(isValidId(id)) { "Invalid farm backup id" }
        return zoneDirectory(zoneId).resolve("$id.schem")
    }

    private fun manifestPath(zoneId: String, id: String): Path {
        require(isValidId(id)) { "Invalid farm backup id" }
        return zoneDirectory(zoneId).resolve("$id$MANIFEST_SUFFIX")
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveNew(source: Path, target: Path) {
        require(!Files.exists(target)) { "Farm backup already exists: ${target.fileName}" }
        runCatching {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source, target)
        }
    }

    private fun contains(region: ActivityRegion, position: FarmPlotPosition): Boolean =
        position.world == region.world.name && region.contains(
            Location(region.world, position.x.toDouble(), position.y.toDouble(), position.z.toDouble()),
        )

    private fun onMain(block: () -> Unit) {
        if (closed) return
        Tasks.scheduler.runSync(Runnable(block))
    }

    private interface ActiveJob {
        fun status(): FarmBackupStatus
        fun cancel()
    }

    private class PendingJob(private val value: FarmBackupStatus) : ActiveJob {
        override fun status() = value
        override fun cancel() = Unit
    }

    private data class LoadedBackup(val manifest: FarmBackupManifest, val clipboard: Clipboard)

    private data class ChunkCoordinate(val x: Int, val z: Int)

    private data class BackupGeometry(val minimum: FarmPlotPosition, val maximum: FarmPlotPosition) {
        val volume = (maximum.x - minimum.x + 1L) *
            (maximum.y - minimum.y + 1L) *
            (maximum.z - minimum.z + 1L)
        val minimumVector: BlockVector3 = BlockVector3.at(minimum.x, minimum.y, minimum.z)
        val maximumVector: BlockVector3 = BlockVector3.at(maximum.x, maximum.y, maximum.z)
        val dimensions: BlockVector3 = BlockVector3.at(
            maximum.x - minimum.x + 1,
            maximum.y - minimum.y + 1,
            maximum.z - minimum.z + 1,
        )
        val chunks: List<ChunkCoordinate> = buildList {
            for (chunkZ in (minimum.z shr 4)..(maximum.z shr 4)) {
                for (chunkX in (minimum.x shr 4)..(maximum.x shr 4)) add(ChunkCoordinate(chunkX, chunkZ))
            }
        }

        init {
            require(minimum.world == maximum.world) { "Farm backup worlds do not match" }
            require(minimum.x <= maximum.x && minimum.y <= maximum.y && minimum.z <= maximum.z) { "Farm backup bounds are inverted" }
            require(volume in 1..MAX_ABSOLUTE_BLOCKS) { "Farm backup volume is invalid" }
        }

        fun slice(chunkX: Int, chunkZ: Int): BackupSlice = BackupSlice(
            minX = maxOf(minimum.x, chunkX shl 4),
            maxX = minOf(maximum.x, (chunkX shl 4) + 15),
            minY = minimum.y,
            maxY = maximum.y,
            minZ = maxOf(minimum.z, chunkZ shl 4),
            maxZ = minOf(maximum.z, (chunkZ shl 4) + 15),
        )
    }

    private data class BackupSlice(
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val minZ: Int,
        val maxZ: Int,
    ) {
        private val width = maxX - minX + 1
        private val depth = maxZ - minZ + 1
        val volume = width.toLong() * depth * (maxY - minY + 1)

        fun vectorAt(cursor: Long): BlockVector3 {
            val layer = width.toLong() * depth
            val y = minY + (cursor / layer).toInt()
            val withinLayer = (cursor % layer).toInt()
            val z = minZ + withinLayer / width
            val x = minX + withinLayer % width
            return BlockVector3.at(x, y, z)
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_ABSOLUTE_BLOCKS = 4_000_000L
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_LISTED_BACKUPS = 50
        private const val MANIFEST_SUFFIX = ".manifest.json"
        private val ID_PATTERN = Regex("[0-9]{8}-[0-9]{6}-[0-9]{3}_[a-z_]+_[0-9a-z]+")
        private val ZONE_PATTERN = Regex("[a-z0-9_-]{1,48}")
        private val WORLD_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private val BACKUP_REASONS = setOf("manual", "pre_restore")
        private val ID_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

        internal fun isValidId(value: String): Boolean = value.matches(ID_PATTERN)
        internal fun isValidZone(value: String): Boolean = value.matches(ZONE_PATTERN)
    }
}
