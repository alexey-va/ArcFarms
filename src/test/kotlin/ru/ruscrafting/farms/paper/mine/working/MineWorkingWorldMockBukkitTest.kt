package ru.ruscrafting.farms.paper.mine.working

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.MineOrderSettings
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.config.ZoneReference
import ru.ruscrafting.farms.domain.MineIncidentState
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.MineWorkingStage
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorksiteSceneCodec
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmBlockDataDecoder
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneBlockDataDecoder
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneChunkRetention
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneOwner

private data class MineWorkingFixture(
    val registry: MineRuntimeRegistry,
    val runtime: MineRuntime,
    val type: MineIncidentType,
    val placement: MineWorkingPlacement,
    val plan: MineWorkingPlan,
    val nonce: Long,
)

class MineWorkingWorldMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var plugin: Plugin

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("mine_working_test")
        plugin = paper.createSimplePlugin("MineWorkingWorldTest")
        for (chunkX in -1..1) for (chunkZ in -1..2) world.getChunkAt(chunkX, chunkZ).load()
    }

    afterEach { paper.close() }

    test("tunnel projection survives restart and chunk reconciliation, then restores exact originals") {
        val fixture = fixture(world, MineIncidentType.TUNNEL_DRIVE)
        val firstOwner = owner(plugin)
        val firstWorld = MineWorkingWorld(fixture.registry, firstOwner, MockBukkitFarmBlockDataDecoder)

        populate(world, fixture.plan, fixture.placement)
        val originals = fixture.plan.blocks.keys.associateWith { position -> block(world, position).blockData.asString }
        firstWorld.prepare(fixture.runtime, fixture.type, fixture.placement, fixture.nonce) shouldBe true
        firstWorld.isReady(fixture.runtime) shouldBe false
        firstOwner.process(1) { true } shouldBeGreaterThan 0
        firstWorld.isReady(fixture.runtime) shouldBe false
        drain(firstOwner, firstWorld, fixture.runtime)
        fixture.plan.supportFrames.flatMap { it.keys }.forEach { position ->
            block(world, position).type shouldBe Material.STONE
        }
        firstWorld.isReady(fixture.runtime) shouldBe true
        val upperChamber = fixture.plan.walkable.first { it.y > fixture.placement.entrance.y + 4 }
        firstWorld.scene(fixture.runtime)!!.inside(upperChamber.location(world)) shouldBe true

        val activeIncident = requireNotNull(fixture.runtime.state.incident)
        fixture.runtime.state = fixture.runtime.state.copy(
            incident = activeIncident.copy(
                working = requireNotNull(activeIncident.working).copy(
                    stage = MineWorkingStage.SUPPORT,
                    completed = setOf(0),
                ),
            ),
        )
        firstWorld.project(fixture.runtime)
        fixture.plan.supportFrames.first().keys.forEach { position ->
            block(world, position).type shouldBe Material.SPRUCE_LOG
        }
        fixture.plan.supportFrames.drop(1).flatMap { it.keys }.forEach { position ->
            block(world, position).type shouldBe Material.STONE
        }
        val excavated = fixture.plan.excavation.first()
        block(world, excavated).blockData.asString shouldBe "minecraft:air"

        firstWorld.clearQueues()
        val restartedOwner = owner(plugin)
        val restartedWorld = MineWorkingWorld(fixture.registry, restartedOwner, MockBukkitFarmBlockDataDecoder)
        restartedWorld.reconcileLoaded()
        drain(restartedOwner, restartedWorld, fixture.runtime)
        restartedWorld.isReady(fixture.runtime) shouldBe true
        block(world, excavated).blockData.asString shouldBe "minecraft:air"
        restartedWorld.onChunkLoad(world.getChunkAt(excavated.x shr 4, excavated.z shr 4))
        drain(restartedOwner, restartedWorld, fixture.runtime)
        restartedWorld.isReady(fixture.runtime) shouldBe true
        block(world, excavated).blockData.asString shouldBe "minecraft:air"

        restartedWorld.startRestore(fixture.runtime)
        while (restartedWorld.isRestoring(fixture.runtime)) restartedWorld.process()
        originals.forEach { (position, data) -> block(world, position).blockData.asString shouldBe data }
    }

    listOf(MineIncidentType.RAIL_EXTENSION, MineIncidentType.ORE_WORKSHOP).forEach { type ->
      test("$type is not ready until the real baseline build has drained") {
        val fixture = fixture(world, type)
        val sceneOwner = owner(plugin)
        val workings = MineWorkingWorld(fixture.registry, sceneOwner, MockBukkitFarmBlockDataDecoder)
        populate(world, fixture.plan, fixture.placement)

        workings.prepare(fixture.runtime, fixture.type, fixture.placement, fixture.nonce) shouldBe true
        workings.retainedScene(fixture.runtime.settings.id, fixture.runtime.state.sequence)?.plan?.type shouldBe type
        workings.isReady(fixture.runtime) shouldBe false
        sceneOwner.process(1) { true } shouldBeGreaterThan 0
        workings.isReady(fixture.runtime) shouldBe false
        drain(sceneOwner, workings, fixture.runtime)
        workings.isReady(fixture.runtime) shouldBe true
      }
    }

    test("rail extension starts with empty rail cells and lays only completed rails") {
        val fixture = fixture(world, MineIncidentType.RAIL_EXTENSION)
        val sceneOwner = owner(plugin)
        val workings = MineWorkingWorld(fixture.registry, sceneOwner, MockBukkitFarmBlockDataDecoder)
        populate(world, fixture.plan, fixture.placement)

        workings.prepare(fixture.runtime, fixture.type, fixture.placement, fixture.nonce) shouldBe true
        drain(sceneOwner, workings, fixture.runtime)
        fixture.plan.rails.forEach { position ->
            block(world, position).type shouldBe if (position in fixture.plan.rubble) Material.COBBLESTONE else Material.AIR
        }
        workings.isReady(fixture.runtime) shouldBe true

        fixture.runtime.state = fixture.runtime.state.copy(
            incident = requireNotNull(fixture.runtime.state.incident).copy(
                working = requireNotNull(fixture.runtime.state.incident!!.working).copy(
                    stage = MineWorkingStage.LAY_TRACK,
                    completed = setOf(0),
                ),
            ),
        )
        workings.project(fixture.runtime)
        fixture.plan.rubble.forEach { position -> block(world, position).type shouldBe Material.AIR }
        block(world, fixture.plan.rails.first()).type shouldBe Material.RAIL
        fixture.plan.rails.drop(1).forEach { position -> block(world, position).type shouldBe Material.AIR }
    }

    test("track damage preserves intact baseline rails and repairs only gaps") {
        val fixture = fixture(world, MineIncidentType.TRACK_DAMAGE)
        val sceneOwner = owner(plugin)
        val workings = MineWorkingWorld(fixture.registry, sceneOwner, MockBukkitFarmBlockDataDecoder)
        populate(world, fixture.plan, fixture.placement)

        workings.prepare(fixture.runtime, fixture.type, fixture.placement, fixture.nonce) shouldBe true
        drain(sceneOwner, workings, fixture.runtime)
        fixture.plan.cartRoute.forEach { position ->
            block(world, position).type shouldBe if (position in fixture.plan.rubble) Material.COBBLESTONE else Material.RAIL
        }
        workings.isReady(fixture.runtime) shouldBe true

        fixture.runtime.state = fixture.runtime.state.copy(
            incident = requireNotNull(fixture.runtime.state.incident).copy(
                working = requireNotNull(fixture.runtime.state.incident!!.working).copy(stage = MineWorkingStage.LAY_TRACK),
            ),
        )
        workings.project(fixture.runtime)
        fixture.plan.rubble.forEach { position -> block(world, position).type shouldBe Material.AIR }
        block(world, fixture.plan.rails.first()).type shouldBe Material.AIR

        fixture.runtime.state = fixture.runtime.state.copy(
            incident = requireNotNull(fixture.runtime.state.incident).copy(
                working = requireNotNull(fixture.runtime.state.incident!!.working).copy(completed = setOf(0)),
            ),
        )
        workings.project(fixture.runtime)
        block(world, fixture.plan.rails.first()).type shouldBe Material.RAIL
        fixture.plan.rails.drop(1).forEach { position -> block(world, position).type shouldBe Material.AIR }
    }

    test("recovery-occupied geology rejects a candidate without writing a prepared scene") {
        val fixture = fixture(world, MineIncidentType.TUNNEL_DRIVE)
        populate(world, fixture.plan, fixture.placement)
        val blocked = fixture.plan.blocks.keys.first()
        val sceneOwner = owner(plugin)
        val workings = MineWorkingWorld(
            fixture.registry,
            sceneOwner,
            MockBukkitFarmBlockDataDecoder,
        ) { position -> position == blocked }

        workings.prepare(fixture.runtime, fixture.type, fixture.placement, fixture.nonce) shouldBe false
        workings.scene(fixture.runtime) shouldBe null
        workings.protects(block(world, blocked).location) shouldBe false
    }
})

