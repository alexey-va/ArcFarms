package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.persistence.FixedFarmCropJournal
import java.util.logging.Level

/** Admin-facing orchestration for the bounded farm topology rebuild. */
internal class FarmBlockAdminController(
    private val plugin: Plugin,
    private val registry: FarmBlockRegistry,
    private val fixedCropJournal: FixedFarmCropJournal,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val message: (Player, MessageKey, Map<String, Component>) -> Unit,
) {
    fun start(
        player: Player,
        zoneId: String,
        idle: Boolean,
        definition: FarmBlockIndexDefinition,
        beforeStart: () -> Unit,
    ): Boolean {
        registry.status(zoneId)?.let { status ->
            sendStatus(player, status)
            return false
        }
        if (!idle) {
            send(player, MessageKey.ADMIN_BLOCKRESET_ACTIVE)
            return false
        }
        val pendingFixedCrops = fixedCropJournal.records().count { it.zoneId == zoneId }
        if (pendingFixedCrops > 0) {
            send(
                player,
                MessageKey.ADMIN_BLOCKRESET_PENDING,
                mapOf("count" to locale.text(pendingFixedCrops)),
            )
            return false
        }
        val playerId = player.uniqueId
        val start = runCatching {
            registry.startReindex(
                definition = definition,
                onProgress = { status ->
                    debug.event(
                        "farm_block_reindex_progress",
                        "zone" to zoneId,
                        "phase" to status.phase,
                        "scanned" to status.scannedBlocks,
                        "total" to status.totalBlocks,
                        "applied_chunks" to status.appliedChunks,
                        "chunks" to status.totalChunks,
                    )
                },
                onComplete = { completed ->
                    Bukkit.getPlayer(playerId)?.let { online ->
                        send(
                            online,
                            MessageKey.ADMIN_BLOCKRESET_COMPLETED,
                            mapOf(
                                "beds" to locale.text(completed.status.beds),
                                "crops" to locale.text(completed.status.fixedCrops),
                                "leaves" to locale.text(completed.status.orchardLeaves),
                                "seconds" to locale.text((completed.durationMillis / 1_000L).coerceAtLeast(1L)),
                            ),
                        )
                    }
                    debug.event(
                        "farm_block_reindex_completed",
                        "zone" to zoneId,
                        "beds" to completed.status.beds,
                        "fixed_crops" to completed.status.fixedCrops,
                        "orchard_leaves" to completed.status.orchardLeaves,
                        "duration_ms" to completed.durationMillis,
                    )
                },
                onFailure = { failure ->
                    plugin.logger.log(Level.SEVERE, "Farm block reindex failed for $zoneId", failure)
                    Bukkit.getPlayer(playerId)?.let { online -> send(online, MessageKey.ADMIN_BLOCKRESET_FAILED) }
                    debug.event("farm_block_reindex_failed", "zone" to zoneId, "failure" to failure.javaClass.simpleName)
                },
            )
        }.getOrElse { failure ->
            plugin.logger.log(Level.SEVERE, "Could not start farm block reindex for $zoneId", failure)
            send(player, MessageKey.ADMIN_BLOCKRESET_FAILED)
            debug.event("farm_block_reindex_start_failed", "zone" to zoneId, "failure" to failure.javaClass.simpleName)
            return false
        }
        return when (start) {
            is FarmBlockReindexStart.Started -> {
                beforeStart()
                send(
                    player,
                    MessageKey.ADMIN_BLOCKRESET_STARTED,
                    mapOf(
                        "blocks" to locale.text(start.status.totalBlocks),
                        "chunks" to locale.text(start.status.totalChunks),
                    ),
                )
                debug.event(
                    "farm_block_reindex_started",
                    "zone" to zoneId,
                    "blocks" to start.status.totalBlocks,
                    "chunks" to start.status.totalChunks,
                )
                true
            }
            is FarmBlockReindexStart.Busy -> {
                sendStatus(player, start.status)
                false
            }
            is FarmBlockReindexStart.TooLarge -> {
                send(
                    player,
                    MessageKey.ADMIN_BLOCKRESET_TOO_LARGE,
                    mapOf(
                        "blocks" to locale.text(start.blocks),
                        "limit" to locale.text(start.maxBlocks),
                        "chunks" to locale.text(start.chunks),
                    ),
                )
                false
            }
        }
    }

    fun status(player: Player, zoneId: String): Boolean {
        val status = registry.status(zoneId) ?: run {
            send(player, MessageKey.ADMIN_BLOCKRESET_IDLE)
            return false
        }
        sendStatus(player, status)
        return true
    }

    private fun sendStatus(player: Player, status: FarmBlockReindexStatus) {
        send(
            player,
            MessageKey.ADMIN_BLOCKRESET_STATUS,
            mapOf(
                "phase" to locale.renderPath("admin.blockreset-phase.${status.phase.name.lowercase()}", player),
                "done" to locale.text(
                    if (status.phase == FarmBlockReindexPhase.SCANNING) status.scannedBlocks else status.appliedChunks,
                ),
                "total" to locale.text(
                    if (status.phase == FarmBlockReindexPhase.SCANNING) status.totalBlocks else status.totalChunks,
                ),
                "beds" to locale.text(status.beds),
                "crops" to locale.text(status.fixedCrops),
                "leaves" to locale.text(status.orchardLeaves),
            ),
        )
    }

    private fun send(player: Player, key: MessageKey, values: Map<String, Component> = emptyMap()) {
        message(player, key, values)
    }
}
