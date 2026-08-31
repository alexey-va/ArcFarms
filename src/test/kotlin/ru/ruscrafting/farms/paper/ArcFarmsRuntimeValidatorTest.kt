package ru.ruscrafting.farms.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.FarmLocationOverrides
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
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

private fun candidateWith(old: String, replacement: String): ArcFarmsConfig {
    val root = Files.createTempDirectory("arcfarms-menu-runtime-validator")
    val source = requireNotNull(ArcFarmsRuntimeValidatorTest::class.java.classLoader.getResourceAsStream("config.yml"))
    val config = source.bufferedReader().use { it.readText() }
    require(old in config) { "Missing config fixture fragment: $old" }
    Files.writeString(root.resolve("config.yml"), config.replace(old, replacement))
    return ArcFarmsConfig.inspect(root)
}
