package ru.ruscrafting.farms.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.FarmCropDamage
import ru.ruscrafting.farms.domain.FarmLocationOverrides
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.FarmSpecialIncidentState
import ru.ruscrafting.farms.persistence.FixedFarmCropJournal
import ru.ruscrafting.farms.persistence.MineRecoveryJournal
import java.nio.file.Files

class ArcFarmsRuntimeValidatorTest : FunSpec({
    test("runtime validation rejects an unknown configured back item") {
        val candidate = candidateWith(
            "  menu-back:\n    material: ARROW",
            "  menu-back:\n    material: NOT_A_PAPER_MATERIAL",
        )

        shouldThrow<IllegalStateException> { validator().validateRuntime(candidate) }
            .message shouldContain "Unknown Paper material: NOT_A_PAPER_MATERIAL"
    }

    test("runtime validation rejects non-item visuals through the complete enterprise catalog") {
        MockBukkitTestRuntime.open().use {
            val candidate = candidateWith(
                "market: {material: GOLD_INGOT, custom-model-data: 0}",
                "market: {material: AIR, custom-model-data: 0}",
            )

            shouldThrow<IllegalArgumentException> { validator().validateRuntime(candidate) }
                .message shouldContain "ui.enterprise-menu.items.market.material must be a non-air item material"
        }
    }

    test("runtime validation rejects action incident materials that cannot perform their configured role") {
        MockBukkitTestRuntime.open().use { paper ->
            paper.server.addSimpleWorld("sp11")
            paper.server.addSimpleWorld("world")

            shouldThrow<IllegalArgumentException> {
                validator().validateRuntime(candidateWith("shield-material: SHIELD", "shield-material: IRON_SWORD"))
            }.message shouldContain "boar-breakout.shield-material must be SHIELD"

            shouldThrow<IllegalArgumentException> {
                validator().validateRuntime(candidateWith("gun-material: PAPER", "gun-material: AIR"))
            }.message shouldContain "rival-raid.gun-material must be a non-air item"

        shouldThrow<IllegalArgumentException> {
            validator().validateRuntime(candidateWith("grenade-material: PAPER", "grenade-material: AIR"))
        }.message shouldContain "rival-raid.grenade-material must be a non-air item"
        shouldThrow<IllegalArgumentException> {
            validator().validateRuntime(candidateWith("grenade-preview-soil-material: COARSE_DIRT", "grenade-preview-soil-material: AIR"))
        }.message shouldContain "rival-raid.grenade-preview-soil-material must be a solid block"
        }
    }

    test("persisted recovery plots cannot escape the configured farm region") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("sp11")
            val gateway = mockk<RegionGateway>()
            every { gateway.resolve(any()) } returns CuboidActivityRegion(
                world,
                "farm",
                CuboidBounds(100, 50, 100, 200, 80, 200),
            )
            val outside = FarmPlotPosition(world.name, 250, 64, 150)
            val persisted = ArcFarmsState(
                farms = mapOf(
                    "communal_farm" to FarmShiftState(
                        phase = FarmPhase.COOLDOWN,
                        diseaseDamagedCrops = listOf(FarmCropDamage(outside, "WHEAT")),
                    ),
                ),
            )

            shouldThrow<IllegalArgumentException> {
                validator(gateway).validatePersisted(candidateWith("server-id: spawn", "server-id: spawn"), persisted)
            }.message shouldContain "farm-managed block escaped region communal_farm"
        }
    }

    test("persisted rival raid plots may use the bounded same-world island footprint") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("sp11")
            val gateway = mockk<RegionGateway>()
            every { gateway.resolve(any()) } returns CuboidActivityRegion(
                world,
                "farm",
                CuboidBounds(160, 40, 400, 240, 80, 510),
            )
            val candidate = rivalRaidCandidate()
            val persisted = rivalRaidState(
                candidate,
                plots = listOf(FarmPlotPosition(world.name, -18, 46, 735)),
            )

            validator(gateway).validatePersisted(candidate, persisted)
        }
    }

    test("persisted rival raid plots reject a different world and plots outside the rival radius") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("sp11")
            val gateway = mockk<RegionGateway>()
            every { gateway.resolve(any()) } returns CuboidActivityRegion(
                world,
                "farm",
                CuboidBounds(160, 40, 400, 240, 80, 510),
            )
            val candidate = rivalRaidCandidate()

            shouldThrow<IllegalArgumentException> {
                validator(gateway).validatePersisted(
                    candidate,
                    rivalRaidState(candidate, plots = listOf(FarmPlotPosition("world", -18, 46, 735))),
                )
            }.message shouldContain "rival farm plot escaped rival footprint"

            shouldThrow<IllegalArgumentException> {
                validator(gateway).validatePersisted(
                    candidate,
                    rivalRaidState(candidate, plots = listOf(FarmPlotPosition(world.name, 200, 46, 735))),
                )
            }.message shouldContain "rival farm plot escaped rival footprint"
        }
    }

    test("persisted rival raid point cannot escape its configured travel distance or world bounds") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("sp11")
            val gateway = mockk<RegionGateway>()
            every { gateway.resolve(any()) } returns CuboidActivityRegion(
                world,
                "farm",
                CuboidBounds(160, 40, 400, 240, 80, 510),
            )
            val candidate = rivalRaidCandidate()

            shouldThrow<IllegalArgumentException> {
                validator(gateway).validatePersisted(
                    candidate,
                    rivalRaidState(candidate, rival = FarmPointPosition("sp11", 1_000.0, 47.0, 735.5151402)),
                )
            }.message shouldContain "Persisted rival farm point is invalid"

            shouldThrow<IllegalArgumentException> {
                validator(gateway).validatePersisted(
                    candidate,
                    rivalRaidState(candidate, rival = FarmPointPosition("world", 55.4393975, 47.0, 735.5151402)),
                )
            }.message shouldContain "Persisted rival farm point is invalid"

            shouldThrow<IllegalArgumentException> {
                validator(gateway).validatePersisted(
                    candidate,
                    rivalRaidState(candidate, rival = FarmPointPosition("sp11", 55.4393975, -128.0, 735.5151402)),
                )
            }.message shouldContain "Persisted rival farm point is invalid"
        }
    }

    test("ordinary persisted incident plots remain inside the configured farm region") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("sp11")
            val gateway = mockk<RegionGateway>()
            every { gateway.resolve(any()) } returns CuboidActivityRegion(
                world,
                "farm",
                CuboidBounds(160, 40, 400, 240, 80, 510),
            )
            val candidate = candidateWith("server-id: spawn", "server-id: spawn")
            val order = candidate.farms.single().orders.first()
            val persisted = ArcFarmsState(
                farms = mapOf(
                    "communal_farm" to FarmShiftState(
                        phase = FarmPhase.INCIDENT,
                        orderId = order.id,
                        progress = order.required.keys.associateWith { 0 },
                        incidentCrop = order.required.keys.first(),
                        incidentType = FarmIncidentType.NIGHT_SHIFT,
                        incidentRequired = 1,
                        specialIncident = FarmSpecialIncidentState(
                            plots = listOf(FarmPlotPosition(world.name, 300, 46, 455)),
                        ),
                    ),
                ),
            )

            shouldThrow<IllegalArgumentException> {
                validator(gateway).validatePersisted(candidate, persisted)
            }.message shouldContain "farm-managed block escaped region communal_farm"
        }
    }

    test("persisted special crop recovery rejects crops removed from the farm catalog") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("sp11")
            val gateway = mockk<RegionGateway>()
            every { gateway.resolve(any()) } returns CuboidActivityRegion(
                world,
                "farm",
                CuboidBounds(100, 50, 100, 200, 80, 200),
            )
            val persisted = ArcFarmsState(
                farms = mapOf(
                    "communal_farm" to FarmShiftState(
                        phase = FarmPhase.COOLDOWN,
                        specialDamagedCrops = listOf(
                            FarmCropDamage(FarmPlotPosition(world.name, 150, 64, 150), "DIAMOND_BLOCK"),
                        ),
                    ),
                ),
            )

            shouldThrow<IllegalArgumentException> {
                validator(gateway).validatePersisted(candidateWith("server-id: spawn", "server-id: spawn"), persisted)
            }.message shouldContain "unknown crop"
        }
    }

    test("persisted incident points cannot move world mutations outside the farm") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("sp11")
            val gateway = mockk<RegionGateway>()
            every { gateway.resolve(any()) } returns CuboidActivityRegion(
                world,
                "farm",
                CuboidBounds(100, 50, 100, 200, 80, 200),
            )
            val candidate = candidateWith("server-id: spawn", "server-id: spawn")
            val order = candidate.farms.single().orders.first()
            val persisted = ArcFarmsState(
                farms = mapOf(
                    "communal_farm" to FarmShiftState(
                        phase = FarmPhase.INCIDENT,
                        orderId = order.id,
                        progress = order.required.keys.associateWith { 0 },
                        incidentCrop = order.required.keys.first(),
                        incidentType = FarmIncidentType.BARN_FIRE,
                        incidentRequired = 1,
                        specialIncident = FarmSpecialIncidentState(
                            points = listOf(FarmPointPosition(world.name, 250.0, 64.0, 150.0)),
                            active = setOf(0),
                        ),
                    ),
                ),
            )

            shouldThrow<IllegalArgumentException> {
                validator(gateway).validatePersisted(candidate, persisted)
            }.message shouldContain "incident point escaped region communal_farm"
        }
    }

    test("rival farm distance is measured from an overridden receiving point") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("sp11")
            val gateway = mockk<RegionGateway>()
            every { gateway.resolve(any()) } returns CuboidActivityRegion(
                world,
                "farm",
                CuboidBounds(900, 50, 900, 1_450, 80, 1_100),
            )
            val configured = candidateWith("server-id: spawn", "server-id: spawn")
            val overrides = FarmLocationOverrides(
                zones = mapOf(
                    "communal_farm" to mapOf(
                        FarmPointKind.RECEIVING to FarmPointPosition("sp11", 1_000.0, 64.0, 1_000.0),
                        FarmPointKind.RIVAL_FARM to FarmPointPosition("sp11", 1_400.0, 64.0, 1_000.0),
                    ),
                ),
            )

            validator(gateway).validateLocations(configured, overrides)
        }
    }
})

