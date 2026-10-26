package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.lumber.*
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.lumber.scene.LumberPresentation
import ru.ruscrafting.farms.paper.lumber.scene.LumberSceneGeometry
import ru.ruscrafting.farms.paper.worksite.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class LumberIncidentControllerTest : FunSpec({
    test("pass completion and its natural repair commit together and guidance follows ready held lap and resumed states") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = IncidentFixture(paper, lumberIncidentBoundary(passes = 0))
            f.click(LumberAction.SAW_LEVER)
            f.now += 12_000L
            f.controller.updateVisuals()
            f.controller.state.sawPasses shouldBe 1
            f.controller.state.incident?.type shouldBe LumberIncidentType.FEED_JAM
            f.click(LumberAction.SAW_LEVER)
            f.controller.state.sawRunning shouldBe false
            f.guide()
            f.controller.view(f.player.uniqueId)?.subtitle shouldBe Component.text("lumber.incidents.feed-jam.hint")
            f.click(LumberAction.START_CRANK)
            f.guide()
            f.controller.view(f.player.uniqueId)?.subtitle shouldBe Component.text("lumber.incidents.feed-jam.winding-hint")
            f.walkLap()
            f.controller.state.incident?.crankTurns shouldBe 1
            f.walkLap()
            f.controller.state.incident shouldBe null
            f.controller.state.sawPasses shouldBe 1
            f.controller.state.completedRounds shouldBe 1L
            f.controller.view(f.player.uniqueId)?.subtitle shouldBe Component.text("lumber.hints.sawing")
            f.titles shouldBe listOf(Component.text("lumber.incidents.feed-jam.hint"),
                Component.text("lumber.incidents.feed-jam.winding-hint"), Component.text("lumber.hints.sawing"))
            f.click(LumberAction.SAW_LEVER)
            f.controller.state.sawRunning shouldBe true
        }
    }

    test("grabbing outside the ring is forgiving and back-and-forth arcs cannot manufacture a full turn") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = IncidentFixture(paper)
            f.force(LumberIncidentType.FEED_JAM)
            f.move(0.0, 2.4)
            f.click(LumberAction.START_CRANK)
            f.controller.state.incident?.crankOperator shouldBe f.player.uniqueId
            f.move(0.0)
            f.walkLap()
            f.controller.state.incident?.crankTurns shouldBe 1
            repeat(20) { listOf(0.0, .35, .7, .35, 0.0).forEach { f.move(it) } }
            f.controller.state.incident?.crankTurns shouldBe 1
            f.move(0.0, 2.4)
            f.controller.state.incident?.crankOperator shouldBe f.player.uniqueId
            f.controller.state.incident?.crankTurns shouldBe 1
            f.move(0.0)
            f.walkLap()
            f.controller.state.incident shouldBe null
        }
    }

    test("admin acceptance waits for durable commit and replacement quit and zero participants preserve completed progress") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = IncidentFixture(paper)
            f.delayWrites = true
            val requested = f.controller.forceIncidentAsync(LumberIncidentType.FEED_JAM)
            requested.isDone shouldBe false
            f.controller.forceIncidentAsync(LumberIncidentType.SAWDUST_SMOLDER).getNow(true) shouldBe false
            f.controller.updateVisuals()
            f.frames.last().state.incident shouldBe null
            f.writes.removeFirst().completeExceptionally(IOException("simulated persistence outage"))
            requested.isDone shouldBe false
            f.retries.removeFirst().invoke()
            f.delayWrites = false
            f.writes.removeFirst().complete(Unit)
            requested.getNow(false) shouldBe true
            f.click(LumberAction.START_CRANK)
            f.walkLap()
            val generation = f.controller.state.incidentGeneration
            val outside = paper.server.addSimpleWorld("outside_lumber")
            f.player.teleport(Location(outside, 0.0, 73.0, 0.0))
            f.controller.onMove(f.player) shouldBe false
            val paused = f.controller.state
            paused.incident?.crankOperator shouldBe null
            paused.incident?.crankTurns shouldBe 1
            f.now += 60_000L
            repeat(3) { f.controller.updateVisuals() }
            f.controller.state shouldBe paused
            f.move(0.0)
            f.controller.onMove(f.player)
            f.force(LumberIncidentType.FEED_JAM)
            f.controller.state.incidentGeneration shouldBe generation + 1
            f.controller.state.incident?.crankTurns shouldBe 0
            verify { f.tethers.clear("lumber-incident:${paused.roundId}:$generation") }
            f.delayWrites = true
            val abandoned = f.controller.forceIncidentAsync(LumberIncidentType.SAWDUST_SMOLDER)
            f.controller.cleanup("test_shutdown")
            abandoned.getNow(true) shouldBe false
        }
    }

    test("owned hose misses consume vanilla use and each visible pile counts once with beta offhand and cleanup guards") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = IncidentFixture(paper, lumberIncidentBoundary(roundId = 1, completedRounds = 0, passes = 2))
            f.force(LumberIncidentType.SAWDUST_SMOLDER)
            f.player.inventory.itemInMainHand.type shouldBe Material.BLAZE_ROD
            val miss = f.rightClick()
            verify { miss.isCancelled = true; miss.setUseInteractedBlock(Event.Result.DENY); miss.setUseItemInHand(Event.Result.DENY) }
            f.controller.state.incident?.hotspots?.completed shouldBe 0
            verify { f.scene.spray(f.player) }
            val piles = LumberSceneGeometry.smolderTargets()
            val originalFloor = f.world.getBlockAt(piles[0].x.toInt(), 72, piles[0].z.toInt()).apply { type = Material.STONE }
            f.aim(piles[0])
            f.rightClick()
            f.controller.state.incident?.hotspots?.completed shouldBe 1
            f.rightClick(advance = 0L)
            f.rightClick()
            f.controller.state.incident?.hotspots?.completed shouldBe 1
            f.rightClick(hand = EquipmentSlot.OFF_HAND, expected = false)
            val denied = f.player.addAttachment(f.plugin, LumbermillSettings.BETA_PERMISSION, false)
            f.rightClick(expected = false)
            f.controller.onMove(f.player)
            f.items.identities(f.player).any { it.activity == ActivityKind.LUMBER } shouldBe false
            f.controller.state.incident?.hotspots?.completed shouldBe 1
            f.player.removeAttachment(denied)
            f.controller.onMove(f.player)
            for (pile in piles.drop(1)) { f.aim(pile); f.rightClick() }
            f.controller.state.incident shouldBe null
            f.controller.state.phase shouldBe LumberPhase.COLLECTING_BOARDS
            f.controller.state.completedRounds shouldBe 0L
            f.items.identities(f.player).any { it.itemId == "lumber_hose" } shouldBe false
            originalFloor.type shouldBe Material.STONE
            f.world.getBlockAt(piles[0].x.toInt(), 73, piles[0].z.toInt()).type shouldBe Material.AIR
        }
    }

    test("a failed hose issue retries through the shared selected-slot delivery after space is freed") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = IncidentFixture(paper, lumberIncidentBoundary(roundId = 1, completedRounds = 0, passes = 2))
            repeat(36) { f.player.inventory.setItem(it, ItemStack(Material.STONE, 64)) }
            f.force(LumberIncidentType.SAWDUST_SMOLDER)
            f.items.identities(f.player).any { it.itemId == "lumber_hose" } shouldBe false
            f.player.inventory.setItem(f.player.inventory.heldItemSlot, null)
            f.input = LumberInput(LumberAction.EXTINGUISH_SAWDUST)
            f.rightClick()
            f.input = null
            f.player.inventory.itemInMainHand.type shouldBe Material.BLAZE_ROD
            f.items.identity(f.player.inventory.itemInMainHand)?.objectiveNonce shouldBe f.controller.state.incidentGeneration
            f.controller.state.incident?.hotspots?.completed shouldBe 0
        }
    }
})

