package ru.ruscrafting.farms.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
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
})

private fun validator(): ArcFarmsRuntimeValidator = ArcFarmsRuntimeValidator(
    regionGateway = mockk(relaxed = true),
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
