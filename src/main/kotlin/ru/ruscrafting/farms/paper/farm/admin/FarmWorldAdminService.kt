package ru.ruscrafting.farms.paper.farm.admin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmAdminEdit
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.paper.ActivityRegion
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBackupAdminContext
import ru.ruscrafting.farms.paper.FarmBackupAdminController
import ru.ruscrafting.farms.paper.FarmBlockAdminController
import ru.ruscrafting.farms.paper.FarmBlockIndexDefinition
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmFixedCropPosition
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.WorldEditSelectionReader
import ru.ruscrafting.farms.paper.WorldEditSelectionResult
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.care.FarmCareController
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.enterprise.FarmEnterprisePort
import ru.ruscrafting.farms.paper.farm.incident.pest.FarmPestIncident
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController
import ru.ruscrafting.farms.paper.farm.recovery.FarmFixedCropRecoveryController
import ru.ruscrafting.farms.paper.toFarmPlotPosition
import ru.ruscrafting.farms.paper.blockIndexDefinition
import ru.ruscrafting.farms.persistence.FixedFarmCropJournal
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.ceil

/** Owns admin edit/inspect sessions and bulk world maintenance operations. */
internal class FarmWorldAdminService(
    plugin: Plugin,
    dataFolder: Path,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val ledger: FarmBlockLedger,
    private val registry: FarmBlockRegistry,
    private val fixedCrops: FarmFixedCropRecoveryController,
    private val fixedCropJournal: FixedFarmCropJournal,
    private val care: FarmCareController,
    private val pests: FarmPestIncident,
    private val delivery: FarmDeliveryController,
    private val enterprise: FarmEnterprisePort,
    private val special: FarmSpecialIncidentController,
    private val transitions: FarmTransitionSink,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val paused: (String) -> Boolean,
    private val setPaused: (String, Boolean) -> Boolean,
    private val persistAsync: () -> CompletableFuture<Unit>,
    private val clock: () -> Long,
) {
    private val port = audience
    private val editing = mutableSetOf<UUID>()
    private val inspecting = mutableSetOf<UUID>()
    private var inspectRenderTick = 0
    private val blockAdmin = FarmBlockAdminController(plugin, registry, fixedCropJournal, locale, debug, port::sendChat)
    private val backupAdmin = FarmBackupAdminController(
        plugin,
        dataFolder,
        registry,
        fixedCropJournal,
        locale,
        debug,
        port::sendChat,
        clock,
    )

    fun isEditing(player: Player): Boolean = player.uniqueId in editing
    fun isInspecting(player: Player): Boolean = player.uniqueId in inspecting
    fun anyEditing(): Boolean = editing.isNotEmpty()
    fun anotherEditor(player: Player): Boolean = editing.any { it != player.uniqueId }

    fun release(player: Player) {
        editing.remove(player.uniqueId)
        inspecting.remove(player.uniqueId)
    }

    fun stopEditing(player: Player): Boolean {
        if (!editing.remove(player.uniqueId)) return false
        audience.sendActionBar(player, MessageKey.ADMIN_EDIT_DISABLED)
        return true
    }

    fun toggleEdit(player: Player): Boolean? {
        if (editing.remove(player.uniqueId)) {
            debug.event("farm_admin_edit", "player" to player.name, "enabled" to false)
            audience.sendActionBar(player, MessageKey.ADMIN_EDIT_DISABLED)
            return false
        }
        if (inspecting.remove(player.uniqueId)) audience.sendActionBar(player, MessageKey.ADMIN_INSPECT_DISABLED)
        editing += player.uniqueId
        debug.event("farm_admin_edit", "player" to player.name, "enabled" to true)
        audience.sendActionBar(player, MessageKey.ADMIN_EDIT_ENABLED)
        return true
    }

    fun toggleInspect(player: Player): Boolean {
        if (inspecting.remove(player.uniqueId)) {
            debug.event("farm_admin_inspect", "player" to player.name, "enabled" to false)
            audience.sendActionBar(player, MessageKey.ADMIN_INSPECT_DISABLED)
            return false
        }
        if (editing.remove(player.uniqueId)) audience.sendActionBar(player, MessageKey.ADMIN_EDIT_DISABLED)
        inspecting += player.uniqueId
        debug.event("farm_admin_inspect", "player" to player.name, "enabled" to true)
        audience.sendActionBar(player, MessageKey.ADMIN_INSPECT_ENABLED)
        return true
    }

    fun renderInspectViews() {
        inspectRenderTick++
        if (inspectRenderTick % 4 != 0) return
        inspecting.mapNotNull(Bukkit::getPlayer).filter(Player::isOnline).forEach { player ->
            val runtime = runtimes().firstOrNull { it.region.contains(player.location) } ?: return@forEach
            val state = runtime.state
            val damaged = buildSet {
                addAll(state.droughtPlots)
                addAll(state.droughtDamagedPlots)
                state.pestDamagedCrops.mapTo(this) { it.position }
                state.diseaseDamagedCrops.orEmpty().mapTo(this) { it.position }
                state.specialDamagedCrops.mapTo(this) { it.position }
            }
            val special = state.specialIncident?.plots.orEmpty().toSet()
            val radiusSquared = INSPECT_RADIUS * INSPECT_RADIUS
            registry.beds(runtime.settings.id).asSequence()
                .filter { plot ->
                    val dx = plot.x + 0.5 - player.location.x
                    val dz = plot.z + 0.5 - player.location.z
                    dx * dx + dz * dz <= radiusSquared
                }
                .sortedBy { plot ->
                    val dx = plot.x + 0.5 - player.location.x
                    val dz = plot.z + 0.5 - player.location.z
                    dx * dx + dz * dz
                }
                .take(MAX_INSPECT_PARTICLES)
                .forEach { plot ->
                    val color = when (plot) {
                        in damaged -> DAMAGE_COLOR
                        in special -> SPECIAL_COLOR
                        in state.preparationPatch -> PATCH_COLOR
                        else -> INDEX_COLOR
                    }
                    player.spawnParticle(
                        Particle.DUST,
                        org.bukkit.Location(player.world, plot.x + 0.5, plot.y + 1.35, plot.z + 0.5),
                        1, 0.0, 0.0, 0.0, 0.0,
                        Particle.DustOptions(color, 0.75f),
                    )
                }
            audience.sendActionBar(player, MessageKey.ADMIN_INSPECT_LEGEND)
        }
    }

    fun startBlockReset(player: Player, zoneId: String): Boolean {
        val runtime = runtime(zoneId, player) ?: return false
        return blockAdmin.start(player, zoneId, runtime.state.phase == FarmPhase.IDLE, definition(runtime)) {
            release(player)
        }
    }

    fun blockResetStatus(player: Player, zoneId: String): Boolean {
        runtime(zoneId, player) ?: return false
        return blockAdmin.status(player, zoneId)
    }

    fun saveBackup(player: Player, zoneId: String): Boolean =
        backupContext(player, zoneId)?.let { backupAdmin.save(player, it) } ?: false

    fun restoreBackup(player: Player, zoneId: String, backupId: String): Boolean =
        backupContext(player, zoneId)?.let { backupAdmin.restore(player, it, backupId) } ?: false

    fun listBackups(player: Player, zoneId: String): Boolean =
        backupContext(player, zoneId)?.let { backupAdmin.list(player, it) } ?: false

    fun backupStatus(player: Player, zoneId: String): Boolean =
        backupContext(player, zoneId)?.let { backupAdmin.status(player, it) } ?: false

    fun backupBusy(): Boolean = backupAdmin.busy()

    fun close() = backupAdmin.close()

    fun inspect(player: Player, block: Block) {
        val cooldown = "farm-admin-inspect:${player.uniqueId}:${block.world.name}:${block.x}:${block.y}:${block.z}"
        if (!access.allowInteraction(cooldown, 250L)) return
        val runtime = farmAt(block)
        val fixedBlock = when {
            ledger.fixedCropRecord(block) != null -> block
            ledger.fixedCropRecord(block.getRelative(org.bukkit.block.BlockFace.UP)) != null ->
                block.getRelative(org.bukkit.block.BlockFace.UP)
            else -> null
        }
        val fixed = fixedBlock?.let(ledger::fixedCropRecord)
        val soil = when {
            ledger.record(block) != null -> block
            ledger.record(block.getRelative(org.bukkit.block.BlockFace.DOWN)) != null ->
                block.getRelative(org.bukkit.block.BlockFace.DOWN)
            else -> null
        }
        val plotRecord = soil?.let(ledger::record)
        val orchardRecord = ledger.orchardLeafRecord(block)
        val position = soil?.toFarmPlotPosition() ?: block.toFarmPlotPosition()
        val state = runtime?.state
        val tracking = buildList {
            if (runtime != null && position in registry.beds(runtime.settings.id)) add("managed")
            if (state != null && position in state.preparationPatch) add("patch")
            if (state != null && position in state.droughtPlots) add("drought")
            if (state != null && position in state.droughtDamagedPlots) add("drought-damaged")
            if (state != null && state.pestNests.any { it.position == position }) add("pest-nest")
            if (state != null && state.pestDamagedCrops.any { it.position == position }) add("pest-damaged")
            if (state != null && position in state.specialIncident?.plots.orEmpty()) add("special-target")
            if (state != null && state.specialDamagedCrops.any { it.position == position }) add("special-damaged")
            if (state != null && state.diseaseDamagedCrops.orEmpty().any { it.position == position }) add("disease-damaged")
            if (runtime != null && special.ownsGiantBlock(runtime, block)) add("giant-crop")
            if (orchardRecord != null) add("orchard")
        }
        port.sendChat(
            player,
            MessageKey.ADMIN_INSPECT_HEADER,
            mapOf(
                "world" to locale.text(block.world.name),
                "x" to locale.text(block.x),
                "y" to locale.text(block.y),
                "z" to locale.text(block.z),
            ),
        )
        port.sendChat(
            player,
            MessageKey.ADMIN_INSPECT_BLOCK,
            mapOf(
                "material" to locale.text(block.type.name),
                "data" to locale.text(block.blockData.asString.take(256)),
                "zone" to locale.text(runtime?.settings?.id ?: "—"),
            ),
        )
        fixed?.let { record ->
            val restore = record.restoreAt?.let { timestamp ->
                locale.renderPath(
                    "admin-inspect.restore.pending",
                    player,
                    mapOf("seconds" to locale.text(remainingSeconds(timestamp, clock()))),
                )
            } ?: locale.renderPath("admin-inspect.restore.ready", player)
            port.sendChat(
                player,
                MessageKey.ADMIN_INSPECT_FIXED,
                mapOf(
                    "zone" to locale.text(record.zoneId),
                    "original" to locale.text(record.originalBlockData.take(256)),
                    "restore" to restore,
                    "x" to locale.text(record.x),
                    "y" to locale.text(record.y),
                    "z" to locale.text(record.z),
                ),
            )
        }
        plotRecord?.let { record ->
            port.sendChat(
                player,
                MessageKey.ADMIN_INSPECT_PLOT,
                mapOf(
                    "zone" to locale.text(record.zoneId),
                    "soil" to locale.text(record.originalSoilData.take(256)),
                    "original" to locale.text(record.originalCropData?.take(256) ?: "—"),
                    "active" to locale.text(record.activeCropData?.take(256) ?: "—"),
                ),
            )
        }
        if (tracking.isNotEmpty()) {
            val rendered = tracking.map { kind ->
                Component.text("  • ").append(locale.renderPath("admin-inspect.tracking-kind.$kind", player))
            }
            port.sendChat(
                player,
                MessageKey.ADMIN_INSPECT_TRACKING,
                mapOf("tracking" to Component.join(JoinConfiguration.separator(Component.newline()), rendered)),
            )
        }
        if (fixed == null && plotRecord == null && orchardRecord == null && tracking.isEmpty()) {
            port.sendChat(player, MessageKey.ADMIN_INSPECT_NONE)
        }
        debug.event(
            "farm_admin_block_inspected",
            "player" to player.name,
            "zone" to runtime?.settings?.id,
            "block" to block.type,
            "fixed_record" to (fixed != null),
            "plot_record" to (plotRecord != null),
            "orchard_record" to (orchardRecord != null),
            "tracking" to tracking.joinToString(","),
            "x" to block.x,
            "y" to block.y,
            "z" to block.z,
        )
    }

    fun unmanageSelection(player: Player, zoneId: String): Boolean {
        val runtime = runtime(zoneId, player) ?: return false
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
            port.sendChat(player, MessageKey.ADMIN_UNMANAGE_WORLD_EDIT_REQUIRED)
            return false
        }
        val selection = when (val result = WorldEditSelectionReader.current(player)) {
            WorldEditSelectionResult.PluginUnavailable -> {
                port.sendChat(player, MessageKey.ADMIN_UNMANAGE_WORLD_EDIT_REQUIRED)
                return false
            }
            WorldEditSelectionResult.Incomplete -> {
                port.sendChat(player, MessageKey.ADMIN_UNMANAGE_SELECTION_REQUIRED)
                return false
            }
            is WorldEditSelectionResult.Available -> result.selection
        }
        if (selection.world != runtime.region.world.name) {
            port.sendChat(
                player,
                MessageKey.ADMIN_UNMANAGE_WRONG_WORLD,
                mapOf("world" to locale.text(selection.world), "expected" to locale.text(runtime.region.world.name)),
            )
            return false
        }
        val tracked = linkedSetOf<FarmPlotPosition>().apply {
            addAll(registry.beds(runtime.settings.id))
            addAll(runtime.state.preparationPatch)
            addAll(runtime.state.droughtPlots)
            addAll(runtime.state.droughtDamagedPlots)
            runtime.state.pestNests.mapTo(this) { it.position }
            runtime.state.pestDamagedCrops.mapTo(this) { it.position }
            runtime.state.diseaseDamagedCrops.orEmpty().mapTo(this) { it.position }
            runtime.state.specialIncident?.plots?.let(::addAll)
            runtime.state.specialDamagedCrops.mapTo(this) { it.position }
        }
        val selected = tracked.filterTo(linkedSetOf(), selection::contains)
        val selectedFixed = linkedSetOf<FarmFixedCropPosition>()
        val selectedLeaves = registry.orchardLeaves(runtime.settings.id).filterTo(linkedSetOf(), selection::contains)
        runtime.region.world.loadedChunks.asSequence()
            .flatMap { ledger.fixedCropRecords(it).asSequence() }
            .filter { it.zoneId == runtime.settings.id }
            .filter { selection.contains(FarmPlotPosition(selection.world, it.x, it.y, it.z)) }
            .mapTo(selectedFixed) { FarmFixedCropPosition(selection.world, it.x, it.y, it.z) }
        fixedCrops.records().asSequence()
            .filter { it.zoneId == runtime.settings.id && it.world == selection.world }
            .filter { selection.contains(FarmPlotPosition(it.world, it.x, it.y, it.z)) }
            .mapTo(selectedFixed) { FarmFixedCropPosition(it.world, it.x, it.y, it.z) }
        if (selected.isEmpty() && selectedFixed.isEmpty() && selectedLeaves.isEmpty()) {
            port.sendChat(player, MessageKey.ADMIN_UNMANAGE_EMPTY)
            return false
        }
        val previous = runtime.state
        val removal = FarmAdminEdit.removePlots(previous, selected, runtime.settings.fieldCompletionPercent)
        runtime.state = removal.state
        if (removal.shiftRetired) enterprise.orderCancelled(runtime.settings.id, previous.sequence)
        persistAsync()
        var removedRecords = 0
        selected.forEach { position ->
            val world = Bukkit.getWorld(position.world) ?: return@forEach
            world.getChunkAt(position.x shr 4, position.z shr 4)
            if (ledger.remove(world.getBlockAt(position.x, position.y, position.z))) removedRecords++
        }
        selectedFixed.forEach { position ->
            val block = runtime.region.world.getBlockAt(position.x, position.y, position.z)
            if (ledger.removeFixedCrop(block)) removedRecords++
            if (fixedCrops.contains(block.location)) {
                fixedCrops.retire(block.location, "admin_worldedit_unmanage")
                removedRecords++
            }
        }
        selectedLeaves.forEach { position ->
            if (ledger.removeOrchardLeaf(runtime.region.world.getBlockAt(position.x, position.y, position.z))) removedRecords++
        }
        registry.removeBeds(runtime.settings.id, selected)
        registry.removeOrchardLeaves(runtime.settings.id, selectedLeaves)
        removal.careTargetIds.forEach { care.removeTarget(runtime.settings.id, it, "admin_worldedit_unmanage") }
        selected.forEach { pests.removeNestAt(runtime, it, "admin_worldedit_unmanage") }
        if (removal.shiftRetired) {
            care.clear(runtime, "admin_worldedit_unmanage")
            pests.clear(runtime, "admin_worldedit_unmanage")
            delivery.clear(runtime, "admin_worldedit_unmanage")
        } else if (
            runtime.state.phase == FarmPhase.INCIDENT &&
            (runtime.state.incidentType ?: FarmIncidentType.PESTS) == FarmIncidentType.PESTS &&
            runtime.state.pestNests.isEmpty() && runtime.state.pestAlive == 0 && currentOrder(runtime)
        ) {
            transitions.apply(runtime, FarmShiftEngine.finishPestIncidentIfClear(runtime.state), null)
        }
        port.sendChat(
            player,
            MessageKey.ADMIN_UNMANAGE_DONE,
            mapOf(
                "plots" to locale.text(selected.size + selectedFixed.size + selectedLeaves.size),
                "records" to locale.text(removedRecords),
            ),
        )
        debug.event(
            "farm_admin_worldedit_unmanage",
            "player" to player.name,
            "zone" to runtime.settings.id,
            "selection_volume" to selection.volume,
            "plots" to selected.size,
            "fixed_crops" to selectedFixed.size,
            "orchard_leaves" to selectedLeaves.size,
            "records" to removedRecords,
            "shift_retired" to removal.shiftRetired,
        )
        return true
    }

    private fun backupContext(player: Player, zoneId: String): FarmBackupAdminContext? {
        val runtime = runtime(zoneId, player) ?: return null
        return FarmBackupAdminContext(
            zoneId = zoneId,
            region = runtime.region,
            idle = runtime.state.phase == FarmPhase.IDLE,
            reindexDefinition = definition(runtime),
            backupBlocksPerTick = runtime.settings.backupBlocksPerTick,
            backupMaxBlocks = runtime.settings.backupMaxBlocks,
            paused = paused(zoneId),
            pause = { setPaused(zoneId, true) },
            resume = { setPaused(zoneId, false) },
            clearAdminModes = { release(player) },
        )
    }

    private fun runtime(zoneId: String, player: Player): FarmRuntime? =
        runtimes().firstOrNull { it.settings.id == zoneId } ?: run {
            port.sendChat(player, MessageKey.ADMIN_ZONE_UNKNOWN, mapOf("zone" to locale.text(zoneId)))
            null
        }

    private fun farmAt(block: Block): FarmRuntime? = runtimes().firstOrNull { it.region.contains(block.location) }

    private fun definition(runtime: FarmRuntime): FarmBlockIndexDefinition = runtime.blockIndexDefinition()

    private fun currentOrder(runtime: FarmRuntime): Boolean = runtime.state.orderId?.let(runtime.orders::containsKey) == true

    private fun remainingSeconds(deadline: Long, now: Long): Long =
        ceil((deadline - now).coerceAtLeast(0) / 1_000.0).toLong()

    private companion object {
        const val INSPECT_RADIUS = 28.0
        const val MAX_INSPECT_PARTICLES = 320
        val INDEX_COLOR: Color = Color.fromRGB(85, 217, 139)
        val PATCH_COLOR: Color = Color.fromRGB(255, 173, 66)
        val DAMAGE_COLOR: Color = Color.fromRGB(255, 95, 109)
        val SPECIAL_COLOR: Color = Color.fromRGB(154, 140, 255)
    }
}
