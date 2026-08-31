package ru.ruscrafting.farms.paper.farm.incident.frost

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmFrostSettings
import ru.ruscrafting.farms.config.FarmSpecialIncidentSettings
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import java.util.concurrent.CompletableFuture

class FarmFrostIncidentMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
    }

    afterEach { paper.close() }

    test("indexed mature crop becomes a campfire and clear restores its exact growth state") {
        val fixture = frostFixture(paper, world)

        fixture.controller.initialize(fixture.runtime) shouldBe true

        fixture.runtime.state.frost!!.campfires shouldHaveSize 1
        fixture.runtime.state.specialDamagedCrops shouldHaveSize 1
        fixture.crop.type shouldBe Material.CAMPFIRE
        fixture.controller.protects(fixture.crop.location) shouldBe true

        fixture.controller.clear(fixture.runtime, "test")

        fixture.crop.type shouldBe Material.WHEAT
        (fixture.crop.blockData as Ageable).age shouldBe (fixture.crop.blockData as Ageable).maximumAge
        fixture.runtime.state.specialDamagedCrops shouldHaveSize 0
    }

    test("immature indexed crops are never replaced by frost campfires") {
        val fixture = frostFixture(paper, world, mature = false)

        fixture.controller.initialize(fixture.runtime) shouldBe false

        fixture.crop.type shouldBe Material.WHEAT
        (fixture.crop.blockData as Ageable).age shouldBe (fixture.crop.blockData as Ageable).maximumAge - 1
        fixture.runtime.state.frost shouldBe null
        fixture.runtime.state.specialDamagedCrops shouldBe emptyList()
    }

    test("mature crops outside the durable bed index are ignored") {
        val fixture = frostFixture(paper, world, indexed = false)

        fixture.controller.initialize(fixture.runtime) shouldBe false

        fixture.crop.type shouldBe Material.WHEAT
        fixture.runtime.state.frost shouldBe null
    }

    test("frost does not start without an explicitly configured firewood point") {
        val fixture = frostFixture(paper, world, firewoodConfigured = false)

        fixture.controller.initialize(fixture.runtime) shouldBe false

        fixture.crop.type shouldBe Material.WHEAT
        fixture.runtime.state.frost shouldBe null
    }

    test("woodpile is upright and grounded then proximity carry fuels the campfire") {
        val fixture = frostFixture(paper, world, woodpileScale = 3f, woodpileYOffset = 0.375)
        val player = paper.addPlayer("Farmer")
        every { fixture.port.players(any()) } returns listOf(player)
        every { fixture.port.hasAccess(player, "arcfarms.farm") } returns true
        every { fixture.port.allowInteraction(any(), any()) } returns true

        fixture.controller.initialize(fixture.runtime) shouldBe true
        fixture.controller.ensure(fixture.runtime)

        val display = world.entities.filterIsInstance<ItemDisplay>().single()
        world.entities.filterIsInstance<Interaction>() shouldHaveSize 1
        display.itemDisplayTransform shouldBe ItemDisplay.ItemDisplayTransform.GROUND
        display.location.y shouldBe 65.375
        display.location.pitch shouldBe 0f
        display.location.yaw shouldBe 37f
        display.transformation.scale shouldBe org.joml.Vector3f(3f, 3f, 3f)

        player.teleport(Location(world, 5.5, 65.0, 5.5))
        fixture.controller.onMove(player, fixture.runtime) shouldBe true
        player.inventory.contents.count(fixture.controller::isServiceItem) shouldBe 1

        player.teleport(Location(world, 1.5, 65.0, 1.5))
        fixture.controller.onMove(player, fixture.runtime) shouldBe true

        player.inventory.contents.count(fixture.controller::isServiceItem) shouldBe 0
        fixture.runtime.state.frost!!.campfires.single().fuelUntil shouldBe 31_000
        fixture.runtime.state.contributors[player.uniqueId] shouldBe 1
        fixture.crop.type shouldBe Material.CAMPFIRE
    }
})