private fun fixture(world: WorldMock, type: MineIncidentType): MineWorkingFixture {
    val placement = MineWorkingPlacement(
        WorksitePosition(world.name, 0, 64, 0),
        direction = 0,
        floorId = "fixture-floor",
    )
    val plan = MineWorkingLayout.plan(type, placement)
    val runtime = MineRuntime(
        settings = settings(world, type),
        region = CuboidActivityRegion(world, "mine_working_test", CuboidBounds(-16, 50, -16, 32, 80, 40)),
        cooldownMillis = 0L,
        state = MineShiftState(
            engineVersion = 2,
            phase = MinePhase.INCIDENT,
            sequence = 7L,
            incident = MineIncidentState(
                type = type,
                required = 1,
                objectiveNonce = 9L,
                working = ru.ruscrafting.farms.domain.MineWorkingEngine.initial(type, placement),
            ),
        ),
    )
    return MineWorkingFixture(MineRuntimeRegistry().also { it.replace(listOf(runtime)) }, runtime, type, placement, plan, 9L)
}

private fun settings(world: WorldMock, type: MineIncidentType): MineZoneSettings = MineZoneSettings(
    id = "mine_working_test",
    priority = 0,
    reference = ZoneReference(world.name, null, CuboidBounds(-16, 50, -16, 32, 80, 40)),
    permission = "arcfarms.mine",
    cartQuota = 4,
    hazardTrigger = 2,
    supportsRequired = 1,
    restoreSeconds = 2,
    temporaryMaterial = "DEEPSLATE",
    baseMaterial = "STONE",
    materialWeights = linkedMapOf("STONE" to 1),
    engineVersion = 2,
    orders = listOf(
        MineOrderSettings(
            id = "fixture_order",
            prospectingRequired = 1,
            miningRequired = 1,
            loadingRequired = 1,
            incidentTypes = listOf(type),
        ),
    ),
    incidentCountMin = 1,
    incidentCountMax = 1,
)

