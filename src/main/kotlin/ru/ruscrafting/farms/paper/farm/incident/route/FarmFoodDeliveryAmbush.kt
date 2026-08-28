package ru.ruscrafting.farms.paper.farm.incident.route

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.EntityType
import org.bukkit.entity.Horse
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Phantom
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmNightShiftController
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import java.util.UUID
import java.util.random.RandomGenerator
import kotlin.math.cos
import kotlin.math.sin

/** Spawns bounded mixed waves and owns the cart breakdown/recovery loop. */
internal class FarmFoodDeliveryAmbush(
    private val random: RandomGenerator,
    private val night: FarmNightShiftController,
    private val port: WorksiteRuntimePort,
    private val debug: ArcFarmsDebug,
    private val setRemoveWhenFarAway: (LivingEntity, Boolean) -> Unit,
    private val ejectPassengers: (Horse) -> Boolean,
) {
    fun update(
        runtime: FarmRuntime,
        horse: Horse,
        session: FarmFoodDeliverySession,
        points: List<FarmPointPosition>,
        location: (FarmPointPosition) -> Location,
        safeSurface: (Location) -> Location?,
        mark: (Mob) -> Unit,
    ) {
        val config = runtime.settings.routeDelivery
        session.monsterIds.removeIf { entityId ->
            val entity = Bukkit.getEntity(entityId)
            val gone = entity == null || !entity.isValid || entity.isDead
            if (gone) releaseLight(runtime.settings.id, entityId)
            gone
        }
        if (session.brokenDown) {
            if (session.monsterIds.isNotEmpty()) return
            if (!session.finishWaveIfCleared()) return
            players(session).forEach { player ->
                port.sendActionBar(player, MessageKey.FARM_ROUTE_REPAIRED)
                player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 0.65f, 1.35f)
            }
            debug.event("farm_food_cart_repaired", "zone" to runtime.settings.id, "sequence" to session.sequence)
            return
        }
        val rider = horse.passengers.filterIsInstance<Player>().firstOrNull() ?: return
        session.riderId = rider.uniqueId
        if (config.monsterMaxAlive == 0) return
        val checkpoint = session.pendingAmbushCheckpoints.firstOrNull() ?: return
        if (runtime.state.incidentProgress < checkpoint) return
        // The recorded route may start deep inside a large farm. The farm itself
        // is always a safe zone even if configuration or sparse samples drift.
        if (runtime.region.contains(horse.location)) return
        val alive = session.monsterIds.size
        if (alive >= config.monsterMaxAlive) return
        val anchor = location(points[(checkpoint + 2).coerceAtMost(points.lastIndex)])
        val requested = random.nextInt(config.monsterWaveMin, config.monsterWaveMax + 1).coerceAtMost(
            config.monsterMaxAlive - alive,
        )
        var spawned = 0
        repeat(requested) { index ->
            val angle = random.nextDouble() * Math.PI * 2 + index * (Math.PI * 2 / requested.coerceAtLeast(1))
            val distance = config.monsterSpawnDistance * random.nextDouble(0.8, 1.15)
            val ground = safeSurface(anchor.clone().add(cos(angle) * distance, 0.0, sin(angle) * distance)) ?: return@repeat
            if (runtime.region.contains(ground)) return@repeat
            val type = EntityType.valueOf(config.monsterTypes[random.nextInt(config.monsterTypes.size)])
            val spawn = if (type == EntityType.PHANTOM) ground.clone().add(0.0, PHANTOM_SPAWN_HEIGHT, 0.0) else ground
            val monster = spawn.world.spawnEntity(spawn, type) as Mob
            monster.isPersistent = false
            setRemoveWhenFarAway(monster, true)
            monster.isGlowing = true
            monster.target = rider
            monster.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = config.monsterMovementSpeed
            if (monster is Phantom) {
                monster.setShouldBurnInDay(false)
                monster.anchorLocation = ground
                monster.size = 1
            } else if (type == EntityType.HUSK || type == EntityType.ZOMBIE) {
                monster.equipment.setItemInMainHand(ItemStack(Material.TORCH), true)
                monster.equipment.itemInMainHandDropChance = 0.0f
            } else if (type == EntityType.SKELETON) {
                monster.equipment.setItemInMainHand(ItemStack(Material.WOODEN_SWORD), true)
                monster.equipment.itemInMainHandDropChance = 0.0f
            }
            mark(monster)
            session.monsterIds += monster.uniqueId
            session.spawnedMonsters++
            spawned++
            night.updateExternalLight(lightOwner(runtime.settings.id, monster.uniqueId), monster, config.monsterLightLevel)
        }
        if (spawned == 0) return
        session.pendingAmbushCheckpoints.removeFirst()
        session.brokenDown = true
        ejectPassengers(horse)
        horse.setAI(false)
        horse.velocity = Vector()
        players(session).forEach { player ->
            player.playSound(player.location, Sound.ENTITY_RAVAGER_ROAR, 0.75f, 0.85f)
            port.sendActionBar(player, MessageKey.FARM_ROUTE_BROKEN)
        }
        debug.event(
            "farm_food_cart_broken", "zone" to runtime.settings.id, "sequence" to session.sequence,
            "wave" to spawned, "alive" to session.monsterIds.size,
            "route_checkpoint" to checkpoint, "ambushes_remaining" to session.pendingAmbushCheckpoints.size,
        )
    }

    fun updateLights(zoneId: String, session: FarmFoodDeliverySession, level: Int) {
        val defenders = players(session).filter { it.isOnline }
        session.monsterIds.forEach { id ->
            val monster = Bukkit.getEntity(id) as? Mob
            if (monster == null || !monster.isValid || monster.isDead) releaseLight(zoneId, id)
            else {
                monster.isGlowing = true
                defenders.asSequence()
                    .filter { it.world === monster.world && !it.isDead }
                    .minByOrNull { it.location.distanceSquared(monster.location) }
                    ?.let { defender -> monster.target = defender }
                night.updateExternalLight(lightOwner(zoneId, id), monster, level)
            }
        }
    }

    fun releaseLight(zoneId: String, entityId: UUID) = night.releaseExternalLight(lightOwner(zoneId, entityId))

    fun clear(zoneId: String) = night.releaseExternalLights("food:$zoneId:")

    private fun players(session: FarmFoodDeliverySession): List<Player> = listOfNotNull(
        session.riderId?.let(Bukkit::getPlayer),
        session.gunnerId?.let(Bukkit::getPlayer),
    ).distinctBy(Player::getUniqueId)

    private fun lightOwner(zoneId: String, id: UUID): String = "food:$zoneId:$id"

    private companion object {
        const val PHANTOM_SPAWN_HEIGHT = 7.0
    }
}
