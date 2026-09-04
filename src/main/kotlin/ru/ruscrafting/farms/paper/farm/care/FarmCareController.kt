package ru.ruscrafting.farms.paper.farm.care

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.FishHook
import org.bukkit.entity.Horse
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmCarePlanner
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmMachinePlanner
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPollenCharges
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmSeederStage
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.seederStage
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.BukkitFarmEntityLookup
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockPolicy
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmEntityLookup
import ru.ruscrafting.farms.paper.FarmMachineBlockProcessor
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.FarmSeederMountResult
import ru.ruscrafting.farms.paper.FarmSeederRigManager
import ru.ruscrafting.farms.paper.shouldSpawnSeederRig
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.worksite.*
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.care.irrigation.FarmIrrigationController
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowController
import ru.ruscrafting.farms.paper.farm.care.scarecrow.FarmScarecrowDeliveryController
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/** Sole owner of care state, PDC entities, animal followers, seeder rig and disease timing. */
internal class FarmCareController(
    plugin: Plugin,
    private val settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val debug: ArcFarmsDebug,
    private val access: WorksiteAccessPort, private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort, private val ledger: FarmBlockLedger,
    private val serviceItems: WorksiteServiceItems,
    private val registry: FarmBlockRegistry, private val plans: FarmCarePlanService,
    points: FarmPointProvider, private val moles: FarmMoleBurrowController,
    private val transitions: FarmTransitionSink,
    private val runtimes: () -> Collection<FarmRuntime>,
    private val clock: () -> Long,
    private val entityLookup: FarmEntityLookup = BukkitFarmEntityLookup,
) {
    private val presentation = FarmCarePresentation(settings)
    private val disease = FarmDiseaseController(
        settings = settings,
        debug = debug,
        audience = audience,
        state = state,
        ledger = ledger,
        transitions = transitions,
        targets = FarmCareTargetSpawner(::ensureFarmCareTarget),
    )
    private val scarecrows = FarmScarecrowDeliveryController(
        plugin = plugin,
        settings = settings,
        locale = locale,
        debug = debug,
        access = access,
        audience = audience,
        points = points,
        transitions = transitions,
        runtimes = runtimes,
        supplyPoint = { runtime -> plans.fixturePoint(runtime, FarmPointKind.PEN) },
    )
    private val irrigation = FarmIrrigationController(settings, debug, audience, transitions)
    private val seederRig = FarmSeederRigManager(plugin)
    private val machineBlocks = FarmMachineBlockProcessor(ledger)
    private val entities = mutableMapOf<FarmCareEntityKey, MutableSet<UUID>>()
    private val animalFollowers = mutableMapOf<FarmCareEntityKey, UUID>()
    private val reconciledSequences = mutableMapOf<String, Long>()
    private val pollenCharges = FarmPollenCharges()
    private val starts = FarmCareStartService(state, moles)
    private val ditchWorld = FarmDitchRescueWorld(ledger, state, debug)
    private val zoneKey = NamespacedKey(plugin, "farm_care_zone")
    private val sequenceKey = NamespacedKey(plugin, "farm_care_sequence")
    private val targetKey = NamespacedKey(plugin, "farm_care_target")
    private val roleKey = NamespacedKey(plugin, "farm_care_role")

    fun owns(entity: Entity): Boolean = moles.owns(entity) || scarecrows.owns(entity) ||
        entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)
    fun identity(entity: Entity): FarmCareEntityIdentity? {
        val data = entity.persistentDataContainer
        val zoneId = data.get(zoneKey, PersistentDataType.STRING) ?: return null
        val sequence = data.get(sequenceKey, PersistentDataType.LONG) ?: return null
        val targetId = data.get(targetKey, PersistentDataType.INTEGER) ?: return null
        val role = data.get(roleKey, PersistentDataType.STRING)
            ?.let { runCatching { FarmCareRole.valueOf(it) }.getOrNull() } ?: return null
        return FarmCareEntityIdentity(zoneId, sequence, targetId, role)
    }

    fun count(zoneId: String): Int = entities.keys.count { it.zoneId == zoneId }
    fun removeTarget(zoneId: String, targetId: Int, reason: String) =
        removeEntities(FarmCareEntityKey(zoneId, targetId), reason)

    fun releasePlayer(player: Player, reason: String) {
        moles.releasePlayer(player, reason)
        scarecrows.releasePlayer(player, reason)
        animalFollowers.filterValues { it == player.uniqueId }.keys.toList().forEach { key ->
            val mob = entities[key].orEmpty().asSequence().mapNotNull(Bukkit::getEntity).filterIsInstance<Mob>().firstOrNull()
            releaseFollower(key, mob, reason)
        }
        pollenCharges.remove(player.uniqueId)
        runtimes().filter { it.state.careType == FarmCareType.DITCH_RESCUE }.forEach { runtime ->
            val identity = rescueRodIdentity(runtime)
            while (serviceItems.consume(player, identity)) Unit
        }
    }

    fun onDeath(event: EntityDeathEvent): Boolean {
        if (moles.onDeath(event)) return true
        val identity = identity(event.entity) ?: return false
        event.drops.clear()
        event.droppedExp = 0
        entities[FarmCareEntityKey(identity.zoneId, identity.targetId)]?.remove(event.entity.uniqueId)
        return true
    }

    fun onVehicleEnter(event: VehicleEnterEvent) {
        val player = event.entered as? Player ?: return
        val identity = identity(event.vehicle) ?: return
        val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return
        val vehicleSupportsRider = when (event.vehicle) {
            is Horse -> true
            is org.bukkit.entity.Pig -> event.vehicle.passengers.none { it is Player }
            else -> false
        }
        val validRig = vehicleSupportsRider && identity.role == FarmCareRole.SEEDER_HORSE &&
            runtime.state.phase == FarmPhase.CARE && runtime.state.careType == FarmCareType.SEEDER &&
            runtime.state.sequence == identity.sequence &&
            runtime.state.careTargets.any { it.id == identity.targetId && it.role == FarmCareRole.SEEDER_HORSE } &&
            runtime.region.contains(event.vehicle.location) && runtime.region.contains(player.location) &&
            !access.isAdminEditing(player) && access.hasAccess(player, runtime.settings.permission)
        event.isCancelled = !validRig
        debug.event("farm_seeder_mount", "zone" to identity.zoneId, "player" to player.name, "allowed" to validRig)
    }

    fun onDamage(event: EntityDamageEvent): Boolean {
        if (scarecrows.owns(event.entity)) {
            event.isCancelled = true
            return true
        }
        if (moles.onDamage(event)) return true
        val identity = identity(event.entity) ?: return false
        if (allowsRescueHook(event, identity)) {
            event.isCancelled = false
            return true
        }
        event.isCancelled = true
        val player = (event as? EntityDamageByEntityEvent)?.let { damage ->
            when (val damager = damage.damager) {
                is Player -> damager
                is Projectile -> damager.shooter as? Player
                else -> null
            }
        } ?: return true
        interact(player, event.entity, identity.zoneId)
        return true
    }

    fun interact(player: Player, entity: Entity) {
        if (scarecrows.interact(player, entity)) return
        if (moles.interact(player, entity)) return
        val identity = identity(entity) ?: return
        interact(player, entity, identity.zoneId)
    }

    fun adminStageName(type: FarmCareType): String = presentation.stageId(type)

    fun color(role: FarmCareRole): Color = presentation.color(role)

    fun hasPollen(player: Player, runtime: FarmRuntime): Boolean =
        pollenCharges.remaining(player.uniqueId, runtime.settings.id, runtime.state.sequence) > 0

    fun cleanup(reason: String) {
        runtimes().filter { it.state.careType == FarmCareType.DITCH_RESCUE }.forEach(ditchWorld::restore)
        moles.cleanup(reason)
        scarecrows.cleanup(reason)
        entityLookup.inAllWorlds().asSequence().filter(::owns).forEach(Entity::remove)
        entities.clear()
        animalFollowers.clear()
        disease.clearAll()
        irrigation.clearAll()
        reconciledSequences.clear()
        pollenCharges.clearAll()
        debug.event("farm_care_cleanup", "reason" to reason)
    }

    fun initialize(
        runtime: FarmRuntime,
        actor: Player?,
        preferredType: FarmCareType? = null,
    ): Boolean {
        val sourceReady = if (preferredType == FarmCareType.SEEDER) {
            runtime.state.phase == FarmPhase.PREPARATION
        } else {
            runtime.state.phase == FarmPhase.HARVESTING
        }
        if (!sourceReady || runtime.state.careType != null) return false
        val selected = plans.select(runtime, preferredType, actor) ?: return false
        if (!starts.prepare(runtime, selected)) return false
        val result = FarmShiftEngine.startCare(runtime.state, selected.type, selected.targets, selected.goal)
        if (!result.accepted) {
            starts.rollback(runtime, selected)
            return false
        }
        transitions.apply(runtime, result, actor)
        if (selected.type == FarmCareType.DISEASE) disease.start(runtime, clock())
        ensure(runtime)
        state.persistAsync()
        return true
    }

    private fun interact(player: Player, entity: Entity, zoneId: String) {
        val runtime = runtimes().firstOrNull { it.settings.id == zoneId } ?: return
        val sequence = entity.persistentDataContainer.get(sequenceKey, PersistentDataType.LONG) ?: return
        val targetId = entity.persistentDataContainer.get(targetKey, PersistentDataType.INTEGER) ?: return
        val roleName = entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING) ?: return
        val role = runCatching { FarmCareRole.valueOf(roleName) }.getOrNull() ?: return
        if (runtime.state.phase != FarmPhase.CARE || runtime.state.sequence != sequence || !runtime.region.contains(entity.location)) return
        if (!access.hasAccess(player, runtime.settings.permission)) {
            audience.sendChat(player, MessageKey.ZONE_LOCKED)
            return
        }
        if (!access.allowInteraction(
                "farm-care-target:$zoneId:$targetId:${player.uniqueId}", runtime.settings.inputCooldowns.careTargetMillis,
            )) return
        if (role == FarmCareRole.PEN) {
            audience.sendActionBar(player, MessageKey.FARM_CARE_ANIMAL_PEN)
            return
        }
        val target = runtime.state.careTargets.firstOrNull { it.id == targetId && it.role == role } ?: return
        if (role == FarmCareRole.SEEDER_HORSE) {
            val key = FarmCareEntityKey(zoneId, targetId)
            val rig = seederRig.resolve(
                entities[key].orEmpty().mapNotNull(Bukkit::getEntity),
                runtime.settings.seederPigCount,
            ) ?: return
            when (seederRig.mount(rig, entity, player)) {
                FarmSeederMountResult.OCCUPIED -> {
                    audience.sendActionBar(player, MessageKey.FARM_CARE_SEEDER_OCCUPIED)
                    return
                }
                FarmSeederMountResult.RIDER_BUSY, FarmSeederMountResult.FAILED -> {
                    audience.sendActionBar(player, MessageKey.FARM_CARE_SEEDER_MOUNT_FAILED)
                    return
                }
                FarmSeederMountResult.MOUNTED -> Unit
            }
            if (!target.complete) {
                presentation.feedback(player, rig.horse.location, role, true)
                transitions.apply(
                    runtime,
                    FarmShiftEngine.startSeeder(runtime.state, target.id),
                    player,
                )
            }
            audience.sendActionBar(player, MessageKey.FARM_CARE_SEEDER_FOLLOWING)
            debug.event("farm_seeder_following", "zone" to zoneId, "player" to player.name)
            return
        }
        when (role) {
            FarmCareRole.WEED_ROOT, FarmCareRole.DISEASED_CROP -> if (!MaterialRules.isHoe(player.inventory.itemInMainHand)) {
                audience.sendActionBar(player, MessageKey.FARM_CARE_TOOL)
                return
            }
            FarmCareRole.VALVE -> Unit
            FarmCareRole.HIVE -> {
                pollenCharges.grant(
                    player.uniqueId,
                    runtime.settings.id,
                    runtime.state.sequence,
                    runtime.settings.pollinationCharges,
                )
                audience.sendActionBar(player, MessageKey.FARM_CARE_POLLEN_TAKEN)
                if (target.complete) return
            }
            FarmCareRole.FLOWER_PATCH -> {
                if (!pollenCharges.consume(player.uniqueId, runtime.settings.id, runtime.state.sequence)) {
                    audience.sendActionBar(player, MessageKey.FARM_CARE_POLLEN_REQUIRED)
                    return
                }
            }
            FarmCareRole.ANIMAL -> {
                if (runtime.state.careType == FarmCareType.DITCH_RESCUE) {
                    audience.sendActionBar(player, MessageKey.FARM_CARE_ANIMAL_ROD)
                    return
                }
                if (runtime.state.careType != FarmCareType.ANIMAL_RESCUE) return
                val key = FarmCareEntityKey(zoneId, targetId)
                animalFollowers[key] = player.uniqueId
                (entity as? Mob)?.let { mob ->
                    mob.isGlowing = true
                    mob.setLeashHolder(player)
                    mob.pathfinder.moveTo(player, runtime.settings.careAnimalFollowSpeed)
                }
                audience.sendActionBar(player, MessageKey.FARM_CARE_ANIMAL_FOLLOWING)
                debug.event("farm_care_animal_following", "zone" to zoneId, "target" to targetId, "player" to player.name)
                return
            }
            FarmCareRole.COVER_ANCHOR, FarmCareRole.SEEDER_WAYPOINT, FarmCareRole.APPLE -> Unit
            FarmCareRole.SCARECROW -> return
            FarmCareRole.MOLE_MOUND -> return
            FarmCareRole.SEEDER_HORSE -> return
            FarmCareRole.PEN -> return
        }
        if (role == FarmCareRole.VALVE) {
            if (irrigation.start(runtime, target, player)) {
                presentation.feedback(player, entity.location, role, false)
            }
            return
        }
        val result = FarmShiftEngine.advanceCare(runtime.state, target.id, player.uniqueId)
        if (!result.accepted) return
        val completed = result.state.careTargets.firstOrNull { it.id == target.id }?.complete != false
        presentation.feedback(player, entity.location, role, completed)
        transitions.apply(runtime, result, player)
    }

    fun ensure(runtime: FarmRuntime) {
        if (runtime.state.phase != FarmPhase.CARE) {
            scarecrows.ensure(runtime)
            if (entities.keys.any { it.zoneId == runtime.settings.id }) clear(runtime, "phase_inactive")
            disease.clear(runtime.settings.id)
            return
        }
        scarecrows.ensure(runtime)
        if (runtime.state.careType == FarmCareType.MOLES) {
            entities.keys.filter { it.zoneId == runtime.settings.id }.toList().forEach { removeEntities(it, "mole_expedition") }
            moles.ensure(runtime)
            return
        }
        if (runtime.state.careType == FarmCareType.DITCH_RESCUE) ditchWorld.ensure(runtime)
        val activeIds = runtime.state.careTargets.mapTo(mutableSetOf(), FarmCareTarget::id)
        val scarecrowIds = runtime.state.careTargets.asSequence()
            .filter { it.role == FarmCareRole.SCARECROW }
            .mapTo(hashSetOf(), FarmCareTarget::id)
        entities.keys.filter { it.zoneId == runtime.settings.id && it.targetId in scarecrowIds }
            .toList().forEach { removeEntities(it, "scarecrow_delivery") }
        entities.keys.filter { it.zoneId == runtime.settings.id && it.targetId >= 0 && it.targetId !in activeIds }
            .forEach { removeEntities(it, "stale_target") }
        var appleSpawnBudget = runtime.settings.appleSpawnsPerUpdate
        var targetSpawnBudget = runtime.settings.careSpawnsPerUpdate
        runtime.state.careTargets.forEach { target ->
            if (target.role == FarmCareRole.SCARECROW) return@forEach
            if (target.role == FarmCareRole.SEEDER_HORSE) return@forEach
            if (target.role == FarmCareRole.SEEDER_WAYPOINT) {
                removeEntities(FarmCareEntityKey(runtime.settings.id, target.id), "seeder_route_removed")
                return@forEach
            }
            if (target.complete && target.role != FarmCareRole.HIVE) {
                removeEntities(FarmCareEntityKey(runtime.settings.id, target.id), "target_complete")
                return@forEach
            }
            if (target.role == FarmCareRole.APPLE) {
                val alreadyActive = entities[FarmCareEntityKey(runtime.settings.id, target.id)].orEmpty()
                    .any { id -> Bukkit.getEntity(id)?.isValid == true }
                if (!alreadyActive && appleSpawnBudget <= 0) return@forEach
                if (!alreadyActive) appleSpawnBudget--
            } else {
                val alreadyActive = entities[FarmCareEntityKey(runtime.settings.id, target.id)].orEmpty()
                    .any { id -> Bukkit.getEntity(id)?.isValid == true }
                if (!alreadyActive && targetSpawnBudget <= 0) return@forEach
                if (!alreadyActive) targetSpawnBudget--
            }
            ensureFarmCareTarget(runtime, target)
        }
        if (runtime.state.careType == FarmCareType.ANIMAL_RESCUE) ensureFarmAnimalPen(runtime)
        if (runtime.state.careType == FarmCareType.DITCH_RESCUE) ensureRescueRods(runtime)
        if (runtime.state.careType == FarmCareType.SEEDER) ensureFarmSeeder(runtime)
    }

    fun reconcile(runtime: FarmRuntime) {
        val zoneId = runtime.settings.id
        if (runtime.state.phase == FarmPhase.CARE && runtime.state.careType == FarmCareType.MOLES) return
        if (runtime.state.phase != FarmPhase.CARE && entities.keys.none { it.zoneId == zoneId }) return
        if (reconciledSequences[zoneId] == runtime.state.sequence) return
        val targets = runtime.state.careTargets.associateBy(FarmCareTarget::id)
        entityLookup.inWorld(runtime.region.world).asSequence().filter { entity ->
            entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) == zoneId
        }.forEach { entity ->
            val sequence = entity.persistentDataContainer.get(sequenceKey, PersistentDataType.LONG)
            val targetId = entity.persistentDataContainer.get(targetKey, PersistentDataType.INTEGER)
            val role = entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING)
            val ordinaryTarget = targetId?.let(targets::get)
            val currentTarget = ordinaryTarget != null && ordinaryTarget.role != FarmCareRole.SCARECROW &&
                ordinaryTarget.role.name == role
            val currentPen = targetId == -1 && role == FarmCareRole.PEN.name &&
                runtime.state.careType == FarmCareType.ANIMAL_RESCUE
            val valid = runtime.state.phase == FarmPhase.CARE && runtime.state.sequence == sequence &&
                (currentTarget || currentPen)
            if (!valid) {
                entity.remove()
                debug.event(
                    "farm_care_stale_entity_removed",
                    "zone" to runtime.settings.id,
                    "target" to targetId,
                    "role" to role,
                )
                return@forEach
            }
            entities.getOrPut(FarmCareEntityKey(runtime.settings.id, requireNotNull(targetId)), ::linkedSetOf) += entity.uniqueId
        }
        reconciledSequences[zoneId] = runtime.state.sequence
    }

    private fun ensureFarmSeeder(runtime: FarmRuntime) {
        val target = runtime.state.careTargets.firstOrNull { it.role == FarmCareRole.SEEDER_HORSE } ?: return
        val key = FarmCareEntityKey(runtime.settings.id, target.id)
        if (!shouldSpawnSeederRig(runtime.state.preparationReleased)) return removeEntities(key, "field_release_pending")
        val active = entities[key].orEmpty().mapNotNull(Bukkit::getEntity).filter { entity ->
            entity.isValid &&
                entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) == runtime.settings.id &&
                entity.persistentDataContainer.get(sequenceKey, PersistentDataType.LONG) == runtime.state.sequence &&
                entity.persistentDataContainer.get(targetKey, PersistentDataType.INTEGER) == target.id &&
                entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING) == target.role.name
        }
        if (seederRig.resolve(active, runtime.settings.seederPigCount) != null) {
            entities[key] = active.mapTo(mutableSetOf(), Entity::getUniqueId)
            return
        }
        removeEntities(key, "replace_seeder")
        val world = Bukkit.getWorld(target.position.world) ?: return
        val location = Location(world, target.position.x, target.position.y, target.position.z)
        if (!runtime.region.contains(location) || !world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return
        if (!FarmSurfacePolicy.isSurfaceSpawn(location)) {
            state.log(Level.WARNING, "Skipped covered farm seeder target ${runtime.settings.id}/${target.id}")
            return
        }
        val rig = seederRig.spawn(
            location = location,
            pigCount = runtime.settings.seederPigCount,
            leadDistance = runtime.settings.seederPigLeadDistance,
            spacing = runtime.settings.seederPigSpacing,
            horseSpeed = runtime.settings.seederHorseSpeed,
            pigSpeed = runtime.settings.seederPigSpeed,
            labelText = locale.render(MessageKey.FARM_CARE_SEEDER_NAME),
            labelViewRange = 0.7f,
            mark = { entity -> mark(entity, runtime, target.id, target.role) },
            validPosition = runtime.region::contains,
        )
        entities[key] = rig.entities.mapTo(mutableSetOf(), Entity::getUniqueId)
        debug.event("farm_seeder_spawned", "zone" to runtime.settings.id, "x" to location.x, "y" to location.y, "z" to location.z)
    }

    private fun ensureFarmCareTarget(runtime: FarmRuntime, target: FarmCareTarget) {
        val key = FarmCareEntityKey(runtime.settings.id, target.id)
        val expected = if (target.role == FarmCareRole.ANIMAL) 1 else 2
        val visual = runtime.settings.careVisuals.getValue(target.role)
        val active = entities[key].orEmpty().mapNotNull(Bukkit::getEntity).filter { entity ->
                entity.isValid &&
                entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) == runtime.settings.id &&
                entity.persistentDataContainer.get(sequenceKey, PersistentDataType.LONG) == runtime.state.sequence &&
                entity.persistentDataContainer.get(targetKey, PersistentDataType.INTEGER) == target.id &&
                entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING) == target.role.name
        }
        val displayMatches = target.role == FarmCareRole.ANIMAL ||
            active.filterIsInstance<ItemDisplay>().singleOrNull()?.let(visual::matches) == true
        if (active.size == expected && displayMatches) {
            if (target.role == FarmCareRole.ANIMAL) {
                (active.singleOrNull() as? Mob)?.let { mob ->
                    mob.isGlowing = true
                }
            }
            entities[key] = active.mapTo(mutableSetOf(), Entity::getUniqueId)
            return
        }
        removeEntities(key, "replace_target")
        val world = Bukkit.getWorld(target.position.world) ?: return
        if (!world.isChunkLoaded(target.position.x.toInt() shr 4, target.position.z.toInt() shr 4)) return
        val location = if (target.role == FarmCareRole.ANIMAL && runtime.state.careType == FarmCareType.DITCH_RESCUE) {
            ditchWorld.spawnLocation(runtime, target) ?: return
        } else Location(world, target.position.x, target.position.y, target.position.z)
        if (!runtime.region.contains(location)) {
            state.log(Level.WARNING, "Farm care target ${runtime.settings.id}/${target.id} is outside ${runtime.region.label}")
            return
        }
        if (target.role in FARM_OUTDOOR_CARE_ROLES && !FarmSurfacePolicy.isSurfaceSpawn(location)) {
            if (access.allowInteraction("farm-care-covered:${runtime.settings.id}:${target.id}", TimeUnit.MINUTES.toMillis(5))) {
                state.log(Level.WARNING, "Skipped covered farm care target ${runtime.settings.id}/${target.id} (${target.role})")
            }
            return
        }
        if (target.role == FarmCareRole.APPLE) {
            val leaf = location.block
            if (!FarmBlockPolicy.isOrchardLeaf(leaf.type, leaf.getRelative(org.bukkit.block.BlockFace.DOWN).type)) {
                if (access.allowInteraction("farm-apple-anchor-missing:${runtime.settings.id}", TimeUnit.MINUTES.toMillis(5))) {
                    state.log(Level.WARNING, "Farm apple targets in ${runtime.settings.id} include an unavailable leaf anchor")
                }
                return
            }
        }
        if (target.role == FarmCareRole.ANIMAL) {
            if (!FarmSurfacePolicy.isSurfaceSpawn(location)) {
                if (access.allowInteraction("farm-animal-covered:${runtime.settings.id}:${target.id}", TimeUnit.MINUTES.toMillis(5))) {
                    state.log(Level.WARNING, "Skipped covered farm animal target ${runtime.settings.id}/${target.id}")
                }
                return
            }
            val typeName = runtime.settings.careAnimalEntities[
                java.lang.Math.floorMod(runtime.state.sequence.toInt() + target.id, runtime.settings.careAnimalEntities.size)
            ]
            val mob = world.spawnEntity(location, EntityType.valueOf(typeName)) as? Mob ?: return
            mob.isPersistent = false
            mob.removeWhenFarAway = false
            mob.isInvulnerable = true
            mob.isCollidable = false
            mob.isGlowing = true
            mark(mob, runtime, target.id, target.role)
            entities[key] = mutableSetOf(mob.uniqueId)
            debug.event("farm_care_animal_spawned", "zone" to runtime.settings.id, "target" to target.id, "entity" to typeName)
            return
        }
        val stack = ItemStack(MaterialRules.material(visual.material))
        if (visual.customModelData > 0) {
            val meta = stack.itemMeta
            meta.setCustomModelData(visual.customModelData)
            stack.itemMeta = meta
        }
        val displayOffset = if (target.role == FarmCareRole.APPLE) runtime.settings.appleDisplayYOffset else visual.displayYOffset
        val interactionOffset = if (target.role == FarmCareRole.APPLE) runtime.settings.appleInteractionYOffset else 0.05
        val display = world.spawn(location.clone().add(0.0, displayOffset, 0.0), ItemDisplay::class.java) { entity ->
            entity.setItemStack(stack)
            entity.itemDisplayTransform = visual.displayTransform.bukkit
            presentation.scale(
                entity,
                if (target.role == FarmCareRole.APPLE) runtime.settings.appleDisplayScale else visual.displayScale,
            )
            entity.viewRange = ru.ruscrafting.farms.paper.farm.FarmFieldPoiVisibility.fullField(runtime.settings.displayViewRange)
            entity.isGlowing = true
            entity.glowColorOverride = if (target.complete) FarmCarePresentation.SUCCESS_COLOR else presentation.color(target.role)
            entity.isPersistent = false
            mark(entity, runtime, target.id, target.role)
        }
        val interaction = world.spawn(location.clone().add(0.0, interactionOffset, 0.0), Interaction::class.java) { entity ->
            entity.interactionWidth = if (target.role == FarmCareRole.COVER_ANCHOR) 1.45f else 1.15f
            entity.interactionHeight = if (target.role == FarmCareRole.APPLE) 1.15f else 1.45f
            entity.isResponsive = true
            entity.isPersistent = false
            mark(entity, runtime, target.id, target.role)
        }
        entities[key] = mutableSetOf(display.uniqueId, interaction.uniqueId)
        debug.event(
            "farm_care_target_spawned",
            "zone" to runtime.settings.id,
            "target" to target.id,
            "role" to target.role,
            "x" to location.x,
            "y" to location.y,
            "z" to location.z,
        )
    }

    private fun ensureFarmAnimalPen(runtime: FarmRuntime) {
        val point = plans.fixturePoint(runtime, FarmPointKind.PEN) ?: return
        val key = FarmCareEntityKey(runtime.settings.id, -1)
        val active = entities[key].orEmpty().mapNotNull(Bukkit::getEntity).filter { entity ->
            entity.isValid &&
                entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) == runtime.settings.id &&
                entity.persistentDataContainer.get(sequenceKey, PersistentDataType.LONG) == runtime.state.sequence &&
                entity.persistentDataContainer.get(targetKey, PersistentDataType.INTEGER) == -1 &&
                entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING) == FarmCareRole.PEN.name
        }
        if (active.size == 2) return
        removeEntities(key, "replace_pen")
        val world = Bukkit.getWorld(point.world) ?: return
        if (!world.isChunkLoaded(point.x.toInt() shr 4, point.z.toInt() shr 4)) return
        val location = Location(world, point.x, point.y, point.z)
        if (!runtime.region.contains(location)) return
        val visual = runtime.settings.careVisuals.getValue(FarmCareRole.PEN)
        val stack = ItemStack(MaterialRules.material(visual.material)).also { item ->
            if (visual.customModelData > 0) item.itemMeta = item.itemMeta.also { it.setCustomModelData(visual.customModelData) }
        }
        val display = world.spawn(location.clone().add(0.0, 0.55, 0.0), ItemDisplay::class.java) { entity ->
            entity.setItemStack(stack)
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            entity.viewRange = ru.ruscrafting.farms.paper.farm.FarmFieldPoiVisibility.fullField(runtime.settings.displayViewRange)
            entity.isGlowing = true
            entity.glowColorOverride = FarmCarePresentation.SUCCESS_COLOR
            entity.isPersistent = false
            mark(entity, runtime, -1, FarmCareRole.PEN)
        }
        val interaction = world.spawn(location, Interaction::class.java) { entity ->
            entity.interactionWidth = 2.2f
            entity.interactionHeight = 1.8f
            entity.isResponsive = true
            entity.isPersistent = false
            mark(entity, runtime, -1, FarmCareRole.PEN)
        }
        entities[key] = mutableSetOf(display.uniqueId, interaction.uniqueId)
        debug.event(
            "farm_care_pen_spawned",
            "zone" to runtime.settings.id,
            "x" to location.x,
            "y" to location.y,
            "z" to location.z,
        )
    }

    fun updateAnimals(runtime: FarmRuntime) {
        if (runtime.state.phase != FarmPhase.CARE) return
        if (runtime.state.careType == FarmCareType.DITCH_RESCUE) {
            ensureRescueRods(runtime)
            return
        }
        if (runtime.state.careType != FarmCareType.ANIMAL_RESCUE) return
        val pen = plans.fixturePoint(runtime, FarmPointKind.PEN) ?: return
        val penLocation = Bukkit.getWorld(pen.world)?.let { Location(it, pen.x, pen.y, pen.z) } ?: return
        runtime.state.careTargets.filter { it.role == FarmCareRole.ANIMAL && !it.complete }.forEach { target ->
            val key = FarmCareEntityKey(runtime.settings.id, target.id)
            val mob = entities[key].orEmpty().asSequence()
                .mapNotNull(Bukkit::getEntity).filterIsInstance<Mob>().firstOrNull() ?: return@forEach
            val actor = animalFollowers[key]?.let(Bukkit::getPlayer)?.takeIf { player ->
                player.isOnline && runtime.region.contains(player.location)
            }
            if (actor != null && mob.world == penLocation.world &&
                mob.location.distanceSquared(penLocation) <= runtime.settings.animalDeliveryRadius * runtime.settings.animalDeliveryRadius
            ) {
                releaseFollower(key, mob, "delivered")
                presentation.feedback(actor, mob.location, FarmCareRole.ANIMAL, true)
                transitions.apply(runtime, FarmShiftEngine.advanceCare(runtime.state, target.id, actor.uniqueId), actor)
                return@forEach
            }
            if (!runtime.region.contains(mob.location)) {
                mob.teleport(Location(mob.world, target.position.x, target.position.y, target.position.z))
                releaseFollower(key, mob, "outside_zone")
                return@forEach
            }
            if (actor == null) {
                releaseFollower(key, mob, "actor_unavailable")
            } else if (
                mob.location.distanceSquared(actor.location) >
                    runtime.settings.careAnimalFollowDistance * runtime.settings.careAnimalFollowDistance
            ) {
                if (!mob.isLeashed || runCatching { mob.leashHolder }.getOrNull() != actor) mob.setLeashHolder(actor)
                mob.pathfinder.moveTo(actor, runtime.settings.careAnimalFollowSpeed)
                pullFarmAnimalTowardHolder(
                    mob = mob,
                    holder = actor,
                    followDistance = runtime.settings.careAnimalFollowDistance,
                    followSpeed = runtime.settings.careAnimalFollowSpeed,
                    impulseBase = runtime.settings.careAnimalFollowImpulseBase,
                    impulsePerBlock = runtime.settings.careAnimalFollowImpulsePerBlock,
                    impulseMax = runtime.settings.careAnimalFollowImpulseMax,
                    smoothing = runtime.settings.careAnimalFollowImpulseSmoothing,
                )
            }
        }
    }

    fun onFish(event: PlayerFishEvent): Boolean {
        if (event.state != PlayerFishEvent.State.CAUGHT_ENTITY) return false
        val caught = event.caught ?: return false
        val identity = identity(caught) ?: return false
        if (identity.role != FarmCareRole.ANIMAL) return false
        event.isCancelled = true
        event.expToDrop = 0
        event.hook.remove()
        val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return true
        if (runtime.state.phase != FarmPhase.CARE || runtime.state.careType != FarmCareType.DITCH_RESCUE ||
            runtime.state.sequence != identity.sequence || !access.hasAccess(event.player, runtime.settings.permission)
        ) return true
        val held = if (event.hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND) {
            event.player.inventory.itemInOffHand
        } else event.player.inventory.itemInMainHand
        if (serviceItems.identity(held) != rescueRodIdentity(runtime)) {
            audience.sendActionBar(event.player, MessageKey.FARM_CARE_ANIMAL_ROD)
            return true
        }
        val target = runtime.state.careTargets.firstOrNull {
            it.id == identity.targetId && it.role == FarmCareRole.ANIMAL && !it.complete
        } ?: return true
        presentation.feedback(event.player, caught.location, FarmCareRole.ANIMAL, true)
        caught.remove()
        entities[FarmCareEntityKey(identity.zoneId, identity.targetId)]?.remove(caught.uniqueId)
        val result = FarmShiftEngine.advanceCare(runtime.state, target.id, event.player.uniqueId)
        if (result.accepted) transitions.apply(runtime, result, event.player)
        debug.event(
            "farm_care_animal_fished",
            "zone" to identity.zoneId,
            "target" to identity.targetId,
            "player" to event.player.name,
        )
        return true
    }

    /**
     * WorldGuard may cancel the hook damage after the regular farm damage handler.
     * Reclaim only the exact tagged rescue interaction at MONITOR, after protection
     * plugins have made their decision, and explicitly attach the hook to the animal.
     */
    fun onRescueHookDamageMonitor(event: EntityDamageByEntityEvent): Boolean {
        val identity = identity(event.entity) ?: return false
        val hook = event.damager as? FishHook ?: return false
        if (!allowsRescueHook(event, identity)) return false
        FarmRescueHookAttachment.attach(event)
        debug.event(
            "farm_care_animal_hooked",
            "zone" to identity.zoneId,
            "target" to identity.targetId,
            "player" to ((hook.shooter as? Player)?.name ?: "unknown"),
        )
        return true
    }

    private fun allowsRescueHook(event: EntityDamageEvent, identity: FarmCareEntityIdentity): Boolean {
        if (identity.role != FarmCareRole.ANIMAL) return false
        val hook = (event as? EntityDamageByEntityEvent)?.damager as? FishHook ?: return false
        val player = hook.shooter as? Player ?: return false
        val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return false
        if (runtime.state.phase != FarmPhase.CARE || runtime.state.careType != FarmCareType.DITCH_RESCUE ||
            runtime.state.sequence != identity.sequence || !access.hasAccess(player, runtime.settings.permission)
        ) return false
        val targetActive = runtime.state.careTargets.any {
            it.id == identity.targetId && it.role == FarmCareRole.ANIMAL && !it.complete
        }
        if (!targetActive) return false
        return listOf(player.inventory.itemInMainHand, player.inventory.itemInOffHand).any { held ->
            serviceItems.identity(held) == rescueRodIdentity(runtime)
        }
    }

    fun isServiceItemActive(identity: ServiceItemIdentity): Boolean {
        if (identity.activity != ActivityKind.FARM || identity.itemId != RESCUE_ROD_ID) return false
        val runtime = runtimes().firstOrNull { it.settings.id == identity.zoneId } ?: return false
        return runtime.state.phase == FarmPhase.CARE && runtime.state.careType == FarmCareType.DITCH_RESCUE &&
            runtime.state.sequence == identity.sequence && runtime.state.placementSequence == identity.objectiveNonce
    }

    fun releaseServiceItem(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        if (identity.activity != ActivityKind.FARM || identity.itemId != RESCUE_ROD_ID) return
        debug.event(
            "farm_rescue_rod_released",
            "zone" to identity.zoneId,
            "player" to playerId,
            "reason" to reason,
        )
    }

    fun updateSeeder(runtime: FarmRuntime, processField: Boolean) {
        if (runtime.state.phase != FarmPhase.CARE || runtime.state.careType != FarmCareType.SEEDER) return
        if (!runtime.state.preparationReleased) return
        val horseTarget = runtime.state.careTargets.firstOrNull { it.role == FarmCareRole.SEEDER_HORSE } ?: return
        val key = FarmCareEntityKey(runtime.settings.id, horseTarget.id)
        val rig = seederRig.resolve(
            entities[key].orEmpty().mapNotNull(Bukkit::getEntity),
            runtime.settings.seederPigCount,
        ) ?: return
        val horse = rig.horse
        val actor = seederRig.rider(rig)?.takeIf { player ->
            player.isOnline && access.hasAccess(player, runtime.settings.permission) && runtime.region.contains(player.location)
        }
        if (actor == null) {
            seederRig.park(rig)
            return
        }
        if (!runtime.region.contains(horse.location)) {
            seederRig.release(rig)
            horse.teleport(Location(horse.world, horseTarget.position.x, horseTarget.position.y, horseTarget.position.z))
            audience.sendActionBar(actor, MessageKey.FARM_CARE_SEEDER_OUTSIDE)
            return
        }
        val workingAnimals = seederRig.update(
            rig = rig,
            pigCount = runtime.settings.seederPigCount,
            leadDistance = runtime.settings.seederPigLeadDistance,
            spacing = runtime.settings.seederPigSpacing,
            catchupDistance = runtime.settings.seederPigCatchupDistance,
            pigSpeed = runtime.settings.seederPigSpeed,
            validPosition = runtime.region::contains,
        ).filter { position ->
            runtime.region.contains(Location(runtime.region.world, position.x, position.y, position.z))
        }
        if (workingAnimals.isEmpty() || !processField) return
        val participants = seederRig.riders(rig).filterTo(linkedSetOf()) { player ->
            player.isOnline && access.hasAccess(player, runtime.settings.permission) && runtime.region.contains(player.location)
        }
        if (participants.isEmpty()) return
        val stage = requireNotNull(runtime.state.seederStage())
        val candidates = when (stage) {
            FarmSeederStage.TILLING -> runtime.state.preparationPatch.filterNot(runtime.state.tilledPlots::contains)
            FarmSeederStage.PLANTING -> runtime.state.tilledPlots.filterNot(runtime.state.plantedPlots::contains)
        }
        val reachable = FarmMachinePlanner.plotsInWorkingRadius(
            candidates = candidates,
            machines = workingAnimals,
            radius = runtime.settings.seederWorkingRadius,
        )
        val mutation = when (stage) {
            FarmSeederStage.TILLING -> machineBlocks.till(
                runtime.settings.id,
                reachable,
                runtime.settings.seederBlocksPerUpdate,
            )
            FarmSeederStage.PLANTING -> machineBlocks.restoreRecordedCrops(
                runtime.settings.id,
                reachable,
                runtime.settings.seederBlocksPerUpdate,
            )
        }
        if (mutation.processed.isNotEmpty()) {
            presentation.machineSwath(runtime, mutation.processed, stage)
            transitions.apply(
                runtime,
                FarmShiftEngine.workSeeder(runtime.state, mutation.processed, participants.mapTo(linkedSetOf(), Player::getUniqueId)),
                actor,
            )
        }
        if (runtime.state.phase != FarmPhase.CARE || runtime.state.careType != FarmCareType.SEEDER || !horse.isValid) return
    }

    fun updateDisease(runtime: FarmRuntime, now: Long) = disease.update(runtime, now)

    fun processIrrigation() = irrigation.process(runtimes())

    fun irrigationDryPlots(runtime: FarmRuntime): Set<FarmPlotPosition> = irrigation.dryPlots(runtime)

    fun onMoistureChange(event: org.bukkit.event.block.MoistureChangeEvent, runtime: FarmRuntime): Boolean =
        irrigation.onMoistureChange(event, runtime)

    fun onMove(event: org.bukkit.event.player.PlayerMoveEvent) {
        moles.onMove(event)
        scarecrows.onMove(event)
    }

    fun updateCarriedDisplays() = scarecrows.updateCarriedDisplays()

    fun onPlayerDeath(player: Player) = moles.onPlayerDeath(player)

    fun refreshPoint(runtime: FarmRuntime, kind: FarmPointKind, reason: String) {
        if (runtime.state.phase != FarmPhase.CARE) return
        if (kind == FarmPointKind.PEN) {
            if (runtime.state.careType == FarmCareType.ANIMAL_RESCUE) {
                removeEntities(FarmCareEntityKey(runtime.settings.id, -1), reason)
                ensureFarmAnimalPen(runtime)
            }
            return
        }
        val type = when (kind) {
            FarmPointKind.HIVE -> FarmCareType.POLLINATION
            FarmPointKind.IRRIGATION -> FarmCareType.IRRIGATION
            FarmPointKind.COVERS -> FarmCareType.STORM_COVERS
            FarmPointKind.SCARECROWS -> FarmCareType.SCARECROWS
            FarmPointKind.DITCH -> FarmCareType.DITCH_RESCUE
            else -> return
        }
        if (runtime.state.careType != type) return
        val previousTargets = runtime.state.careTargets
        val rebuilt = plans.targets(runtime, type, null) ?: return
        val merged = FarmCarePlanner.preserveProgress(previousTargets, rebuilt)
        clear(runtime, reason)
        runtime.state = runtime.state.copy(careTargets = merged)
        ensure(runtime)
        state.persistAsync()
    }

    private fun mark(entity: Entity, runtime: FarmRuntime, targetId: Int, role: FarmCareRole) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
        entity.persistentDataContainer.set(targetKey, PersistentDataType.INTEGER, targetId)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, role.name)
    }

    private fun removeEntities(key: FarmCareEntityKey, reason: String) {
        val ids = entities.remove(key).orEmpty()
        ids.forEach { id ->
            val entity = Bukkit.getEntity(id)
            if (entity is Mob) releaseFollower(key, entity, reason)
            entity?.remove()
        }
        animalFollowers.remove(key)
        if (ids.isNotEmpty()) debug.event("farm_care_entities_removed", "zone" to key.zoneId, "target" to key.targetId, "reason" to reason)
    }

    fun clear(runtime: FarmRuntime, reason: String) {
        if (runtime.state.careType == FarmCareType.DITCH_RESCUE) ditchWorld.restore(runtime)
        if (runtime.state.careType == FarmCareType.MOLES) moles.clear(runtime, reason)
        scarecrows.clear(runtime, reason)
        entities.keys.filter { it.zoneId == runtime.settings.id }.toList().forEach { removeEntities(it, reason) }
        pollenCharges.clear(runtime.settings.id, runtime.state.sequence)
        disease.clear(runtime.settings.id)
        irrigation.clear(runtime)
        reconciledSequences.remove(runtime.settings.id)
        val rod = rescueRodIdentity(runtime)
        Bukkit.getOnlinePlayers().forEach { player -> while (serviceItems.consume(player, rod)) Unit }
    }

    fun startSound(type: FarmCareType): Sound = presentation.startSound(type)

    private fun ensureRescueRods(runtime: FarmRuntime) {
        val identity = rescueRodIdentity(runtime)
        audience.players(runtime.region).filterNot(access::isAdminEditing).forEach { player ->
            val hasRod = player.inventory.storageContents.any { serviceItems.identity(it) == identity } ||
                serviceItems.identity(player.inventory.itemInOffHand) == identity
            if (!hasRod && serviceItems.issueHeld(
                    player,
                    identity,
                    Material.FISHING_ROD,
                    locale.render(MessageKey.FARM_CARE_ANIMAL_FISHING_ROD, player),
                ) == null
            ) audience.sendActionBar(player, MessageKey.FARM_ACTION_INVENTORY_FULL)
        }
    }

    private fun rescueRodIdentity(runtime: FarmRuntime) = ServiceItemIdentity(
        ActivityKind.FARM,
        runtime.settings.id,
        runtime.state.sequence,
        runtime.state.placementSequence,
        ObjectiveTargetRole("farm_care"),
        RESCUE_ROD_ID,
    )

    private fun releaseFollower(key: FarmCareEntityKey, mob: Mob?, reason: String) {
        val playerId = animalFollowers.remove(key)
        mob?.pathfinder?.stopPathfinding()
        if (mob?.isLeashed == true) runCatching { mob.setLeashHolder(null) }
        if (playerId != null) {
            debug.event("farm_care_animal_released", "zone" to key.zoneId, "target" to key.targetId, "reason" to reason)
        }
    }

    private companion object {
        const val RESCUE_ROD_ID = "ditch_rescue_rod"
    }
}
