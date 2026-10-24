package ru.ruscrafting.farms.paper.farm.care

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.EntityType
import org.bukkit.entity.FishHook
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.ArcFarmsListener
import ru.ruscrafting.farms.paper.ArcFarmsService
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItemController
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItemOwner

class FarmDitchRescueHookMockBukkitTest : FunSpec({
    test("service hooks attach and reel once before WorldGuard-shaped protection in either hand") {
        for (hand in listOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
            RescueHookFixture().use { f ->
                if (hand == EquipmentSlot.OFF_HAND) {
                    f.player.inventory.setItemInOffHand(f.player.inventory.itemInMainHand)
                    f.player.inventory.setItemInMainHand(ItemStack(Material.AIR))
                }
                val hook = f.cast(hand)
                val hit = ProjectileHitEvent(hook, f.animal)
                f.paper.server.pluginManager.callEvent(hit)

                hit.isCancelled shouldBe true
                verify(exactly = 1) { hook.hookedEntity = f.animal }
                f.protection.denials shouldBe 0
                f.credits shouldBe 0

                val catch = PlayerFishEvent(f.player, f.animal, hook, hand, PlayerFishEvent.State.CAUGHT_ENTITY)
                f.paper.server.pluginManager.callEvent(catch)
                catch.isCancelled shouldBe true
                f.protection.denials shouldBe 0
                f.credits shouldBe 1
                f.runtime.state.careTargets.first().complete shouldBe true
                f.runtime.state.contributors[f.player.uniqueId] shouldBe 1
                f.animal.isValid shouldBe false
                verify(exactly = 1) { hook.remove() }

                f.paper.server.pluginManager.callEvent(
                    PlayerFishEvent(f.player, f.animal, hook, hand, PlayerFishEvent.State.CAUGHT_ENTITY),
                )
                f.credits shouldBe 1
                f.runtime.state.contributors[f.player.uniqueId] shouldBe 1

                val ordinary = f.world.spawnEntity(Location(f.world, 10.5, 65.0, 10.5), EntityType.SHEEP)
                f.paper.server.pluginManager.callEvent(ProjectileHitEvent(f.hook(), ordinary))
                f.protection.denials shouldBe 1
                f.paper.server.pluginManager.callEvent(
                    PlayerFishEvent(f.player, ordinary, f.hook(), hand, PlayerFishEvent.State.CAUGHT_ENTITY),
                )
                f.protection.denials shouldBe 2
                ordinary.isValid shouldBe true
                f.credits shouldBe 1
            }
        }
    }

    test("rescue authorization rejects inactive stale foreign and out-of-zone targets without credit") {
        RescueHookFixture().use { f ->
            val invalid: List<Pair<String, RescueHookFixture.() -> Unit>> = listOf(
                "phase" to { runtime.state = active.copy(phase = FarmPhase.HARVESTING) },
                "care type" to { runtime.state = active.copy(careType = FarmCareType.ANIMAL_RESCUE) },
                "sequence" to { runtime.state = active.copy(sequence = active.sequence + 1) },
                "completed target" to { runtime.state = active.copy(careTargets = active.careTargets.map { it.copy(progress = 1) }) },
                "missing target" to { runtime.state = active.copy(careTargets = active.careTargets.drop(1)) },
                "wrong role" to { mark("farm_care_role", FarmCareRole.WEED_ROOT.name) },
                "foreign zone" to { mark("farm_care_zone", "other_farm") },
                "permission" to { every { access.hasAccess(player, any()) } returns false },
                "edit mode" to { every { access.isAdminEditing(player) } returns true },
                "player outside" to { player.teleport(Location(world, 40.5, 65.0, 8.5)) },
                "target outside" to { animal.teleport(Location(world, 40.5, 65.0, 8.5)) },
                "ordinary rod" to { player.inventory.setItemInMainHand(ItemStack(Material.FISHING_ROD)) },
                "stale rod" to { equipRod(rodIdentity.copy(objectiveNonce = rodIdentity.objectiveNonce + 1)) },
                "foreign rod" to { equipRod(rodIdentity.copy(zoneId = "other_farm")) },
            )
            for ((name, invalidate) in invalid) withClue(name) {
                f.reset()
                val hook = f.cast()
                f.invalidate()
                val hit = ProjectileHitEvent(hook, f.animal)
                f.care.onProjectileHit(hit) shouldBe false
                hit.isCancelled shouldBe false
                verify(exactly = 0) { hook.hookedEntity = any() }
                f.paper.server.pluginManager.callEvent(hit)
                hit.isCancelled shouldBe true
                f.protection.denials shouldBe 1

                f.care.onFish(PlayerFishEvent(f.player, f.animal, hook, EquipmentSlot.HAND, PlayerFishEvent.State.CAUGHT_ENTITY))
                f.credits shouldBe 0
                f.animal.isValid shouldBe true
            }

            f.reset()
            val damage = EntityDamageEvent(f.animal, EntityDamageEvent.DamageCause.CUSTOM, 2.0)
            f.care.onDamage(damage) shouldBe true
            damage.isCancelled shouldBe true
            f.credits shouldBe 0
        }
    }

    test("ordinary casts cannot borrow a service rod in the opposite hand") {
        RescueHookFixture().use { f ->
            for (ordinaryHand in listOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
                f.reset()
                if (ordinaryHand == EquipmentSlot.HAND) {
                    f.player.inventory.setItemInOffHand(f.player.inventory.itemInMainHand)
                    f.player.inventory.setItemInMainHand(ItemStack(Material.FISHING_ROD))
                } else f.player.inventory.setItemInOffHand(ItemStack(Material.FISHING_ROD))
                val hook = f.cast(ordinaryHand)

                f.care.onProjectileHit(ProjectileHitEvent(hook, f.animal)) shouldBe false
                verify(exactly = 0) { hook.hookedEntity = any() }
                f.care.onFish(PlayerFishEvent(f.player, f.animal, hook, ordinaryHand, PlayerFishEvent.State.CAUGHT_ENTITY))
                f.credits shouldBe 0
            }

            f.reset()
            val previousPlacementHook = f.cast()
            f.runtime.state = f.active.copy(placementSequence = f.active.placementSequence + 1)
            f.equipRod(f.rodIdentity.copy(objectiveNonce = f.runtime.state.placementSequence))
            f.care.onProjectileHit(ProjectileHitEvent(previousPlacementHook, f.animal)) shouldBe false
            f.care.onFish(PlayerFishEvent(f.player, f.animal, previousPlacementHook, EquipmentSlot.HAND, PlayerFishEvent.State.CAUGHT_ENTITY))
            f.credits shouldBe 0
            verify(exactly = 0) { previousPlacementHook.hookedEntity = any() }
        }
    }
})

