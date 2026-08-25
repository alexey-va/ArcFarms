package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.persistence.FixedFarmCropJournal
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Level

internal data class FarmBackupAdminContext(
    val zoneId: String,
    val region: ActivityRegion,
    val idle: Boolean,
    val reindexDefinition: FarmBlockIndexDefinition,
    val backupBlocksPerTick: Int,
    val backupMaxBlocks: Int,
    val paused: Boolean,
    val pause: () -> Unit,
    val resume: () -> Unit,
    val clearAdminModes: () -> Unit,
)

/** Admin adapter around the bounded backup owner and post-restore topology rebuild. */
internal class FarmBackupAdminController(
    private val plugin: Plugin,
    dataRoot: Path,
    private val registry: FarmBlockRegistry,
    private val fixedCropJournal: FixedFarmCropJournal,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val message: (Player, MessageKey, Map<String, Component>) -> Unit,
    clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val backups = FarmWorldBackupManager(plugin, dataRoot.resolve("data/farm-backups"), clock)

    fun busy(): Boolean = backups.activeStatus() != null

    fun save(player: Player, context: FarmBackupAdminContext): Boolean {
        if (!allowed(player, context)) return false
        return handleStart(
            player,
            backups.save(
                zoneId = context.zoneId,
                region = context.region,
                blocksPerTick = context.backupBlocksPerTick,
                maxBlocks = context.backupMaxBlocks,
                onComplete = { manifest ->
                    online(player.uniqueId)?.let { target ->
                        send(
                            target,
                            MessageKey.ADMIN_BACKUP_SAVED,
                            mapOf("id" to locale.text(manifest.id), "blocks" to locale.text(manifest.volume)),
                        )
                    }
                    debug.event(
                        "farm_backup_saved",
                        "zone" to context.zoneId,
                        "id" to manifest.id,
                        "blocks" to manifest.volume,
                    )
                },
                onFailure = { failed(player.uniqueId, context, it, pause = false) },
            ),
        )
    }

    fun restore(player: Player, context: FarmBackupAdminContext, backupId: String): Boolean {
        if (!allowed(player, context)) return false
        val resumeWhenReady = !context.paused
        val start = backups.restore(
            zoneId = context.zoneId,
            region = context.region,
            backupId = backupId,
            blocksPerTick = context.backupBlocksPerTick,
            maxBlocks = context.backupMaxBlocks,
            onSafetyBackup = { manifest ->
                online(player.uniqueId)?.let { target ->
                    send(target, MessageKey.ADMIN_BACKUP_SAFETY_SAVED, mapOf("id" to locale.text(manifest.id)))
                }
            },
            onComplete = { manifest ->
                debug.event(
                    "farm_backup_restored",
                    "zone" to context.zoneId,
                    "id" to manifest.id,
                    "blocks" to manifest.volume,
                )
                startReindex(context, player.uniqueId, resumeWhenReady)
                online(player.uniqueId)?.let { target ->
                    send(target, MessageKey.ADMIN_BACKUP_RESTORED, mapOf("id" to locale.text(manifest.id)))
                }
            },
            onFailure = { failed(player.uniqueId, context, it, pause = true) },
        )
        val started = handleStart(player, start)
        if (started) {
            context.pause()
            context.clearAdminModes()
        }
        return started
    }

    fun list(player: Player, context: FarmBackupAdminContext): Boolean {
        val playerId = player.uniqueId
        backups.list(
            context.zoneId,
            onComplete = { manifests ->
                val target = online(playerId) ?: return@list
                if (manifests.isEmpty()) {
                    send(target, MessageKey.ADMIN_BACKUP_LIST_EMPTY)
                } else {
                    send(
                        target,
                        MessageKey.ADMIN_BACKUP_LIST_HEADER,
                        mapOf("zone" to locale.text(context.zoneId)),
                    )
                    manifests.forEach { manifest ->
                        send(
                            target,
                            MessageKey.ADMIN_BACKUP_LIST_ENTRY,
                            mapOf(
                                "id" to locale.text(manifest.id),
                                "blocks" to locale.text(manifest.volume),
                                "reason" to locale.renderPath("admin.backup-reason.${manifest.reason}", target),
                            ),
                        )
                    }
                }
            },
            onFailure = { failed(playerId, context, it, pause = false) },
        )
        return true
    }

    fun status(player: Player, context: FarmBackupAdminContext): Boolean {
        val status = backups.status(context.zoneId) ?: run {
            send(player, MessageKey.ADMIN_BACKUP_IDLE)
            return false
        }
        send(
            player,
            MessageKey.ADMIN_BACKUP_STATUS,
            mapOf(
                "phase" to locale.renderPath("admin.backup-phase.${status.phase.name.lowercase()}", player),
                "done" to locale.text(status.done),
                "total" to locale.text(status.total),
                "id" to locale.text(status.backupId ?: "—"),
            ),
        )
        return true
    }

    override fun close() = backups.close()

    private fun allowed(player: Player, context: FarmBackupAdminContext): Boolean {
        if (!context.idle || registry.isReindexing(context.zoneId)) {
            send(player, MessageKey.ADMIN_BACKUP_ACTIVE_SHIFT)
            return false
        }
        val pending = fixedCropJournal.records().count { it.zoneId == context.zoneId }
        if (pending > 0) {
            send(player, MessageKey.ADMIN_BLOCKRESET_PENDING, mapOf("count" to locale.text(pending)))
            return false
        }
        return true
    }

    private fun handleStart(player: Player, start: FarmBackupStart): Boolean = when (start) {
        is FarmBackupStart.Started -> {
            send(
                player,
                MessageKey.ADMIN_BACKUP_STARTED,
                mapOf(
                    "phase" to locale.renderPath("admin.backup-phase.${start.status.phase.name.lowercase()}", player),
                    "blocks" to locale.text(start.status.total),
                ),
            )
            true
        }
        is FarmBackupStart.Busy -> {
            send(player, MessageKey.ADMIN_BACKUP_BUSY, mapOf("zone" to locale.text(start.status.zoneId)))
            false
        }
        is FarmBackupStart.Rejected -> {
            send(
                player,
                MessageKey.ADMIN_BACKUP_REJECTED,
                mapOf("reason" to locale.renderPath("admin.backup-rejection.${start.reason.name.lowercase()}", player)),
            )
            false
        }
    }

    private fun startReindex(context: FarmBackupAdminContext, playerId: UUID, resumeWhenReady: Boolean) {
        when (val start = registry.startReindex(
            definition = context.reindexDefinition,
            onProgress = { status ->
                debug.event(
                    "farm_backup_reindex_progress",
                    "zone" to context.zoneId,
                    "phase" to status.phase,
                    "done" to if (status.phase == FarmBlockReindexPhase.SCANNING) {
                        status.scannedBlocks
                    } else {
                        status.appliedChunks
                    },
                )
            },
            onComplete = { completed ->
                if (resumeWhenReady) context.resume()
                online(playerId)?.let { target ->
                    send(
                        target,
                        MessageKey.ADMIN_BACKUP_REINDEXED,
                        mapOf(
                            "beds" to locale.text(completed.status.beds),
                            "crops" to locale.text(completed.status.fixedCrops),
                        ),
                    )
                }
            },
            onFailure = { failure -> failed(playerId, context, failure, pause = true) },
        )) {
            is FarmBlockReindexStart.Started -> Unit
            is FarmBlockReindexStart.Busy -> failed(
                playerId,
                context,
                IllegalStateException("Farm reindex unexpectedly busy"),
                pause = true,
            )
            is FarmBlockReindexStart.TooLarge -> failed(
                playerId,
                context,
                IllegalStateException("Farm region exceeds block reindex limit: ${start.blocks}/${start.maxBlocks}"),
                pause = true,
            )
        }
    }

    private fun failed(playerId: UUID, context: FarmBackupAdminContext, failure: Throwable, pause: Boolean) {
        if (pause) context.pause()
        plugin.logger.log(Level.SEVERE, "Farm backup operation failed for ${context.zoneId}", failure)
        online(playerId)?.let { send(it, MessageKey.ADMIN_BACKUP_FAILED) }
        debug.event(
            "farm_backup_failed",
            "zone" to context.zoneId,
            "failure" to failure.javaClass.simpleName,
            "paused" to pause,
        )
    }

    private fun online(playerId: UUID): Player? = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)

    private fun send(player: Player, key: MessageKey, values: Map<String, Component> = emptyMap()) {
        message(player, key, values)
    }
}
