package ru.ruscrafting.farms.paper.farm.incident.frost

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.data.type.Campfire
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.FarmFrostSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmCropDamage
import ru.ruscrafting.farms.domain.FarmFrostEngine
import ru.ruscrafting.farms.domain.FarmFrostPlanner
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.toFarmPlotPosition
import java.util.UUID

/** Restart-safe frost incident: indexed crops become protected campfires and players carry one log at a time. */
internal class FarmFrostIncident(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val ledger: FarmBlockLedger,
    private val beds: FarmIncidentBedProvider,
    private val points: FarmPointProvider,
    private val transitions: FarmTransitionSink,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val clock: () -> Long,
) {
    private val entityZoneKey = NamespacedKey(plugin, "farm_frost_zone")
    private val entityRoleKey = NamespacedKey(plugin, "farm_frost_role")
    private val itemZoneKey = NamespacedKey(plugin, "farm_frost_wood_zone")
    private val itemSequenceKey = NamespacedKey(plugin, "farm_frost_wood_sequence")
    private val sceneIds = mutableMapOf<String, MutableSet<UUID>>()
    private val knownCampfires = mutableMapOf<String, List<FarmPlotPosition>>()
    private val originalFreezeTicks = mutableMapOf<UUID, Int>()

    fun initialize(runtime: FarmRuntime): Boolean {
        if (!active(runtime)) return false
        if (!hasConfiguredPoint(runtime)) {
            debug.event(
                "farm_frost_unavailable",
                "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence,
                "reason" to "missing_firewood_point",
            )
            return false
        }
        runtime.state.frost?.let { frost ->
            knownCampfires[runtime.settings.id] = frost.campfires.map { it.position }
            return true
        }
        val candidates = beds.discover(runtime).mapNotNull { position ->
            val soil = position.block() ?: return@mapNotNull null
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            if (crop.type.name !in runtime.settings.crops) return@mapNotNull null
            val age = crop.blockData as? Ageable
            if (age != null && age.age < age.maximumAge) return@mapNotNull null
            position
        }
        val frostSettings = runtime.settings.specialIncidents.frost
        val selected = FarmFrostPlanner.select(
            candidates,
            frostSettings.campfireCount(candidates.size),
            runtime.state.placementSequence,
        )
        if (selected.isEmpty()) {
            debug.event(
                "farm_frost_unavailable",
                "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence,
                "reason" to "no_loaded_mature_indexed_crops",
                "beds" to candidates.size,
            )
            return false
        }
        val soils = selected.mapNotNull(FarmPlotPosition::block)
        ledger.captureActiveCrops(soils, runtime.settings.id)
        val damages = soils.mapNotNull { soil ->
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            crop.type.takeIf { it.name in runtime.settings.crops }?.let { FarmCropDamage(soil.toFarmPlotPosition(), it.name) }
        }
        val result = FarmFrostEngine.initialize(
            runtime.state,
            damages.map(FarmCropDamage::position),
            clock(),
            frostSettings.targetTemperature,
        )
        if (!result.accepted) return false
        runtime.state = result.state.copy(
            specialDamagedCrops = (runtime.state.specialDamagedCrops + damages).distinctBy(FarmCropDamage::position),
        )
        knownCampfires[runtime.settings.id] = damages.map(FarmCropDamage::position)
        materializeCampfires(runtime, clock())
        state.persistAsync()
        debug.event(
            "farm_frost_initialized",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "campfires" to damages.size,
            "temperature_target" to frostSettings.targetTemperature,
        )
        return true
    }

    fun ensure(runtime: FarmRuntime) {
        if (!active(runtime)) {
            if (knownCampfires.containsKey(runtime.settings.id) || sceneIds.containsKey(runtime.settings.id)) {
                clear(runtime, "inactive")
            }
            clearFreeze(audience.players(runtime.region))
            return
        }
        if (!initialize(runtime)) return
        materializeCampfires(runtime, clock())
        ensureWoodpile(runtime)
    }

    fun update(runtime: FarmRuntime, now: Long) {
        if (!active(runtime) || !initialize(runtime)) {
            clearFreeze(audience.players(runtime.region))
            return
        }
        val frostSettings = runtime.settings.specialIncidents.frost
        val result = FarmFrostEngine.tick(
            runtime.state,
            now,
            frostSettings.heatPerSecondPerFire,
            frostSettings.coolingSecondsPerDegree,
        )
        if (result.accepted) {
            transitions.apply(runtime, result, null)
            state.persistAsync()
        }
        if (!active(runtime)) return
        materializeCampfires(runtime, now)
        audience.players(runtime.region).forEach { player -> applyFreeze(player, runtime) }
        if (settings().particles) emitCold(runtime)
    }

    fun onMove(player: Player, runtime: FarmRuntime?): Boolean {
        if (runtime == null || !active(runtime) || !access.hasAccess(player, runtime.settings.permission)) return false
        if (isServiceItem(player.inventory.itemInMainHand, runtime.settings.id) || carryingSlot(player, runtime.settings.id) >= 0) {
            val target = nearestCampfire(runtime, player.location) ?: return false
            val result = FarmFrostEngine.fuel(
                runtime.state,
                target,
                player.uniqueId,
                clock(),
                runtime.settings.specialIncidents.frost.fuelSeconds * 1_000L,
            )
            if (!result.accepted) return false
            removeOne(player, runtime.settings.id)
            transitions.apply(runtime, result, player)
            materializeCampfires(runtime, clock())
            audience.showScreenTitle(player, MessageKey.FARM_FROST_FUELED, scope = "frost_fueled")
            if (settings().sounds) player.playSound(player.location, Sound.ITEM_FIRECHARGE_USE, 0.8f, 1.05f)
            debug.event(
                "farm_frost_fueled",
                "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence,
                "player" to player.name,
                "x" to target.x,
                "y" to target.y,
                "z" to target.z,
            )
            return true
        }
        val woodpile = woodpileLocation(runtime) ?: return false
        val pickupRadius = runtime.settings.specialIncidents.frost.pickupRadius
        if (player.world !== woodpile.world || player.location.distanceSquared(woodpile) > pickupRadius * pickupRadius) return false
        if (!access.allowInteraction(
                "farm-frost-pickup:${runtime.settings.id}:${player.uniqueId}",
                runtime.settings.inputCooldowns.frostPickupMillis,
            )) return false
        val item = firewoodItem(runtime, player)
        if (player.inventory.addItem(item).isNotEmpty()) {
            audience.sendActionBar(player, MessageKey.FARM_FROST_INVENTORY_FULL)
            return true
        }
        audience.showScreenTitle(player, MessageKey.FARM_FROST_PICKED_UP, scope = "frost_pickup")
        if (settings().sounds) player.playSound(player.location, Sound.BLOCK_WOOD_HIT, 0.8f, 1.1f)
        return true
    }

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(entityZoneKey, PersistentDataType.STRING)

    fun hasConfiguredPoint(runtime: FarmRuntime): Boolean =
        points.configured(runtime, FarmPointKind.FIREWOOD) != null

    fun protects(location: Location): Boolean {
        val position = location.toFarmPlotPosition().copy(y = location.blockY - 1)
        return knownCampfires.values.any { position in it }
    }

    fun isServiceItem(item: ItemStack?): Boolean = item?.itemMeta?.persistentDataContainer
        ?.has(itemZoneKey, PersistentDataType.STRING) == true

    fun isServiceItem(item: ItemStack?, zoneId: String): Boolean = item?.itemMeta?.persistentDataContainer
        ?.get(itemZoneKey, PersistentDataType.STRING) == zoneId

    fun isCarrying(player: Player, zoneId: String): Boolean = carryingSlot(player, zoneId) >= 0

    fun removeServiceItems(player: Player, zoneId: String? = null, reason: String) {
        var removed = 0
        player.inventory.contents.forEachIndexed { index, item ->
            if (isServiceItem(item) && (zoneId == null || isServiceItem(item, zoneId))) {
                player.inventory.setItem(index, null)
                removed += item?.amount ?: 0
            }
        }
        if (removed > 0) debug.event(
            "farm_frost_firewood_removed",
            "player" to player.name,
            "zone" to zoneId,
            "count" to removed,
            "reason" to reason,
        )
    }

    fun clearPlayer(player: Player, reason: String) {
        removeServiceItems(player, reason = reason)
        clearFreeze(listOf(player))
    }

    fun refresh(runtime: FarmRuntime, reason: String) {
        clearScene(runtime.settings.id, reason)
        if (active(runtime)) ensureWoodpile(runtime)
    }

    fun clear(runtime: FarmRuntime, reason: String, retireDamage: Boolean = true) {
        val positions = (
            knownCampfires.remove(runtime.settings.id).orEmpty() +
                runtime.state.frost?.campfires.orEmpty().map { it.position }
        ).distinct()
        positions.forEach { position ->
            val soil = position.block() ?: return@forEach
            val above = soil.getRelative(org.bukkit.block.BlockFace.UP)
            if (above.type == Material.CAMPFIRE) above.setType(Material.AIR, false)
            ledger.restoreActiveCrop(soil)
        }
        if (retireDamage && positions.isNotEmpty()) {
            val owned = positions.toHashSet()
            runtime.state = runtime.state.copy(
                specialDamagedCrops = runtime.state.specialDamagedCrops.filterNot { it.position in owned },
            )
        }
        clearScene(runtime.settings.id, reason)
        audience.players(runtime.region).forEach { player ->
            removeServiceItems(player, runtime.settings.id, reason)
        }
        clearFreeze(audience.players(runtime.region))
        debug.event("farm_frost_cleared", "zone" to runtime.settings.id, "reason" to reason, "campfires" to positions.size)
    }

    fun cleanup(reason: String) {
        runtimes().forEach { runtime -> clear(runtime, reason, retireDamage = false) }
        originalFreezeTicks.keys.mapNotNull(Bukkit::getPlayer).forEach { clearFreeze(listOf(it)) }
        sceneIds.clear()
        knownCampfires.clear()
    }

    private fun materializeCampfires(runtime: FarmRuntime, now: Long) {
        val frost = runtime.state.frost ?: return
        knownCampfires[runtime.settings.id] = frost.campfires.map { it.position }
        frost.campfires.forEach { fire ->
            val soil = fire.position.block() ?: return@forEach
            val block = soil.getRelative(org.bukkit.block.BlockFace.UP)
            val shouldBeLit = fire.fuelUntil > now
            val current = block.blockData as? Campfire
            if (
                block.type == Material.CAMPFIRE && current != null && current.isLit == shouldBeLit &&
                !current.isSignalFire && !current.isWaterlogged
            ) return@forEach
            val data = (Material.CAMPFIRE.createBlockData() as Campfire).also {
                it.isLit = shouldBeLit
                it.isSignalFire = false
                it.isWaterlogged = false
            }
            block.setBlockData(data, false)
        }
    }

    private fun ensureWoodpile(runtime: FarmRuntime) {
        val existing = sceneIds[runtime.settings.id].orEmpty().mapNotNull(Bukkit::getEntity).filter(Entity::isValid)
        if (existing.any { role(it) == ROLE_DISPLAY } && existing.any { role(it) == ROLE_INTERACTION }) return
        existing.forEach(Entity::remove)
        sceneIds.remove(runtime.settings.id)
        val location = woodpileLocation(runtime) ?: return
        if (!location.world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return
        val frostSettings = runtime.settings.specialIncidents.frost
        val ids = mutableSetOf<UUID>()
        location.world.spawn(location, ItemDisplay::class.java) { display ->
            tag(display, runtime.settings.id, ROLE_DISPLAY)
            display.isPersistent = false
            display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.valueOf(frostSettings.woodpileDisplayTransform)
            display.setItemStack(woodpileItem(frostSettings))
            display.viewRange = frostSettings.woodpileViewRange
            display.isGlowing = true
            val scale = frostSettings.woodpileScale
            display.transformation = Transformation(
                Vector3f(),
                AxisAngle4f(),
                Vector3f(scale, scale, scale),
                AxisAngle4f(),
            )
            ids += display.uniqueId
        }
        location.world.spawn(location.clone().add(0.0, 0.45, 0.0), Interaction::class.java) { interaction ->
            tag(interaction, runtime.settings.id, ROLE_INTERACTION)
            interaction.isPersistent = false
            interaction.interactionWidth = 1.8f
            interaction.interactionHeight = 1.0f
            interaction.isResponsive = true
            ids += interaction.uniqueId
        }
        sceneIds[runtime.settings.id] = ids
    }

    private fun woodpileLocation(runtime: FarmRuntime): Location? {
        val point = points.configured(runtime, FarmPointKind.FIREWOOD) ?: return null
        val world = Bukkit.getWorld(point.world) ?: return null
        val visuals = runtime.settings.specialIncidents.frost
        return Location(
            world,
            point.x,
            point.y + visuals.woodpileYOffset,
            point.z,
            point.yaw + visuals.woodpileYawOffset,
            0f,
        )
    }

    private fun nearestCampfire(runtime: FarmRuntime, location: Location): FarmPlotPosition? {
        val radius = runtime.settings.specialIncidents.frost.deliveryRadius
        return runtime.state.frost?.campfires.orEmpty().asSequence()
            .mapNotNull { fire ->
                val soil = fire.position.block() ?: return@mapNotNull null
                val center = soil.location.add(0.5, 1.0, 0.5)
                if (center.world !== location.world) null else fire.position to center.distanceSquared(location)
            }
            .filter { it.second <= radius * radius }
            .minByOrNull { it.second }
            ?.first
    }

    private fun firewoodItem(runtime: FarmRuntime, player: Player): ItemStack {
        val item = ItemStack(MaterialRules.material(runtime.settings.specialIncidents.frost.fuelMaterial))
        item.editMeta { meta ->
            meta.displayName(locale.render(MessageKey.FARM_FROST_FIREWOOD_ITEM, player))
            meta.persistentDataContainer.set(itemZoneKey, PersistentDataType.STRING, runtime.settings.id)
            meta.persistentDataContainer.set(itemSequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        }
        return item
    }

    private fun woodpileItem(frost: FarmFrostSettings): ItemStack = ItemStack(MaterialRules.material(frost.woodpileMaterial)).also { item ->
        if (frost.woodpileCustomModelData > 0) item.editMeta { it.setCustomModelData(frost.woodpileCustomModelData) }
    }

    private fun carryingSlot(player: Player, zoneId: String): Int {
        val sequence = runtimes().firstOrNull { it.settings.id == zoneId }?.state?.sequence ?: return -1
        return player.inventory.contents.indexOfFirst { item ->
            isServiceItem(item, zoneId) && item?.itemMeta?.persistentDataContainer
                ?.get(itemSequenceKey, PersistentDataType.LONG) == sequence
        }
    }

    private fun removeOne(player: Player, zoneId: String) {
        val slot = carryingSlot(player, zoneId)
        if (slot >= 0) player.inventory.setItem(slot, null)
    }

    private fun applyFreeze(player: Player, runtime: FarmRuntime) {
        originalFreezeTicks.putIfAbsent(player.uniqueId, player.freezeTicks)
        val target = runtime.state.incidentRequired.coerceAtLeast(1)
        val coldRatio = 1.0 - runtime.state.incidentProgress.toDouble() / target
        val safeMaximum = (player.maxFreezeTicks - 2).coerceAtLeast(0)
        player.freezeTicks = (safeMaximum * coldRatio * 0.85).toInt().coerceIn(0, safeMaximum)
    }

    private fun clearFreeze(players: Collection<Player>) {
        players.forEach { player ->
            originalFreezeTicks.remove(player.uniqueId)?.let { original ->
                player.freezeTicks = original.coerceIn(0, (player.maxFreezeTicks - 2).coerceAtLeast(0))
            }
        }
    }

    private fun emitCold(runtime: FarmRuntime) {
        if (!access.allowInteraction("farm-frost-particles:${runtime.settings.id}", 1_000)) return
        runtime.state.frost?.campfires.orEmpty().forEach { fire ->
            val soil = fire.position.block() ?: return@forEach
            val center = soil.location.add(0.5, 1.35, 0.5)
            soil.world.spawnParticle(Particle.SNOWFLAKE, center, 3, 0.35, 0.2, 0.35, 0.01)
        }
    }

    private fun tag(entity: Entity, zoneId: String, role: String) {
        entity.persistentDataContainer.set(entityZoneKey, PersistentDataType.STRING, zoneId)
        entity.persistentDataContainer.set(entityRoleKey, PersistentDataType.STRING, role)
    }

    private fun role(entity: Entity): String? = entity.persistentDataContainer.get(entityRoleKey, PersistentDataType.STRING)

    private fun clearScene(zoneId: String, reason: String) {
        val removed = sceneIds.remove(zoneId).orEmpty().mapNotNull(Bukkit::getEntity).count { entity ->
            entity.remove()
            true
        }
        if (removed > 0) debug.event("farm_frost_scene_cleared", "zone" to zoneId, "reason" to reason, "entities" to removed)
    }

    private fun active(runtime: FarmRuntime): Boolean = runtime.state.phase == FarmPhase.INCIDENT &&
        runtime.state.incidentType == FarmIncidentType.FROST

    private companion object {
        const val ROLE_DISPLAY = "WOODPILE_DISPLAY"
        const val ROLE_INTERACTION = "WOODPILE_INTERACTION"
    }
}
