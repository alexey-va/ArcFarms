package ru.ruscrafting.farms.paper.farm.care.mole

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Rabbit
import org.bukkit.entity.TextDisplay
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.paper.teleport.ScopedTeleportAuthorizer
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmMoleGuidance
import ru.ruscrafting.farms.domain.FarmMoleProximity
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.care.FarmCarePresentation
import ru.ruscrafting.farms.paper.farm.care.bukkit
import ru.ruscrafting.farms.paper.platform.FarmMobDespawnPolicy
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayStyle
import ru.ruscrafting.farms.persistence.FarmBurrowReturn
import ru.ruscrafting.farms.persistence.FarmBurrowReturnRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlin.random.Random

private val MOLE_LABEL_STYLE = FarmTextDisplayStyle(viewRange = 0.8f)

/** Interactive shell around the crash-safe temporary mole tunnel. */
internal class FarmMoleBurrowController(
    private val plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val world: FarmMoleBurrowWorld,
    private val transitions: FarmTransitionSink,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val clock: () -> Long,
    private val textDisplays: FarmTextDisplayRenderer,
    private val mobDespawns: FarmMobDespawnPolicy,
) {
    private enum class Role { ENTRANCE, LAIR, EXIT, MOLE }
    private data class SceneKey(val zoneId: String, val sequence: Long, val burrowId: Int)
    private data class Identity(val zoneId: String, val sequence: Long, val burrowId: Int, val role: Role)

    private val presentation = FarmCarePresentation(settings)
    private val returns by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FarmBurrowReturnRepository(plugin.dataFolder.toPath())
    }
    private val teleports = ScopedTeleportAuthorizer()
    private val entities = mutableMapOf<SceneKey, MutableSet<UUID>>()
    private val molesInitialized = mutableSetOf<SceneKey>()
    private val sessions = ConcurrentHashMap<UUID, FarmBurrowReturn>()
    private val pendingEntries = ConcurrentHashMap.newKeySet<UUID>()
    private val zoneKey = NamespacedKey(plugin, "farm_mole_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_mole_sequence")
    private val roleKey = NamespacedKey(plugin, "farm_mole_role")
    private val burrowKey = NamespacedKey(plugin, "farm_mole_burrow")
    private var guidanceTick = 0L

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)

    fun ensure(runtime: FarmRuntime) {
        if (!active(runtime)) return
        runtime.state.careTargets.filter { it.role == FarmCareRole.MOLE_MOUND && !it.complete }.forEach { target ->
            val (result, scene) = world.ensure(runtime, target.position, target.id)
            when {
                result == FarmMoleBurrowEnsureResult.READY && scene != null -> ensureScene(runtime, scene)
                result == FarmMoleBurrowEnsureResult.BUILDING && scene != null -> ensureBuildingEntrance(runtime, scene)
                result == FarmMoleBurrowEnsureResult.UNAVAILABLE -> debug.event(
                    "farm_mole_burrow_target_unavailable",
                    "zone" to runtime.settings.id,
                    "sequence" to runtime.state.sequence,
                    "burrow" to target.id,
                )
            }
        }
    }

    fun prepare(runtime: FarmRuntime, targets: List<ru.ruscrafting.farms.domain.FarmCareTarget>, placementSequence: Long): Boolean =
        world.prepare(runtime, targets, placementSequence)

    fun discardPrepared(runtime: FarmRuntime) =
        world.beginRestore(runtime.region.world, runtime.settings.id, runtime.state.sequence)

    fun interact(player: Player, entity: Entity): Boolean {
        val identity = identity(entity) ?: return false
        val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return true
        if (!active(runtime) || runtime.state.sequence != identity.sequence || !port.hasAccess(player, runtime.settings.permission)) {
            if (!port.hasAccess(player, runtime.settings.permission)) port.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (!port.allowInteraction("farm-mole:${runtime.settings.id}:${identity.burrowId}:${identity.role}:${player.uniqueId}", 500)) return true
        val scene = world.scene(runtime, identity.burrowId) ?: return true
        when (identity.role) {
            Role.ENTRANCE -> enter(player, runtime, scene)
            Role.LAIR -> finish(player, runtime, scene)
            Role.EXIT -> leave(player, scene, MessageKey.FARM_MOLE_LEFT)
            Role.MOLE -> Unit
        }
        return true
    }

    fun onDamage(event: EntityDamageEvent): Boolean {
        val identity = identity(event.entity) ?: return false
        val player = (event as? EntityDamageByEntityEvent)?.let { damage ->
            when (val damager = damage.damager) {
                is Player -> damager
                is Projectile -> damager.shooter as? Player
                else -> null
            }
        }
        if (identity.role == Role.MOLE) {
            event.isCancelled = player == null
            return true
        }
        event.isCancelled = true
        player ?: return true
        interact(player, event.entity)
        return true
    }

    fun onDeath(event: EntityDeathEvent): Boolean {
        val identity = identity(event.entity) ?: return false
        if (identity.role != Role.MOLE) return false
        event.drops.clear()
        event.droppedExp = 0
        entities[SceneKey(identity.zoneId, identity.sequence, identity.burrowId)]?.remove(event.entity.uniqueId)
        return true
    }

    fun onMove(event: PlayerMoveEvent) {
        val record = sessions[event.player.uniqueId] ?: return
        val destination = event.to
        if (event is PlayerTeleportEvent && teleports.isAuthorized(event.player.uniqueId, destination)) return
        val runtime = runtimes().firstOrNull {
            it.settings.id == record.zoneId && it.state.sequence == record.sequence && active(it)
        }
        val scene = runtime?.let { activeRuntime ->
            world.scenes(activeRuntime).firstOrNull { it.contains(destination) }
        }
        if (scene == null || !scene.contains(destination)) {
            sessions.remove(event.player.uniqueId, record)
            acknowledgeAsync(record)
            debug.event("farm_mole_burrow_external_exit", "zone" to record.zoneId, "player" to event.player.name)
        }
    }

    fun recoverPlayer(player: Player) {
        val lifecycleToken = port.lifecycleToken()
        port.runAsync(lifecycleToken) {
            val record = runCatching { returns.load(player.uniqueId) }.getOrElse { failure ->
                port.log(Level.SEVERE, "Could not read mole burrow return for ${player.uniqueId}", failure)
                return@runAsync
            } ?: return@runAsync
            port.runSync(lifecycleToken) {
                if (!player.isOnline) return@runSync
                val destination = returnLocation(record) ?: run {
                    port.log(Level.SEVERE, "Rejected invalid mole burrow return for ${player.uniqueId}")
                    return@runSync
                }
                val moved = teleports.authorize(player.uniqueId, destination) {
                    player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                }
                if (moved) {
                    sessions.remove(player.uniqueId)
                    acknowledgeAsync(record)
                    port.sendChat(player, MessageKey.FARM_MOLE_RECOVERED)
                    debug.event("farm_mole_burrow_player_recovered", "zone" to record.zoneId, "player" to player.name)
                }
            }
        }
    }

    fun releasePlayer(player: Player, reason: String) {
        pendingEntries.remove(player.uniqueId)
        val record = sessions.remove(player.uniqueId) ?: return
        if (reason != "player_quit") acknowledgeAsync(record)
    }

    fun onPlayerDeath(player: Player) = releasePlayer(player, "player_death")

    fun clear(runtime: FarmRuntime, reason: String) {
        val keys = entities.keys.filter { it.zoneId == runtime.settings.id && it.sequence == runtime.state.sequence }
        keys.toList().forEach(::removeEntities)
        val scenes = world.scenes(runtime)
        sessions.values.filter { it.zoneId == runtime.settings.id && it.sequence == runtime.state.sequence }.forEach { record ->
            val player = Bukkit.getPlayer(record.playerId)
            if (player != null && player.isOnline) leave(player, record, null)
            else sessions.remove(record.playerId, record)
        }
        scenes.forEach { activeScene ->
            activeScene.world.players.filter { activeScene.contains(it.location) }.forEach { player ->
                val destination = activeScene.surface.clone().apply {
                    yaw = player.location.yaw
                    pitch = player.location.pitch
                }
                teleports.authorize(player.uniqueId, destination) {
                    player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                }
                recoverPlayer(player)
            }
        }
        world.beginRestore(runtime.region.world, runtime.settings.id, runtime.state.sequence)
        debug.event("farm_mole_burrow_cleared", "zone" to runtime.settings.id, "sequence" to runtime.state.sequence, "reason" to reason)
    }

    fun cleanup(reason: String) {
        sessions.values.toList().forEach { record ->
            Bukkit.getPlayer(record.playerId)?.takeIf(Player::isOnline)?.let { leave(it, record, null) }
        }
        Bukkit.getWorlds().asSequence().flatMap { it.entities.asSequence() }.filter(::owns).forEach(Entity::remove)
        entities.clear()
        molesInitialized.clear()
        sessions.clear()
        pendingEntries.clear()
        world.clearQueues()
        debug.event("farm_mole_burrow_cleanup", "reason" to reason)
    }

    fun onChunkLoad(chunk: org.bukkit.Chunk) = world.onChunkLoad(chunk, ::isActive)
    fun reconcileLoaded() = world.reconcileLoaded(::isActive)
    fun processBlocks(limit: Int): Int {
        val byZone = runtimes().associateBy { it.settings.id }
        return world.process(limit) { record ->
            val runtime = byZone[record.zoneId] ?: return@process false
            runtime.ownsMoleBurrowRecord(record)
        }
    }

    fun updateGuidance() {
        guidanceTick++
        sessions.values.forEach { record ->
            val runtime = runtimes().firstOrNull {
                it.settings.id == record.zoneId && it.state.sequence == record.sequence && active(it)
            } ?: return@forEach
            if (guidanceTick % runtime.settings.moleBurrow.guidanceIntervalTicks != 0L) return@forEach
            val player = Bukkit.getPlayer(record.playerId)?.takeIf(Player::isOnline) ?: return@forEach
            val scene = world.scenes(runtime).firstOrNull { it.contains(player.location) } ?: return@forEach
            val distance = scene.pathDistanceToLair(player.location) ?: return@forEach
            val key = when (FarmMoleGuidance.proximity(
                distance,
                runtime.settings.moleBurrow.guidanceCloseDistance,
                runtime.settings.moleBurrow.guidanceFarDistance,
            )) {
                FarmMoleProximity.FAR -> MessageKey.FARM_MOLE_DISTANCE_FAR
                FarmMoleProximity.CLOSER -> MessageKey.FARM_MOLE_DISTANCE_CLOSER
                FarmMoleProximity.VERY_CLOSE -> MessageKey.FARM_MOLE_DISTANCE_VERY_CLOSE
            }
            port.sendActionBar(player, key)
        }
    }

    private fun enter(player: Player, runtime: FarmRuntime, scene: FarmMoleBurrowScene) {
        if (!scene.ready || !pendingEntries.add(player.uniqueId)) {
            if (!scene.ready) port.sendActionBar(player, MessageKey.FARM_MOLE_BUILDING)
            return
        }
        val surface = scene.surface.clone().apply {
            yaw = player.location.yaw
            pitch = player.location.pitch
        }
        val record = FarmBurrowReturn(
            player.uniqueId,
            runtime.settings.id,
            runtime.state.sequence,
            surface.world.name,
            surface.x,
            surface.y,
            surface.z,
            surface.yaw,
            surface.pitch,
            clock(),
        )
        val lifecycleToken = port.lifecycleToken()
        port.runAsync(lifecycleToken) {
            val committed = runCatching { returns.commit(record) }.getOrElse { failure ->
                port.log(Level.SEVERE, "Could not commit mole burrow return for ${player.uniqueId}", failure)
                null
            }
            port.runSync(lifecycleToken) {
                pendingEntries.remove(player.uniqueId)
                val stillAtEntrance = player.isOnline && player.world === scene.world && runtime.region.contains(player.location) &&
                    player.location.distanceSquared(scene.surface) <= 25.0
                if (committed == null || !active(runtime) || runtime.state.sequence != record.sequence ||
                    !scene.ready || !stillAtEntrance
                ) {
                    if (committed != null) acknowledgeAsync(committed)
                    return@runSync
                }
                val destination = scene.start.clone().add(0.0, 0.05, 0.0).apply {
                    yaw = player.location.yaw
                    pitch = 0f
                }
                val moved = teleports.authorize(player.uniqueId, destination) {
                    player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                }
                if (!moved) {
                    acknowledgeAsync(committed)
                    return@runSync
                }
                sessions[player.uniqueId] = committed
                player.fallDistance = 0f
                port.showScreenTitle(player, MessageKey.FARM_MOLE_ENTERED)
                if (settings().sounds) {
                    player.playSound(destination, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.85f, 0.7f)
                }
                debug.event("farm_mole_burrow_entered", "zone" to runtime.settings.id, "player" to player.name)
            }
        }
    }

    private fun finish(player: Player, runtime: FarmRuntime, scene: FarmMoleBurrowScene) {
        if (!scene.contains(player.location)) return
        val target = runtime.state.careTargets.firstOrNull {
            it.id == scene.burrowId && it.role == FarmCareRole.MOLE_MOUND && !it.complete
        } ?: return
        val result = FarmShiftEngine.advanceCare(runtime.state, target.id, player.uniqueId)
        if (!result.accepted) return
        if (settings().sounds) {
            player.playSound(scene.lair, Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.15f)
        }
        transitions.apply(runtime, result, player)
        releaseScenePlayers(scene)
        removeEntities(SceneKey(runtime.settings.id, runtime.state.sequence, scene.burrowId))
        debug.event("farm_mole_burrow_lair_found", "zone" to runtime.settings.id, "player" to player.name)
    }

    /**
     * A completed lair is an exit boundary for that burrow, not for the whole
     * multi-burrow event. Return every explorer currently inside this scene so
     * a group can immediately continue with the remaining entrances.
     */
    private fun releaseScenePlayers(scene: FarmMoleBurrowScene) {
        scene.world.players.filter { scene.contains(it.location) }.forEach { explorer ->
            leave(explorer, scene, null)
            debug.event(
                "farm_mole_burrow_player_released",
                "zone" to scene.zoneId,
                "sequence" to scene.sequence,
                "burrow" to scene.burrowId,
                "player" to explorer.name,
            )
        }
    }

    private fun leave(player: Player, scene: FarmMoleBurrowScene, message: MessageKey?) {
        val record = sessions[player.uniqueId] ?: FarmBurrowReturn(
            player.uniqueId, scene.zoneId, scene.sequence, scene.world.name,
            scene.surface.x, scene.surface.y, scene.surface.z, player.location.yaw, player.location.pitch, clock(),
        )
        leave(player, record, message)
    }

    private fun leave(player: Player, record: FarmBurrowReturn, message: MessageKey?) {
        val destination = returnLocation(record) ?: return
        val moved = teleports.authorize(player.uniqueId, destination) {
            player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
        }
        if (!moved) return
        player.fallDistance = 0f
        sessions.remove(player.uniqueId, record)
        acknowledgeAsync(record)
        if (message != null) port.sendActionBar(player, message)
    }

    private fun ensureScene(runtime: FarmRuntime, scene: FarmMoleBurrowScene) {
        val key = SceneKey(runtime.settings.id, runtime.state.sequence, scene.burrowId)
        val active = entities[key].orEmpty().mapNotNull(Bukkit::getEntity).filter(Entity::isValid)
        val structural = active.filter { identity(it)?.role != Role.MOLE }
        if (structural.size == expectedStructuralEntities(runtime) && structural.all {
                identity(it)?.let { id ->
                    id.zoneId == key.zoneId && id.sequence == key.sequence && id.burrowId == key.burrowId
                } == true
            }
        ) {
            entities[key] = active.mapTo(mutableSetOf(), Entity::getUniqueId)
            if (key !in molesInitialized) {
                spawnMoles(runtime, scene).mapTo(entities.getValue(key), Entity::getUniqueId)
                molesInitialized += key
            }
            return
        }
        removeEntities(key)
        val spawned = linkedSetOf<UUID>()
        spawnMarker(runtime, scene, scene.surface, Role.ENTRANCE, runtime.settings.careVisuals.getValue(FarmCareRole.MOLE_MOUND), "care.moles.entrance-label", true)
            .mapTo(spawned, Entity::getUniqueId)
        spawnMarker(runtime, scene, scene.lair, Role.LAIR, runtime.settings.moleBurrow.lairVisual, "care.moles.lair-label", false)
            .mapTo(spawned, Entity::getUniqueId)
        spawnExit(runtime, scene, scene.start).mapTo(spawned, Entity::getUniqueId)
        spawnMoles(runtime, scene).mapTo(spawned, Entity::getUniqueId)
        entities[key] = spawned
        molesInitialized += key
        debug.event("farm_mole_burrow_scene_spawned", "zone" to key.zoneId, "sequence" to key.sequence)
    }

    /**
     * Surface feedback is available while the bounded block queue builds the
     * underground scene. The entrance stays non-usable until [scene.ready], so
     * players never enter a half-built or failed maze.
     */
    private fun ensureBuildingEntrance(runtime: FarmRuntime, scene: FarmMoleBurrowScene) {
        val key = SceneKey(runtime.settings.id, runtime.state.sequence, scene.burrowId)
        val active = entities[key].orEmpty().mapNotNull(Bukkit::getEntity).filter(Entity::isValid)
        if (active.isNotEmpty() && active.all { identity(it)?.role == Role.ENTRANCE }) {
            entities[key] = active.mapTo(mutableSetOf(), Entity::getUniqueId)
            return
        }
        removeEntities(key)
        entities[key] = spawnMarker(
            runtime,
            scene,
            scene.surface,
            Role.ENTRANCE,
            runtime.settings.careVisuals.getValue(FarmCareRole.MOLE_MOUND),
            "care.moles.entrance-label",
            true,
        ).mapTo(mutableSetOf(), Entity::getUniqueId)
        debug.event(
            "farm_mole_burrow_entrance_building",
            "zone" to key.zoneId,
            "sequence" to key.sequence,
            "burrow" to key.burrowId,
        )
    }

    private fun spawnMoles(runtime: FarmRuntime, scene: FarmMoleBurrowScene): List<Rabbit> {
        val floorY = scene.start.blockY
        val candidates = scene.records.asSequence()
            .filter { it.y == floorY && it.burrowData == "minecraft:air" }
            .map { Location(scene.world, it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
            .filter { it.distanceSquared(scene.start) >= 16.0 && it.distanceSquared(scene.lair) >= 9.0 }
            .filter { it.block.getRelative(org.bukkit.block.BlockFace.DOWN).type.isSolid }
            .distinctBy { it.blockX to it.blockZ }
            .toMutableList()
        candidates.shuffle(Random(runtime.state.placementSequence xor 0x4d4f4c45L))
        return candidates.take(runtime.settings.moleBurrow.moleCount).map { location ->
            scene.world.spawn(location, Rabbit::class.java) { mole ->
                mole.setAdult()
                mole.rabbitType = Rabbit.Type.BROWN
                mole.isPersistent = false
                mobDespawns.setRemoveWhenFarAway(mole, false)
                mole.isCollidable = true
                mole.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 1.0
                mole.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.28
                mole.health = 1.0
                mole.customName(locale.render(MessageKey.FARM_MOLE_NAME))
                mole.isCustomNameVisible = true
                mark(mole, runtime, scene.burrowId, Role.MOLE)
            }
        }
    }

    private fun spawnMarker(
        runtime: FarmRuntime,
        scene: FarmMoleBurrowScene,
        location: Location,
        role: Role,
        visual: ru.ruscrafting.farms.config.FarmCareVisualSettings,
        labelPath: String,
        glowing: Boolean,
    ): List<Entity> {
        val result = mutableListOf<Entity>()
        val material = MaterialRules.material(visual.material)
        if (material != org.bukkit.Material.AIR) {
            val stack = ItemStack(material).also { item ->
                if (visual.customModelData > 0) item.itemMeta = item.itemMeta.also { it.setCustomModelData(visual.customModelData) }
            }
            result += location.world.spawn(location.clone().add(0.0, visual.displayYOffset, 0.0), ItemDisplay::class.java) { entity ->
                entity.setItemStack(stack)
                entity.itemDisplayTransform = visual.displayTransform.bukkit
                presentation.scale(entity, visual.displayScale)
                entity.viewRange = runtime.settings.displayViewRange
                entity.isGlowing = glowing
                entity.isPersistent = false
                mark(entity, runtime, scene.burrowId, role)
            }
        }
        val label = location.world.spawn(location.clone().add(0.0, 1.85, 0.0), TextDisplay::class.java) { entity ->
            textDisplays.render(entity, locale.renderPath(labelPath), MOLE_LABEL_STYLE)
            mark(entity, runtime, scene.burrowId, role)
        }
        val hitbox = location.world.spawn(location.clone().add(0.0, 0.55, 0.0), Interaction::class.java) { entity ->
            entity.interactionWidth = 1.45f
            entity.interactionHeight = 1.7f
            entity.isResponsive = true
            entity.isPersistent = false
            mark(entity, runtime, scene.burrowId, role)
        }
        result += label
        result += hitbox
        return result
    }

    private fun spawnExit(runtime: FarmRuntime, scene: FarmMoleBurrowScene, location: Location): List<Entity> {
        val label = location.world.spawn(location.clone().add(0.0, 1.65, 0.0), TextDisplay::class.java) { entity ->
            textDisplays.render(entity, locale.renderPath("care.moles.exit-label"), MOLE_LABEL_STYLE)
            mark(entity, runtime, scene.burrowId, Role.EXIT)
        }
        val hitbox = location.world.spawn(location.clone().add(0.0, 0.55, 0.0), Interaction::class.java) { entity ->
            entity.interactionWidth = 1.3f
            entity.interactionHeight = 1.7f
            entity.isResponsive = true
            entity.isPersistent = false
            mark(entity, runtime, scene.burrowId, Role.EXIT)
        }
        return listOf(label, hitbox)
    }

    private fun mark(entity: Entity, runtime: FarmRuntime, burrowId: Int, role: Role) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, role.name)
        entity.persistentDataContainer.set(burrowKey, PersistentDataType.INTEGER, burrowId)
    }

    private fun identity(entity: Entity): Identity? {
        val data = entity.persistentDataContainer
        val zone = data.get(zoneKey, PersistentDataType.STRING) ?: return null
        val sequence = data.get(sequenceKey, PersistentDataType.LONG) ?: return null
        val role = data.get(roleKey, PersistentDataType.STRING)?.let { runCatching { Role.valueOf(it) }.getOrNull() } ?: return null
        val burrowId = data.get(burrowKey, PersistentDataType.INTEGER) ?: 0
        return Identity(zone, sequence, burrowId, role)
    }

    private fun removeEntities(key: SceneKey) {
        entities.remove(key).orEmpty().forEach { Bukkit.getEntity(it)?.remove() }
        molesInitialized.remove(key)
    }

    private fun acknowledgeAsync(record: FarmBurrowReturn) {
        if (!plugin.isEnabled) return
        val lifecycleToken = runCatching(port::lifecycleToken).getOrNull() ?: return
        port.runAsync(lifecycleToken) {
            runCatching { returns.acknowledge(record) }.onFailure { failure ->
                port.log(Level.SEVERE, "Could not acknowledge mole burrow return for ${record.playerId}", failure)
            }
        }
    }

    private fun returnLocation(record: FarmBurrowReturn): Location? {
        val runtime = runtimes().firstOrNull { it.settings.id == record.zoneId } ?: return null
        val world = runtime.region.world.takeIf { it.name == record.world } ?: return null
        return Location(world, record.x, record.y, record.z, record.yaw, record.pitch)
            .takeIf(runtime.region::contains)
    }

    private fun active(runtime: FarmRuntime): Boolean = runtime.state.phase == FarmPhase.CARE &&
        runtime.state.careType == FarmCareType.MOLES

    private fun isActive(zoneId: String, sequence: Long): Boolean = runtimes().any {
        it.settings.id == zoneId && it.state.sequence == sequence && active(it)
    }

    private fun expectedStructuralEntities(runtime: FarmRuntime): Int =
        7 + if (MaterialRules.material(runtime.settings.moleBurrow.lairVisual.material) == org.bukkit.Material.AIR) 0 else 1
}