private data class FrostFixture(
    val runtime: FarmRuntime,
    val controller: FarmFrostIncident,
    val port: WorksiteRuntimePort,
    val crop: org.bukkit.block.Block,
)

private fun frostFixture(
    paper: MockBukkitTestRuntime,
    world: WorldMock,
    woodpileScale: Float = 1f,
    woodpileYOffset: Double = 0.0,
    mature: Boolean = true,
    indexed: Boolean = true,
    firewoodConfigured: Boolean = true,
): FrostFixture {
    val soil = world.getBlockAt(1, 64, 1)
    soil.type = Material.FARMLAND
    val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
    crop.setBlockData(Material.WHEAT.createBlockData().also { data ->
        data as Ageable
        data.age = if (mature) data.maximumAge else data.maximumAge - 1
    })
    val frost = FarmFrostSettings(
        campfireMinCount = 1,
        campfireMaxCount = 1,
        bedsPerCampfire = 16,
        targetTemperature = 4,
        heatPerSecondPerFire = 2,
        coolingSecondsPerDegree = 10,
        fuelSeconds = 30,
        pickupRadius = 1.75,
        deliveryRadius = 2.25,
        fuelMaterial = "OAK_LOG",
        woodpileMaterial = "OAK_LOG",
        woodpileCustomModelData = 0,
        woodpileDisplayTransform = "GROUND",
        woodpileScale = woodpileScale,
        woodpileYOffset = woodpileYOffset,
        woodpileYawOffset = 0f,
        woodpileViewRange = 3f,
    )
    val special = mockk<FarmSpecialIncidentSettings>(relaxed = true) { every { this@mockk.frost } returns frost }
    val zone = mockk<FarmZoneSettings>(relaxed = true) {
        every { id } returns "communal_farm"
        every { permission } returns "arcfarms.farm"
        every { crops } returns setOf("WHEAT")
        every { specialIncidents } returns special
    }
    val runtime = FarmRuntime(
        zone,
        CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15)),
        emptyMap(),
        emptyList(),
        mockk(relaxed = true),
        FarmShiftState(
            phase = FarmPhase.INCIDENT,
            sequence = 7,
            placementSequence = 11,
            incidentCrop = "WHEAT",
            incidentType = FarmIncidentType.FROST,
            incidentRequired = 1,
        ),
    )
    val port = mockk<WorksiteRuntimePort>(relaxed = true) {
        every { persistAsync() } returns CompletableFuture.completedFuture(Unit)
    }
    val plugin = paper.createSimplePlugin("FrostIncidentTest")
    val locale = mockk<ArcFarmsLocale>(relaxed = true) {
        every { render(any(), any(), any()) } returns Component.text("Campfire log")
    }
    val controller = FarmFrostIncident(
        plugin = plugin,
        settings = { mockk<ArcFarmsConfig>(relaxed = true) },
        locale = locale,
        debug = ArcFarmsDebug({ false }) {},
        access = port,
        audience = port,
        state = port,
        ledger = FarmBlockLedger(plugin),
        beds = FarmIncidentBedProvider {
            if (indexed) setOf(FarmPlotPosition(world.name, 1, 64, 1)) else emptySet()
        },
        points = object : FarmPointProvider {
            private val firewood = FarmPointPosition(world.name, 5.5, 65.0, 5.5, yaw = 37f)

            override fun resolve(runtime: FarmRuntime, kind: FarmPointKind) = firewood

            override fun configured(runtime: FarmRuntime, kind: FarmPointKind) =
                firewood.takeIf { firewoodConfigured && kind == FarmPointKind.FIREWOOD }
        },
        transitions = FarmTransitionSink { target, result, _ -> if (result.accepted) target.state = result.state },
        runtimes = { listOf(runtime) },
        clock = { 1_000L },
    )
    return FrostFixture(runtime, controller, port, crop)
}