private fun owner(plugin: Plugin): WorksitePreparedSceneOwner {
    val leases = mutableSetOf<Triple<String, Int, Int>>()
    return WorksitePreparedSceneOwner(
        plugin = plugin,
        namespace = "mine_working",
        codec = FarmMoleBurrowWorksiteSceneCodec(plugin, "mine_working"),
        chunkRetention = WorksitePreparedSceneChunkRetention { chunk ->
            val key = Triple(chunk.world.name, chunk.x, chunk.z)
            check(leases.add(key))
            AutoCloseable { leases.remove(key) }
        },
        blockDataDecoder = WorksitePreparedSceneBlockDataDecoder { raw -> MockBukkitFarmBlockDataDecoder.decode(raw) },
    )
}

private fun populate(world: WorldMock, plan: MineWorkingPlan, placement: MineWorkingPlacement) {
    plan.blocks.keys.forEach { position -> block(world, position).type = Material.STONE }
    // The fixture is geological rock. Only the short entry approach is open;
    // the lateral working must actually cut into rock instead of inheriting an
    // already hollow room from the test world.
    val open = (plan.walkable + plan.cartRoute).filter { it.z - placement.entrance.z in 0..2 }
    open.forEach { position -> block(world, position).type = Material.AIR }
    val surfaceGround = placement.position(0, 0, -1)
    val surfaceHead = placement.position(0, 1, -1)
    block(world, surfaceGround).type = Material.STONE
    for (up in 0..2) block(world, surfaceHead.copy(y = surfaceHead.y + up)).type = Material.AIR
    plan.shell.firstOrNull()?.let { block(world, it).type = Material.DEEPSLATE }
    plan.excavation.lastOrNull()?.let { block(world, it).type = Material.TUFF }
}

private fun drain(owner: WorksitePreparedSceneOwner, workings: MineWorkingWorld, runtime: MineRuntime) {
    val sceneId = (requireNotNull(runtime.state.incident).objectiveNonce % 16).toInt()
    var rounds = 0
    while (owner.isBuilding(runtime.settings.id, runtime.state.sequence, sceneId)) {
        workings.process()
        check(++rounds < 32) { "prepared mine scene did not finish in bounded test drain" }
    }
}

private fun block(world: WorldMock, position: WorksitePosition) = world.getBlockAt(position.x, position.y, position.z)