private class IncidentFixture(paper: MockBukkitTestRuntime,
    initial: LumberState = lumberIncidentBoundary(roundId = 1, completedRounds = 0),
) {
    private val rules = LumbermillSettings()
    val world = paper.server.addSimpleWorld(rules.world)
    val plugin = paper.createSimplePlugin("LumberIncidentTest")
    val player = paper.server.addPlayer().apply {
        val center = LumberSceneGeometry.incidentCrank()
        teleport(Location(world, center.x + 1.35, center.y, center.z))
        addAttachment(plugin, LumbermillSettings.BETA_PERMISSION, true)
    }
    var now = 1_000L
    var delayWrites = false
    var input: LumberInput? = null
    val writes = ArrayDeque<CompletableFuture<Unit>>()
    val retries = ArrayDeque<() -> Unit>()
    val frames = mutableListOf<LumberVisualView>()
    val titles = mutableListOf<Component>()
    val tethers = mockk<WorksiteCrankTethers>(relaxed = true) {
        every { attach(any(), any(), any(), any()) } returns Result.success(false)
    }
    private val cooldowns = mutableMapOf<String, Long>()
    private val access = mockk<WorksiteAccessPort>(relaxed = true) {
        every { allowInteraction(any(), any()) } answers {
            val key = firstArg<String>(); val delay = secondArg<Long>(); val previous = cooldowns[key]
            if (previous != null && now - previous < delay) false else { cooldowns[key] = now; true }
        }
    }
    private val locale = mockk<ArcFarmsLocale>(relaxed = true) {
        every { render(any<MessageKey>(), any(), any()) } answers { Component.text(firstArg<MessageKey>().path) }
        every { renderPath(any(), any(), any()) } answers { Component.text(firstArg<String>()) }
        every { text(any()) } answers { Component.text(firstArg<Any?>().toString()) }
    }
    private val audience = mockk<WorksiteAudiencePort>(relaxed = true) {
        every { showScreenTitle(player, any<Component>(), any<Component>()) } answers { titles += thirdArg<Component>() }
    }
    private val token = mockk<RuntimeTaskSupervisor.Token>()
    private val tasks = mockk<WorksiteTaskPort>(relaxed = true) {
        every { lifecycleToken() } returns token
        every { runSync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
        every { runLater(token, any(), any()) } answers { retries += thirdArg<() -> Unit>(); true }
    }
    private val storage = mockk<WorksiteStatePort>(relaxed = true) {
        every { persistAsync() } answers {
            if (delayWrites) CompletableFuture<Unit>().also(writes::addLast) else CompletableFuture.completedFuture(Unit)
        }
    }
    val scene = mockk<LumberPresentation>(relaxed = true) {
        every { target(any()) } answers { input }
        every { render(any()) } answers { frames += firstArg<LumberVisualView>() }
    }
    lateinit var controller: LumberController
        private set
    val items = WorksiteServiceItemController(plugin, object : WorksiteServiceItemOwner {
        override fun isActive(identity: ServiceItemIdentity) = controller.isActive(identity)
        override fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) = controller.releaseIdentity(playerId)
    })

    init {
        controller = LumberController(rules, "spawn", locale, access, audience, storage, tasks, items, scene, initial,
            { now }, mockk(relaxed = true), mockk(relaxed = true), incidentTethers = tethers)
        controller.activate()
        controller.onMove(player)
    }

    fun force(type: LumberIncidentType) { controller.forceIncidentAsync(type).getNow(false) shouldBe true }
    fun guide() { controller.updateGuidance(mutableSetOf()) }
    fun click(action: LumberAction) {
        now += 400L; input = LumberInput(action)
        controller.onDisplayInteract(player) shouldBe true
        input = null
    }
    fun move(angle: Double, radius: Double = 1.35) {
        val at = LumberSceneGeometry.incidentCrank()
        player.teleport(Location(world, at.x + radius * cos(angle), at.y, at.z + radius * sin(angle)))
        now += 50L
        controller.updateVisuals()
        guide()
    }
    fun walkLap() { repeat(49) { move(2.0 * PI * it / 48.0) } }
    fun aim(target: LumberPose) {
        player.teleport(Location(world, target.x + 2.5, 73.0, target.z))
        val feet = player.location
        feet.direction = Location(world, target.x, target.y, target.z).toVector().subtract(player.eyeLocation.toVector())
        player.teleport(feet)
    }
    fun rightClick(advance: Long = 400L, hand: EquipmentSlot = EquipmentSlot.HAND, expected: Boolean = true): PlayerInteractEvent {
        now += advance
        val usedHand = hand
        val event = mockk<PlayerInteractEvent>(relaxed = true) {
            every { this@mockk.player } returns this@IncidentFixture.player
            every { this@mockk.hand } returns usedHand
            every { action } returns Action.RIGHT_CLICK_AIR
        }
        controller.onInteract(event) shouldBe expected
        return event
    }
}
