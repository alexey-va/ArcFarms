package ru.ruscrafting.farms.paper.farm.care

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.block.data.Ageable
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerTeleportEvent
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
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmBlockPassability
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.shift.FarmShiftCoordinator
import ru.ruscrafting.farms.paper.worksite.WorksiteStatsPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort

class FarmDitchRescueWorldMockBukkitTest : FunSpec({
    test("restoration lifts a visitor out before filling the pit and leaves surface bystanders in place") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val (runtime, ditch, ledger) = safetyFixture(paper)
            val world = runtime.region.world
            ditch.ensure(runtime)
            val visitor = paper.server.addPlayer()
            visitor.teleport(requireNotNull(ditch.spawnLocation(runtime, runtime.state.careTargets.single())))
            visitor.fallDistance = 12f
            val bystander = paper.server.addPlayer()
            bystander.teleport(Location(world, 1.5, 65.0, 1.5))
            val bystanderPosition = bystander.location.clone()
            val footprint = FarmDitchLayout.cells(runtime.state.placementSequence).mapTo(hashSetOf()) { 8 + it.x to 8 + it.z }
            val rim = footprint.flatMap { (x, z) -> listOf(x - 1 to z, x + 1 to z, x to z - 1, x to z + 1) }
                .distinct().filterNot { it in footprint }
            val exit = rim[1]
            world.getBlockAt(rim.first().first, 64, rim.first().second).type = Material.MAGMA_BLOCK
            rim.drop(2).forEach { (x, z) -> (65..68).forEach { y -> world.getBlockAt(x, y, z).type = Material.STONE } }
            var sawTeleportBeforeRestoration = false
            paper.server.pluginManager.registerEvents(object : Listener {
                @EventHandler
                fun onTeleport(event: PlayerTeleportEvent) {
                    if (event.player != visitor) return
                    event.cause shouldBe PlayerTeleportEvent.TeleportCause.PLUGIN
                    ditch.isAuthorizedTeleport(visitor, event.to) shouldBe true
                    world.getBlockAt(8, 64, 8).type shouldBe Material.AIR
                    sawTeleportBeforeRestoration = true
                }
            }, paper.createSimplePlugin("DitchSurfaceObserver"))

            ditch.restore(runtime)

            sawTeleportBeforeRestoration shouldBe true
            (visitor.location.y >= 65.0) shouldBe true
            visitor.location.block.type.isSolid shouldBe false
            visitor.location.block.getRelative(org.bukkit.block.BlockFace.UP).type.isSolid shouldBe false
            visitor.location.block.getRelative(org.bukkit.block.BlockFace.DOWN).type.isSolid shouldBe true
            visitor.fallDistance shouldBe 0f
            (visitor.location.blockX to visitor.location.blockZ) shouldBe exit
            ditch.isAuthorizedTeleport(visitor, visitor.location) shouldBe false
            bystander.location shouldBe bystanderPosition
            world.getBlockAt(8, 64, 8).type shouldBe Material.FARMLAND
            ledger.record(world.getBlockAt(8, 63, 8)) shouldBe null
        } finally { paper.close() }
    }

    test("a completed rescue retains ownership while blocked and automatic retry never credits the catch twice") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val (runtime, _, ledger) = safetyFixture(paper)
            val player = paper.server.addPlayer()
            val care = mockk<FarmCareController>(relaxed = true) {
                every { restoreDitch(runtime) } returnsMany listOf(false, true)
                every { clear(runtime, "care_resolved") } returns true
            }
            val stats = mockk<WorksiteStatsPort>(relaxed = true)
            val coordinator = FarmShiftCoordinator(
                settings = { mockk(relaxed = true) }, locale = mockk(relaxed = true), debug = ArcFarmsDebug({ false }) {},
                port = mockk(relaxed = true), state = mockk(relaxed = true), tasks = mockk(relaxed = true),
                stats = stats, network = mockk(relaxed = true), carePlans = mockk(relaxed = true), care = care,
                field = mockk(relaxed = true),
                drought = mockk(relaxed = true), pests = mockk(relaxed = true), birds = mockk(relaxed = true),
                foodDelivery = mockk(relaxed = true), actionIncidents = mockk(relaxed = true), special = mockk(relaxed = true),
                processing = mockk(relaxed = true), barnFire = mockk(relaxed = true), frost = mockk(relaxed = true),
                tornado = mockk(relaxed = true), greenhouse = mockk(relaxed = true), delivery = mockk(relaxed = true),
                scene = mockk(relaxed = true), supplies = mockk(relaxed = true), rewards = mockk(relaxed = true),
                enterprise = mockk(relaxed = true), hud = mockk(relaxed = true), points = mockk(relaxed = true),
            )

            coordinator.apply(runtime, FarmShiftEngine.advanceCare(runtime.state, 0, player.uniqueId), player)

            runtime.state.phase shouldBe FarmPhase.CARE
            runtime.state.careType shouldBe FarmCareType.DITCH_RESCUE
            runtime.state.careTargets.single().complete shouldBe true
            verify(exactly = 0) { care.clear(runtime, any()) }
            val retryOwner = FarmCareController(
                plugin = paper.createSimplePlugin("DitchRetry"), settings = { mockk(relaxed = true) },
                locale = mockk(relaxed = true), debug = ArcFarmsDebug({ false }) {},
                access = mockk(relaxed = true), audience = mockk(relaxed = true), state = mockk(relaxed = true),
                ledger = ledger, serviceItems = mockk(relaxed = true), registry = mockk(relaxed = true),
                plans = mockk(relaxed = true), points = mockk(relaxed = true), moles = mockk(relaxed = true),
                transitions = FarmTransitionSink(coordinator::apply), runtimes = { listOf(runtime) }, clock = { 0L },
            )

            retryOwner.ensure(runtime)

            runtime.state.phase shouldBe FarmPhase.HARVESTING
            runtime.state.careType shouldBe null
            runtime.state.careTargets shouldBe emptyList()
            runtime.state.contributors[player.uniqueId] shouldBe 1
            verify(exactly = 1) { stats.recordContribution(player.uniqueId, ru.ruscrafting.farms.domain.ActivityKind.FARM, 1) }
            verify(exactly = 1) { care.clear(runtime, "care_resolved") }
        } finally { paper.close() }
    }

    test("rejected evacuation preserves the complete pit and journals until a later restore succeeds") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val (runtime, ditch, ledger) = safetyFixture(paper)
            val world = runtime.region.world
            ditch.ensure(runtime)
            val visitor = paper.server.addPlayer()
            visitor.teleport(requireNotNull(ditch.spawnLocation(runtime, runtime.state.careTargets.single())))
            var reject = true
            paper.server.pluginManager.registerEvents(object : Listener {
                @EventHandler
                fun onTeleport(event: PlayerTeleportEvent) {
                    if (event.player == visitor && reject) event.isCancelled = true
                }
            }, paper.createSimplePlugin("DitchTeleportGate"))

            ditch.restore(runtime)

            FarmDitchLayout.cells(runtime.state.placementSequence).forEach { cell ->
                repeat(cell.depth) { depth ->
                    val block = world.getBlockAt(8 + cell.x, 64 - depth, 8 + cell.z)
                    block.type shouldBe Material.AIR
                    (ledger.record(block) != null) shouldBe true
                }
            }
            reject = false
            ditch.restore(runtime)
            world.getBlockAt(8, 64, 8).type shouldBe Material.FARMLAND
            (visitor.location.y >= 65.0) shouldBe true
        } finally { paper.close() }
    }

    test("procedural ditch removes an uneven crop patch and restores exact active crops") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("sp11")
            world.getChunkAt(0, 0).load()
            val placementSequence = 12L
            val centerX = 8
            val centerZ = 8
            val layout = FarmDitchLayout.cells(placementSequence)
            val ditchCells = layout.map { centerX + it.x to centerZ + it.z }.toSet()
            fun surfaceY(x: Int, z: Int): Int = 64 + ((x + z) and 1)
            val expectedCrops = mutableMapOf<Pair<Int, Int>, String>()
            val expectedTerrain = mutableMapOf<Triple<Int, Int, Int>, String>()
            for (x in 2..14) for (z in 2..14) {
                val surfaceY = surfaceY(x, z)
                world.getBlockAt(x, surfaceY, z).type = Material.FARMLAND
                world.getBlockAt(x, surfaceY - 1, z).type = Material.DIRT
                world.getBlockAt(x, surfaceY - 2, z).type = Material.STONE
                world.getBlockAt(x, surfaceY - 3, z).type = Material.DEEPSLATE
                world.getBlockAt(x, surfaceY - 4, z).type = Material.STONE
                world.getBlockAt(x, surfaceY - 5, z).type = Material.DEEPSLATE
                world.getBlockAt(x, surfaceY - 6, z).type = Material.STONE
                val crop = world.getBlockAt(x, surfaceY + 1, z)
                crop.type = Material.WHEAT
                val age = crop.blockData as Ageable
                age.age = (x + z) % (age.maximumAge + 1)
                crop.blockData = age
                if (x to z in ditchCells) expectedCrops[x to z] = crop.blockData.asString
            }
            layout.forEach { cell ->
                val surfaceY = surfaceY(centerX + cell.x, centerZ + cell.z)
                repeat(cell.depth) { depth ->
                    val block = world.getBlockAt(centerX + cell.x, surfaceY - depth, centerZ + cell.z)
                    expectedTerrain[Triple(block.x, block.y, block.z)] = block.blockData.asString
                }
            }
            val settings = mockk<FarmZoneSettings> {
                io.mockk.every { id } returns "communal_farm"
            }
            val runtime = FarmRuntime(
                settings = settings,
                region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 56, 0, 16, 72, 16)),
                orders = emptyMap(),
                orderList = emptyList(),
                rules = FarmRules(listOf(50), 1, 1_000),
                state = FarmShiftState(
                    phase = FarmPhase.CARE,
                    careType = FarmCareType.DITCH_RESCUE,
                    sequence = 12,
                    placementSequence = placementSequence,
                    careTargets = listOf(
                        FarmCareTarget(
                            0,
                            FarmCareRole.ANIMAL,
                            FarmPointPosition(world.name, centerX + 0.5, 64.0, centerZ + 0.5),
                        ),
                    ),
                    careGoal = 1,
                ),
            )
            val ledger = FarmBlockLedger(paper.createSimplePlugin("FarmDitchRescueWorldTest"))
            val ditch = FarmDitchRescueWorld(
                ledger,
                mockk<WorksiteStatePort>(relaxed = true),
                ArcFarmsDebug({ false }) {},
            )

            ditch.ensure(runtime)
            ditch.ensure(runtime)

            val floatingCrop = layout.first().let { cell ->
                world.getBlockAt(centerX + cell.x, surfaceY(centerX + cell.x, centerZ + cell.z) + 1, centerZ + cell.z)
            }
            floatingCrop.type = Material.WHEAT
            ditch.ensure(runtime)
            floatingCrop.type shouldBe Material.AIR

            (ditchCells.size in 68..78) shouldBe true
            val boundingArea =
                (ditchCells.maxOf { it.first } - ditchCells.minOf { it.first } + 1) *
                    (ditchCells.maxOf { it.second } - ditchCells.minOf { it.second } + 1)
            (boundingArea > ditchCells.size) shouldBe true
            layout.forEach { cell ->
                val surfaceY = surfaceY(centerX + cell.x, centerZ + cell.z)
                repeat(cell.depth) { depth ->
                    world.getBlockAt(centerX + cell.x, surfaceY - depth, centerZ + cell.z).type shouldBe Material.AIR
                }
                val x = centerX + cell.x
                val z = centerZ + cell.z
                world.getBlockAt(x, surfaceY + 1, z).type shouldBe Material.AIR
            }
            world.getBlockAt(2, 64, 2).type shouldBe Material.FARMLAND
            val spawn = requireNotNull(ditch.spawnLocation(runtime, runtime.state.careTargets.single()))
            spawn.block.type shouldBe Material.AIR
            spawn.block.getRelative(org.bukkit.block.BlockFace.DOWN).type.isSolid shouldBe true

            ditch.restore(runtime)

            expectedTerrain.forEach { (position, data) ->
                world.getBlockAt(position.first, position.second, position.third).blockData.asString shouldBe data
            }
            ditchCells.forEach { (x, z) ->
                world.getBlockAt(x, surfaceY(x, z) + 1, z).blockData.asString shouldBe expectedCrops.getValue(x to z)
            }
        } finally {
            paper.close()
        }
    }

    test("every ditch template is connected and leaves a missing cell in its bounding box") {
        (0L..3L).forEach { selection ->
            val cells = FarmDitchLayout.cellsForSelection(selection)
            cells.first().x shouldBe 0
            cells.first().z shouldBe 0
            val offsets = cells.map { it.x to it.z }.toSet()
            val visited = mutableSetOf(0 to 0)
            val queue = ArrayDeque(visited)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                listOf(
                    current.first - 1 to current.second,
                    current.first + 1 to current.second,
                    current.first to current.second - 1,
                    current.first to current.second + 1,
                ).filter { it in offsets && visited.add(it) }.forEach(queue::addLast)
            }
            visited.shouldContainExactlyInAnyOrder(offsets)
            val boundingArea =
                (offsets.maxOf { it.first } - offsets.minOf { it.first } + 1) *
                    (offsets.maxOf { it.second } - offsets.minOf { it.second } + 1)
            (boundingArea > offsets.size) shouldBe true
            cells.minOf { it.depth } shouldBe 3
            cells.maxOf { it.depth } shouldBe 6
            (cells.sumOf { it.depth } >= 280) shouldBe true
        }
    }
})

