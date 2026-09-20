package ru.ruscrafting.farms.paper.mine.incident.creature

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Mob
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.mine.*
import ru.ruscrafting.farms.paper.mine.recovery.*

class MineCreaturePestsMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("creatures chase creative players only on their floor and journal a bounded amount of damage") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        val runtime = MineRuntimeFactory.build(listOf(mineV2Settings()), emptyMap(), 5_000L, CuboidRegionGateway()).single()
        runtime.state = MineShiftState(engineVersion = 2, sequence = 1, phase = MinePhase.INCIDENT, orderId = "ore_run",
            resumePhase = MinePhase.MINING, incident = MineIncidentState(MineIncidentType.CREATURE_NEST, required = 2, objectiveNonce = 7))
        val anchor = WorksitePosition("world", 5, 64, 5)
        for (x in 3..8) for (z in 3..8) world.getBlockAt(x, 63, z).type = Material.STONE
        for (x in 4..6) for (z in 4..6) if (x != 5 || z != 5) for (y in 64..65) world.getBlockAt(x, y, z).type = Material.IRON_ORE
        val decor = world.getBlockAt(5, 65, 5).also { it.type = Material.OAK_PLANKS }
        val mob = world.spawnEntity(Location(world, 5.5, 64.0, 5.5), EntityType.HUSK) as Mob
        val player = paper.server.addPlayer("CreativeMiner")
        player.gameMode = GameMode.CREATIVE
        player.teleport(Location(world, 8.5, 64.0, 5.5))
        val navigation = RecordingMineNavigation()
        val stored = ImmediateMineJournal()
        val port = immediateMinePort()
        val recovery = MineBlockRecoveryController(stored, port, port, port) { 1_000L }
        val journal = MineIncidentBlockJournal(recovery)
        val pests = MineCreaturePests(journal, navigation)
        repeat(22) { pests.tick(runtime, mapOf(mob to anchor), listOf(player), 1_000L + it * 2_500L) }
        navigation.chases shouldBe 22
        stored.records().size shouldBe MineCreaturePests.MAX_DAMAGE
        world.getBlockAt(5, 63, 5).type shouldBe Material.STONE
        decor.type shouldBe Material.OAK_PLANKS
        player.health shouldBe 20.0
        val before = navigation.chases
        player.teleport(Location(world, 8.5, 78.0, 5.5))
        pests.tick(runtime, mapOf(mob to anchor), listOf(player), 70_000L)
        navigation.chases shouldBe before
        navigation.stops shouldBe 1
        val transitions = MineTransitionCoordinator(port, port, port, null, journal)
        transitions.apply(runtime, EngineResult(runtime.state.copy(phase = MinePhase.MINING, incident = null), true), null)
        player.teleport(Location(world, 50.0, 64.0, 50.0))
        recovery.processDue(70_000L)
        stored.records() shouldBe emptyList()
        world.getBlockAt(6, 64, 5).type shouldBe Material.IRON_ORE
    }

    test("path validation rejects a route across the shaft even when its destination is on the same floor") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        val runtime = MineRuntimeFactory.build(listOf(mineV2Settings()), emptyMap(), 5_000L, CuboidRegionGateway()).single()
        world.getBlockAt(5, 63, 5).type = Material.STONE
        PaperMineCreatureNavigation.safeStep(runtime, Location(world, 5.5, 64.0, 5.5), 64) shouldBe true
        PaperMineCreatureNavigation.safeStep(runtime, Location(world, 6.5, 64.0, 5.5), 64) shouldBe false
        PaperMineCreatureNavigation.safeStep(runtime, Location(world, 5.5, 78.0, 5.5), 64) shouldBe false
        world.getBlockAt(6, 65, 5).type = Material.STONE
        PaperMineCreatureNavigation.safeStep(runtime, Location(world, 6.5, 66.0, 5.5), 64) shouldBe true
        world.getBlockAt(7, 61, 5).type = Material.STONE
        PaperMineCreatureNavigation.safeStep(runtime, Location(world, 7.5, 62.0, 5.5), 64) shouldBe false
    }
})

private class RecordingMineNavigation : MineCreatureNavigation {
    var chases = 0
    var stops = 0
    override fun initialize(mob: Mob) = Unit
    override fun stop(mob: Mob) { stops++ }
    override fun chase(mob: Mob, destination: Location, runtime: MineRuntime, floorY: Int) { chases++ }
}