private class RescueHookFixture : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    private val plugin = paper.createSimplePlugin("DitchRescueHook")
    val world = paper.server.addSimpleWorld("farm")
    val player = paper.server.addPlayer()
    val animal = world.spawnEntity(Location(world, 8.5, 62.0, 8.5), EntityType.SHEEP)
    val active = FarmShiftState(
        phase = FarmPhase.CARE, careType = FarmCareType.DITCH_RESCUE, sequence = 4, placementSequence = 8,
        careTargets = (0..1).map { FarmCareTarget(it, FarmCareRole.ANIMAL, FarmPointPosition(world.name, 8.5 + it, 62.0, 8.5)) },
    )
    val runtime = FarmRuntime(
        mockk<FarmZoneSettings> {
            every { id } returns "communal_farm"
            every { permission } returns "arcfarms.farm"
        },
        CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 31, 128, 31)),
        emptyMap(), emptyList(), FarmRules(listOf(50), 1, 1_000), active,
    )
    val access = mockk<WorksiteAccessPort>(relaxed = true)
    private val items = WorksiteServiceItemController(plugin, mockk<WorksiteServiceItemOwner> {
        every { isActive(any()) } returns true
    })
    private val debug = ArcFarmsDebug({ false }) {}
    val rodIdentity = FarmDitchRescueRod(plugin, items, access, mockk(relaxed = true), mockk(relaxed = true), debug).identity(runtime)
    var credits = 0
    val care = FarmCareController(
        plugin = plugin, settings = { mockk(relaxed = true) }, locale = mockk(relaxed = true), debug = debug,
        access = access, audience = mockk(relaxed = true), state = mockk(relaxed = true), ledger = mockk(relaxed = true),
        serviceItems = items, registry = mockk(relaxed = true), plans = mockk(relaxed = true), points = mockk(relaxed = true),
        moles = mockk(relaxed = true), transitions = FarmTransitionSink { owner, result, _ ->
            owner.state = result.state
            credits += result.contribution
        },
        runtimes = { listOf(runtime) }, clock = { 0L }, displays = mockk(relaxed = true),
    )
    val protection = RescueProtectionListener()

    init {
        reset()
        val service = mockk<ArcFarmsService>(relaxed = true)
        every { service.onProjectileHitLowest(any()) } answers { care.onProjectileHit(firstArg()); Unit }
        every { service.onFish(any()) } answers { care.onFish(firstArg()); Unit }
        paper.server.pluginManager.registerEvents(ArcFarmsListener(service, mockk(relaxed = true)), plugin)
        paper.server.pluginManager.registerEvents(protection, plugin)
    }

    fun reset() {
        runtime.state = active
        credits = 0
        protection.denials = 0
        every { access.hasAccess(player, any()) } returns true
        every { access.isAdminEditing(player) } returns false
        player.teleport(Location(world, 8.5, 65.0, 8.5))
        animal.teleport(Location(world, 8.5, 62.0, 8.5))
        mark("farm_care_zone", runtime.settings.id)
        mark("farm_care_role", FarmCareRole.ANIMAL.name)
        animal.persistentDataContainer.set(NamespacedKey(plugin, "farm_care_sequence"), PersistentDataType.LONG, active.sequence)
        animal.persistentDataContainer.set(NamespacedKey(plugin, "farm_care_target"), PersistentDataType.INTEGER, 0)
        equipRod(rodIdentity)
    }

    fun equipRod(identity: ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity) {
        player.inventory.clear()
        check(items.issueTool(player, identity, Material.FISHING_ROD, Component.text("Rescue rod")) != null)
    }

    fun mark(key: String, value: String) {
        animal.persistentDataContainer.set(NamespacedKey(plugin, key), PersistentDataType.STRING, value)
    }

    // MockBukkit has no native hook collision/reel simulation; Paper's setter and retrieve contract is source-verified.
    fun hook(): FishHook {
        val data = PersistentDataContainerMock()
        return mockk(relaxed = true) {
            every { shooter } returns player
            every { persistentDataContainer } returns data
        }
    }

    fun cast(hand: EquipmentSlot = EquipmentSlot.HAND): FishHook = hook().also { hook ->
        val event = PlayerFishEvent(player, null, hook, hand, PlayerFishEvent.State.FISHING)
        paper.server.pluginManager.callEvent(event)
        event.isCancelled shouldBe false
    }

    override fun close() = paper.close()
}

/** Mirrors WorldGuard 7.0.16's NORMAL/ignoreCancelled collision and CAUGHT_ENTITY protection entrypoints. */
private class RescueProtectionListener : Listener {
    var denials = 0

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onHit(event: ProjectileHitEvent) {
        if (event.entity is FishHook && event.hitEntity != null) {
            denials++
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state == PlayerFishEvent.State.CAUGHT_ENTITY) {
            denials++
            event.isCancelled = true
        }
    }
}