private fun safetyFixture(paper: MockBukkitTestRuntime): Triple<FarmRuntime, FarmDitchRescueWorld, FarmBlockLedger> {
    val world = paper.server.addSimpleWorld("ditch_safety")
    world.getChunkAt(0, 0).load()
    for (x in 0..15) for (z in 0..15) {
        world.getBlockAt(x, 64, z).type = Material.FARMLAND
        world.getBlockAt(x, 65, z).type = Material.WHEAT
        for (y in 57..63) world.getBlockAt(x, y, z).type = Material.STONE
    }
    val settings = mockk<FarmZoneSettings>(relaxed = true) { io.mockk.every { id } returns "farm" }
    val runtime = FarmRuntime(settings, CuboidActivityRegion(world, "farm", CuboidBounds(0, 56, 0, 15, 72, 15)),
        emptyMap(), emptyList(), mockk(relaxed = true), FarmShiftState(
            phase = FarmPhase.CARE, careType = FarmCareType.DITCH_RESCUE, sequence = 12, placementSequence = 12,
            careTargets = listOf(FarmCareTarget(0, FarmCareRole.ANIMAL, FarmPointPosition(world.name, 8.5, 64.0, 8.5))),
            careGoal = 1,
        ))
    val ledger = FarmBlockLedger(paper.createSimplePlugin("DitchSafety"))
    return Triple(runtime, FarmDitchRescueWorld(ledger, mockk(relaxed = true), ArcFarmsDebug({ false }) {},
        MockBukkitFarmBlockPassability), ledger)
}
