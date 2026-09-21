package ru.ruscrafting.farms.paper.mine.expedition

import io.papermc.paper.entity.TeleportFlag
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.entity.Interaction
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.mine.expedition.ExpeditionPoint
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionMachines
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionMotion
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlan
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionStage
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionState
import kotlin.math.abs
import kotlin.math.sin
import java.util.UUID
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Paper owner for the few native entities that make an expedition machine
 * readable. The real machine body is always projected through [project], so
 * the prepared-scene journal remains the only owner of block mutations.
 */
internal class MineExpeditionMachinery(
    private val plugin: Plugin,
    private val project: (MineExpeditionScene, Map<ExpeditionPoint, String>) -> Boolean,
    private val furnishingPoint: (MineExpeditionScene,String,ExpeditionPoint) -> ExpeditionPoint = { _,_,p -> p },
) {
    private data class SceneKey(val world: String, val zone: String, val sequence: Long, val nonce: Long)

    private data class Runtime(
        val key: SceneKey,
        val seats: MutableMap<UUID, Interaction> = linkedMapOf(),
        val displays: MutableMap<String, BlockDisplay> = linkedMapOf(),
        var projectedCenter: ExpeditionPoint? = null,
        var center: ExpeditionPoint? = null,
        var lastParticleAt: Long = 0L,
        val crankAngles: MutableMap<String, Double> = linkedMapOf(),
    )

    private val runtimes = linkedMapOf<SceneKey, Runtime>()
    private val machineKey = NamespacedKey(plugin, "mine_expedition_machine")
    private val roleKey = NamespacedKey(plugin, "mine_expedition_machine_role")
    private val zoneKey = NamespacedKey(plugin, "mine_expedition_machine_zone")
    private val sequenceKey = NamespacedKey(plugin, "mine_expedition_machine_sequence")
    private val nonceKey = NamespacedKey(plugin, "mine_expedition_machine_nonce")

    /** Rebuilds visual state after reload and journals a stopped machine body. */
    fun sync(scene: MineExpeditionScene, state: MineExpeditionState): Boolean {
        if (!scene.ready || !scene.contains(scene.at(scene.plan.spawn))) return false
        val local = localCenter(scene, state)
        val runtime = runtime(scene, state, local) ?: return false
        if (runtime.seats.isNotEmpty() && runtime.projectedCenter != local) return rollback(scene, state)
        val blueprint = translatedBlueprint(scene, runtime.projectedCenter, local)
        if (blueprint.isNotEmpty() && runtime.projectedCenter != local) {
            if (!project(scene, blueprint)) return false
            runtime.projectedCenter = local
        }
        runtime.center = local
        return true
    }

    /** Player-facing center used by guidance and objective placement. */
    fun localCenter(scene: MineExpeditionScene, state: MineExpeditionState): ExpeditionPoint = when (state.stage) {
        MineExpeditionStage.DESCENT_MIDDLE,
        MineExpeditionStage.DESCENT_BOTTOM,
            -> MineExpeditionMotion.position(scene.plan, state)
        MineExpeditionStage.DESCENT_COUNTERWEIGHTS,
        MineExpeditionStage.DESCENT_POWER_CELLS,
            -> scene.plan.stations.getValue("lift_middle")
        MineExpeditionStage.DESCENT_CORE_VALVES,
        MineExpeditionStage.DESCENT_ENGINE,
            -> scene.plan.stations.getValue("lift_bottom")

        MineExpeditionStage.ARK_FUEL -> scene.plan.stations.getValue("ark_start")
        MineExpeditionStage.ARK_FORK -> MineExpeditionMotion.position(scene.plan, state)
        MineExpeditionStage.ARK_BRANCH,
        MineExpeditionStage.ARK_JAM,
        MineExpeditionStage.ARK_COOLANT,
            -> scene.plan.stations.getValue("ark_mid")
        MineExpeditionStage.ARK_CHAMBER,
        MineExpeditionStage.ARK_HOME,
            -> MineExpeditionMotion.position(scene.plan, state)
        MineExpeditionStage.ARK_CORES,
            -> scene.plan.stations.getValue("ark_end")

        MineExpeditionStage.FACTORY_WATER,
        MineExpeditionStage.FACTORY_COAL,
        MineExpeditionStage.FACTORY_HEAT,
        MineExpeditionStage.FACTORY_POUR,
        MineExpeditionStage.FACTORY_CRANE,
        MineExpeditionStage.FACTORY_INSTALL,
            -> scene.plan.stations.getValue("crane_control")
        MineExpeditionStage.COMPLETE -> when (scene.kind) {
            MineExpeditionKind.LAST_DESCENT -> scene.plan.stations.getValue("lift_bottom")
            MineExpeditionKind.DRILLING_ARK -> scene.plan.stations.getValue("ark_start")
            MineExpeditionKind.DEAD_FACTORY -> scene.plan.stations.getValue("crane_control")
        }
    }

    fun center(scene: MineExpeditionScene, state: MineExpeditionState): Location? = scene.at(localCenter(scene, state))

    fun motionSteps(scene: MineExpeditionScene, state: MineExpeditionState): Int =
        if (isMotion(state.stage)) MineExpeditionMotion.steps(scene.plan, state) else 0

    /**
     * Projects the target machine first, then moves the native seats with
     * RETAIN_PASSENGERS. No player is teleported by this class.
     */
    fun advance(
        scene: MineExpeditionScene,
        before: MineExpeditionState,
        after: MineExpeditionState,
        participants: Collection<Player>,
    ): Boolean {
        if (!scene.ready || !isMotion(before.stage) || before.placement != after.placement) return false
        val oldCenter = localCenter(scene, before)
        val target = targetAfter(scene.plan, before, after)
        if (target == oldCenter && before.stage == after.stage) return false
        val runtime = runtime(scene, before, oldCenter) ?: return false
        val playerList = participants.filter { player ->
            player.isOnline && player.world === scene.world &&
                onDeck(scene, player.location, oldCenter)
        }
        if (playerList.isEmpty()) return false
        if (playerList.any { it.vehicle != null && it.vehicle !== runtime.seats[it.uniqueId] }) return false
        val boarded = mutableListOf<Player>()
        for (player in playerList) {
            val carrier = runtime.seats.getOrPut(player.uniqueId) { spawnCarrier(scene, player.location) }
            if (player.vehicle == null) {
                if (!carrier.addPassenger(player)) {
                    boarded.forEach(::release)
                    return false
                }
                boarded += player
            }
        }

        val targetBlocks = translatedBlueprint(scene, oldCenter, target)
        if (targetBlocks.isNotEmpty() && !project(scene, targetBlocks)) {
            boarded.forEach(::release)
            return false
        }
        val previousSeats = runtime.seats.values.associateWith { it.location.clone() }
        val delta = scene.at(target).toVector().subtract(scene.at(oldCenter).toVector())
        val moved = previousSeats.keys.all { seat ->
            seat.teleport(seat.location.clone().add(delta), TeleportFlag.EntityState.RETAIN_PASSENGERS)
        }
        if (!moved) {
            // The callback is journal-owned and can retry this exact target on
            // the next authorized tick; first restore the old body when the
            // carrier itself rejected the move. Do not teleport passengers directly.
            val rollback = translatedBlueprint(scene, target, oldCenter)
            if (rollback.isNotEmpty() && project(scene, rollback)) {
                runtime.projectedCenter = oldCenter
                runtime.center = oldCenter
            } else {
                // Keep the projected marker honest so sync() retries the
                // target-to-old restoration after the journal becomes ready.
                runtime.projectedCenter = target
            }
            previousSeats.forEach { (seat, location) -> seat.teleport(location, TeleportFlag.EntityState.RETAIN_PASSENGERS) }
            boarded.forEach(::release)
            return false
        }
        runtime.center = target
        runtime.projectedCenter = target
        return true
    }

    /** Restore a rejected domain step without separating passengers from the deck. */
    fun rollback(scene: MineExpeditionScene, state: MineExpeditionState): Boolean {
        val runtime = runtimes[key(scene)] ?: return true
        val target = localCenter(scene, state)
        val previous = runtime.projectedCenter ?: return true
        if (previous == target) return true
        if (!project(scene, translatedBlueprint(scene, previous, target))) return false
        val delta = scene.at(target).toVector().subtract(scene.at(previous).toVector())
        runtime.seats.values.forEach { seat ->
            seat.teleport(seat.location.clone().add(delta), TeleportFlag.EntityState.RETAIN_PASSENGERS)
        }
        runtime.center = target
        runtime.projectedCenter = target
        park(scene)
        return true
    }

    /** Bounded visual animation; factory effects are sparse particles, not entity spam. */
    fun animate(scene: MineExpeditionScene, state: MineExpeditionState, now: Long, cargoClaimed: Boolean = false) {
        if (!scene.ready) return
        val local = localCenter(scene, state)
        val runtime = runtime(scene, state, local) ?: return
        positionDisplays(scene, runtime, local, state, now, cargoClaimed)
        val phase = (now % ANIMATION_PERIOD).toDouble() / ANIMATION_PERIOD * Math.PI * 2.0
        if (scene.kind == MineExpeditionKind.DEAD_FACTORY && scene.placement.geometryVersion<3 && now - runtime.lastParticleAt >= PARTICLE_PERIOD &&
            state.stage != MineExpeditionStage.FACTORY_WATER) {
            runtime.lastParticleAt = now
            val furnace = scene.at(if(scene.placement.geometryVersion>=3) furnishingPoint(scene,"decor_furnace_left",ExpeditionPoint(-17,12,-13)) else ExpeditionPoint(14,11,1))
            scene.world.spawnParticle(Particle.SMALL_FLAME, furnace.add(0.0, 4.0, 0.0), 2, 0.16, 0.24, 0.16, 0.0)
            val wheel = scene.at(if(scene.placement.geometryVersion>=3) furnishingPoint(scene,"decor_waterwheel",ExpeditionPoint(0,11,-23)) else ExpeditionPoint(-15,11,0))
            scene.world.spawnParticle(Particle.DRIPPING_WATER, wheel.add(0.0, sin(phase) * 0.5, 0.0), 1, 0.08, 0.08, 0.08, 0.0)
            val molten = scene.at(if(scene.placement.geometryVersion>=3) furnishingPoint(scene,"pour_control",ExpeditionPoint(17,5,-6)) else ExpeditionPoint(12,5,-6))
            scene.world.spawnParticle(Particle.FLAME, molten.add(0.0, 0.12, 0.0), 1, 0.14, 0.02, 0.14, 0.0)
        }
    }

    /** Explicit departure path for a player who dismounts or leaves the activity. */
    fun release(player: Player) {
        runtimes.values.forEach { runtime -> runtime.seats.remove(player.uniqueId)?.let { seat ->
            if (player.vehicle === seat) player.leaveVehicle()
            seat.remove()
        } }
    }

    fun riding(scene: MineExpeditionScene, player: Player): Boolean =
        runtimes[key(scene)]?.seats?.get(player.uniqueId)?.let { player.vehicle === it } == true

    fun park(scene: MineExpeditionScene) {
        runtimes[key(scene)]?.seats?.let { seats ->
            seats.values.forEach { it.eject(); it.remove() }
            seats.clear()
        }
    }

    fun turns(scene:MineExpeditionScene):Map<String,Double> = runtimes[key(scene)]?.crankAngles.orEmpty()

    fun turn(scene: MineExpeditionScene, id: String, radians: Double) {
        runtimes[key(scene)]?.crankAngles?.set(id, radians)
    }

    fun clear(scene: MineExpeditionScene) {
        val key = key(scene)
        runtimes.remove(key)?.let(::remove)
    }

    fun cleanup() {
        runtimes.values.toList().forEach(::remove)
        runtimes.clear()
    }

    private fun runtime(scene: MineExpeditionScene, state: MineExpeditionState, local: ExpeditionPoint): Runtime? {
        val key = key(scene)
        val current = runtimes[key] ?: Runtime(key).also { runtimes[key] = it }
        val roles = displayAnchors(scene, local).keys
        roles.forEach { role ->
            val display = current.displays[role]?.takeIf(Entity::isValid)
                ?: find(scene, local, role) as? BlockDisplay
                ?: spawnDisplay(scene, local, role)
            if (display != null) current.displays[role] = display
        }
        return current
    }

    private fun spawnCarrier(scene: MineExpeditionScene, location: Location): Interaction =
        scene.world.spawn(location.clone().add(0.0, 0.02, 0.0), Interaction::class.java) { seat ->
            mark(seat, "carrier", scene)
            seat.interactionWidth = 0.01f
            seat.interactionHeight = 0.01f
            seat.isResponsive = false
            seat.isInvulnerable = true
            seat.isPersistent = false
        }

    private fun spawnDisplay(scene: MineExpeditionScene, local: ExpeditionPoint, role: String): BlockDisplay? {
        val material = displayMaterial(scene.kind, role) ?: return null
        return scene.world.spawn(scene.at(local), BlockDisplay::class.java) { display ->
            mark(display, role, scene)
            display.block = material.createBlockData()
            display.brightness = MineDisplayLighting.brightness(material)
            display.viewRange = 5f
            display.interpolationDuration = 4
            display.teleportDuration = 4
            display.isPersistent = false
            display.isGlowing = false
            display.glowColorOverride = when (scene.kind) {
                MineExpeditionKind.LAST_DESCENT -> Color.fromRGB(0x48, 0xd5, 0xff)
                MineExpeditionKind.DRILLING_ARK -> Color.fromRGB(0xff, 0xb2, 0x49)
                MineExpeditionKind.DEAD_FACTORY -> Color.fromRGB(0xff, 0x67, 0x32)
            }
            display.transformation = display.transformation.also { transform ->
                transform.scale.set(0.82f, 0.82f, 0.82f)
            }
        }
    }

    private fun positionDisplays(
        scene: MineExpeditionScene,
        runtime: Runtime,
        local: ExpeditionPoint,
        state: MineExpeditionState,
        now: Long,
        cargoClaimed: Boolean,
    ) {
        val phase = (now % ANIMATION_PERIOD).toDouble() / ANIMATION_PERIOD * Math.PI * 2.0
        displayAnchors(scene, local).forEach { (role, anchor) ->
            val display = runtime.displays[role] ?: return@forEach
            if (!display.isValid) return@forEach
            val pivot = scene.at(anchor)
            val activeFactory = state.stage != MineExpeditionStage.FACTORY_WATER
            val crank = runtime.crankAngles["crane_control"] ?: 0.0
            val craneTravel = when (state.stage) {
                MineExpeditionStage.FACTORY_INSTALL, MineExpeditionStage.COMPLETE -> 1.0
                MineExpeditionStage.FACTORY_CRANE -> (crank / (Math.PI * 2)).coerceIn(0.0, 1.0)
                else -> 0.0
            }
            var chainLength = 8f
            if (scene.kind == MineExpeditionKind.DEAD_FACTORY && role in setOf("crane", "core")) {
                val modern = scene.placement.geometryVersion >= 3
                val source = scene.plan.stations.getValue("pour_control")
                val destination = scene.plan.stations["crane_load"] ?: scene.plan.stations.getValue("assembly_socket")
                val from = if (modern) furnishingPoint(scene, "pour_control", source) else ExpeditionPoint(12, 5, -6)
                val to = if (modern) furnishingPoint(scene, if ("crane_load" in scene.plan.stations) "crane_load" else "assembly_socket", destination)
                    else ExpeditionPoint(0, 5, -6)
                pivot.x = scene.at(from).x + (to.x - from.x) * craneTravel
                pivot.z = scene.at(from).z + (to.z - from.z) * craneTravel
                val loadY = scene.placement.originY + from.y + 2.2 +
                    (to.y - from.y - .2) * craneTravel + sin(craneTravel * Math.PI) * 4.0
                val ceilingY = scene.placement.originY + 17.0
                chainLength = (ceilingY - (loadY + .5)).coerceAtLeast(.1).toFloat()
                pivot.y = if (role == "core") loadY else ceilingY - chainLength / 2
            }
            val pressing=state.stage==MineExpeditionStage.FACTORY_INSTALL && (runtime.crankAngles["assembly_socket"] ?: 0.0)>0
            val poured=if(state.stage==MineExpeditionStage.FACTORY_POUR)
                ((runtime.crankAngles["pour_control"] ?: 0.0)/(Math.PI*2)).coerceIn(0.0,1.0).toFloat() else 0f
            if(scene.kind==MineExpeditionKind.DEAD_FACTORY && role=="core" && pressing) {
                val press=furnishingPoint(scene,"assembly_socket",scene.plan.stations.getValue("assembly_socket"))
                val at=scene.at(press).add(0.0,1.95,0.0)
                pivot.x=at.x; pivot.y=at.y; pivot.z=at.z
            }
            if(scene.kind==MineExpeditionKind.DEAD_FACTORY && role=="molten") {
                val bed=furnishingPoint(scene,"pour_control",scene.plan.stations.getValue("pour_control"))
                pivot.y=scene.at(bed).y+1.73+poured*.4
            }
            if (display.location.distanceSquared(pivot) > 0.0001) display.teleport(pivot)
            val rotation = Quaternionf()
            val scale = when {
                role.startsWith("wheel") -> {
                    rotation.rotateZ((if (activeFactory) phase else 0.0).toFloat() + if (role == "wheel_cross") (Math.PI / 2).toFloat() else 0f)
                    Vector3f(10f, 0.4f, 0.6f)
                }
                role.startsWith("drill") -> {
                    rotation.rotateZ(phase.toFloat() * 2 + if (role == "drill_cross") (Math.PI / 2).toFloat() else 0f)
                    Vector3f(3.6f, 0.55f, 0.65f)
                }
                scene.kind == MineExpeditionKind.DEAD_FACTORY && role == "crane" -> Vector3f(0.3f, chainLength, 0.3f)
                scene.kind == MineExpeditionKind.DEAD_FACTORY && role == "core" -> {
                    val visible = state.stage == MineExpeditionStage.FACTORY_CRANE ||
                        (state.stage == MineExpeditionStage.FACTORY_INSTALL && !cargoClaimed)
                    if (visible || pressing) Vector3f(1.6f, 1.0f, 1.6f) else Vector3f(0f)
                }
                scene.kind == MineExpeditionKind.DEAD_FACTORY && role == "molten" -> {
                    if(poured>0) Vector3f(3.4f,.1f,2.2f) else Vector3f(0f)
                }
                else -> Vector3f(0.8f)
            }
            val translation = rotation.transform(Vector3f(scale).mul(-0.5f))
            MineDisplayPose.apply(display, Transformation(translation, rotation, scale, Quaternionf()))
        }
    }

    private fun translatedBlueprint(
        scene: MineExpeditionScene,
        previous: ExpeditionPoint?,
        center: ExpeditionPoint,
    ): Map<ExpeditionPoint, String> {
        val body = MineExpeditionMachines.blocks(scene.kind)
        if (body.isEmpty()) return emptyMap()
        val changes = linkedMapOf<ExpeditionPoint, String>()
        if (previous != null && previous != center) body.keys.forEach { relative ->
            val point = previous.offset(relative.x, relative.y, relative.z)
            // Restore the captured terrain recipe below the moving body. Missing
            // sparse cells are natural solid stone by the scene contract.
            changes[point] = scene.plan.blocks[point] ?: "minecraft:stone"
        }
        body.forEach { (relative, block) ->
            changes[center.offset(relative.x, relative.y, relative.z)] = block
        }
        require(changes.size <= 1_024) { "Moving machine projection is too large" }
        return changes
    }

    private fun targetAfter(plan: MineExpeditionPlan, before: MineExpeditionState, after: MineExpeditionState): ExpeditionPoint {
        val route = MineExpeditionMotion.points(plan, before)
        return if (after.stage != before.stage) route.last()
        else route[after.motionStep.coerceIn(0, route.lastIndex)]
    }

    private fun displayAnchors(scene: MineExpeditionScene, center: ExpeditionPoint): Map<String, ExpeditionPoint> = when (scene.kind) {
        MineExpeditionKind.LAST_DESCENT -> linkedMapOf(
            "drive" to center.offset(0, 2, -2),
            "frame" to center.offset(-2, 1, -2),
            "core" to center.offset(2, 1, -2),
        )
        MineExpeditionKind.DRILLING_ARK -> linkedMapOf(
            "drive" to center.offset(0, 2, -4),
            "boiler" to center.offset(0, 1, -2),
            "lamp" to center.offset(-3, 2, 0),
            "drill" to center.offset(0, 1, 8),
            "drill_cross" to center.offset(0, 1, 8),
        )
        MineExpeditionKind.DEAD_FACTORY -> linkedMapOf(
            "wheel" to ExpeditionPoint(if (scene.placement.geometryVersion >= 3) -24 else -15, 11, 0),
            "wheel_cross" to ExpeditionPoint(if (scene.placement.geometryVersion >= 3) -24 else -15, 11, 0),
            "crane" to ExpeditionPoint(0, 16, -6),
            "boiler" to if(scene.placement.geometryVersion>=3) furnishingPoint(scene,"decor_furnace_left",ExpeditionPoint(-17,12,-13)) else ExpeditionPoint(14,11,1),
            "molten" to if(scene.placement.geometryVersion>=3) furnishingPoint(scene,"pour_control",scene.plan.stations.getValue("pour_control").offset(dy=2)) else ExpeditionPoint(12,6,-6),
            "core" to ExpeditionPoint(0, 6, -6),
        ).filterKeys { scene.placement.geometryVersion < 3 || (!it.startsWith("wheel") && it != "boiler") }
    }

    private fun displayMaterial(kind: MineExpeditionKind, role: String): Material? = when (kind) {
        MineExpeditionKind.LAST_DESCENT -> when (role) {
            "drive" -> Material.REDSTONE_BLOCK
            "frame" -> Material.COPPER_BLOCK
            "core" -> Material.SEA_LANTERN
            else -> null
        }
        MineExpeditionKind.DRILLING_ARK -> when (role) {
            "drive" -> Material.COPPER_BLOCK
            "boiler" -> Material.BLAST_FURNACE
            "lamp" -> Material.LANTERN
            "drill", "drill_cross" -> Material.IRON_BLOCK
            else -> null
        }
        MineExpeditionKind.DEAD_FACTORY -> when (role) {
            "wheel", "wheel_cross" -> Material.DARK_OAK_PLANKS
            "crane" -> Material.IRON_CHAIN
            "boiler" -> Material.COPPER_BLOCK
            "molten" -> Material.MAGMA_BLOCK
            "core" -> Material.IRON_BLOCK
            else -> null
        }
    }

    private fun find(scene: MineExpeditionScene, local: ExpeditionPoint, role: String): Entity? {
        val center = scene.at(local)
        return scene.world.getNearbyEntities(center, SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS).firstOrNull { entity ->
            isOwned(entity) && entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING) == role &&
                entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) == scene.zoneId &&
                entity.persistentDataContainer.get(sequenceKey, PersistentDataType.LONG) == scene.sequence &&
                entity.persistentDataContainer.get(nonceKey, PersistentDataType.LONG) == scene.objectiveNonce
        }
    }

    private fun mark(entity: Entity, role: String, scene: MineExpeditionScene) {
        entity.persistentDataContainer.apply {
            set(machineKey, PersistentDataType.INTEGER, 1)
            set(roleKey, PersistentDataType.STRING, role)
            set(zoneKey, PersistentDataType.STRING, scene.zoneId)
            set(sequenceKey, PersistentDataType.LONG, scene.sequence)
            set(nonceKey, PersistentDataType.LONG, scene.objectiveNonce)
        }
    }

    private fun isOwned(entity: Entity): Boolean =
        entity.persistentDataContainer.get(machineKey, PersistentDataType.INTEGER) == 1

    private fun remove(runtime: Runtime) {
        runtime.seats.values.forEach { it.eject(); it.remove() }
        runtime.displays.values.forEach(Entity::remove)
        runtime.seats.clear()
        runtime.displays.clear()
    }

    private fun key(scene: MineExpeditionScene): SceneKey =
        SceneKey(scene.world.name, scene.zoneId, scene.sequence, scene.objectiveNonce)

    private fun near(player: Location, center: Location, radius: Double): Boolean =
        player.world === center.world && player.distanceSquared(center) <= radius * radius

    private fun onDeck(scene: MineExpeditionScene, player: Location, local: ExpeditionPoint): Boolean {
        val center = scene.at(local)
        val lift = scene.kind == MineExpeditionKind.LAST_DESCENT
        return player.world === scene.world && abs(player.y - center.y) <= 2.5 &&
            abs(player.x - center.x) <= (if (lift) 2.9 else 3.9) && abs(player.z - center.z) <= (if (lift) 2.0 else 5.9)
    }

    private fun isMotion(stage: MineExpeditionStage): Boolean = stage in MOVING_STAGES

    private companion object {
        val MOVING_STAGES = setOf(
            MineExpeditionStage.DESCENT_MIDDLE,
            MineExpeditionStage.DESCENT_BOTTOM,
            MineExpeditionStage.ARK_FORK,
            MineExpeditionStage.ARK_CHAMBER,
            MineExpeditionStage.ARK_HOME,
        )
        const val SEARCH_RADIUS = 40.0
        const val ANIMATION_PERIOD = 4_000L
        const val PARTICLE_PERIOD = 350L
    }
}