private fun validator(regionGateway: RegionGateway = mockk(relaxed = true)): ArcFarmsRuntimeValidator = ArcFarmsRuntimeValidator(
    regionGateway = regionGateway,
    economyAvailable = { true },
    fixedCropJournal = mockk<FixedFarmCropJournal>(relaxed = true),
    mineJournal = mockk<MineRecoveryJournal>(relaxed = true),
)

private fun rivalRaidState(
    candidate: ArcFarmsConfig,
    departure: FarmPointPosition = FarmPointPosition("sp11", 200.133, 49.0, 454.533),
    rival: FarmPointPosition = FarmPointPosition("sp11", 55.4393975, 47.0, 735.5151402),
    plots: List<FarmPlotPosition> = listOf(FarmPlotPosition("sp11", -18, 46, 735)),
): ArcFarmsState {
    val order = candidate.farms.single().orders.first()
    return ArcFarmsState(
        farms = mapOf(
            "communal_farm" to FarmShiftState(
                phase = FarmPhase.INCIDENT,
                orderId = order.id,
                progress = order.required.keys.associateWith { 0 },
                incidentCrop = order.required.keys.first(),
                incidentType = FarmIncidentType.RIVAL_RAID,
                incidentRequired = 1,
                specialIncident = FarmSpecialIncidentState(
                    points = listOf(departure, rival),
                    plots = plots,
                ),
            ),
        ),
    )
}

private fun candidateWith(old: String, replacement: String): ArcFarmsConfig {
    val root = Files.createTempDirectory("arcfarms-menu-runtime-validator")
    val source = requireNotNull(ArcFarmsRuntimeValidatorTest::class.java.classLoader.getResourceAsStream("config.yml"))
    val config = source.bufferedReader().use { it.readText() }
    require(old in config) { "Missing config fixture fragment: $old" }
    Files.writeString(root.resolve("config.yml"), config.replace(old, replacement))
    return ArcFarmsConfig.inspect(root)
}

private fun rivalRaidCandidate(): ArcFarmsConfig = candidateWith("worker-radius: 64.0", "worker-radius: 100.0")
