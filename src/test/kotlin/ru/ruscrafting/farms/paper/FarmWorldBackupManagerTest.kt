package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.World
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.FarmPlotPosition

class FarmWorldBackupManagerTest : FunSpec({
    test("backup identifiers cannot escape the plugin data directory") {
        FarmWorldBackupManager.isValidId("20260825-120102-003_manual_1") shouldBe true
        FarmWorldBackupManager.isValidId("20260825-120102-003_pre_restore_z") shouldBe true
        FarmWorldBackupManager.isValidId("../latest") shouldBe false
        FarmWorldBackupManager.isValidId("20260825-120102-003_manual_1.schem") shouldBe false
        FarmWorldBackupManager.isValidId("20260825-120102-003/manual/1") shouldBe false
    }

    test("backup manifests retain their exact restore bounds") {
        val manifest = FarmBackupManifest(
            schemaVersion = 1,
            id = "20260825-120102-003_manual_1",
            zoneId = "communal_farm",
            world = "sp11",
            minX = 100,
            minY = 40,
            minZ = 300,
            maxX = 120,
            maxY = 70,
            maxZ = 340,
            volume = 26_691,
            createdAt = 1,
            reason = "manual",
            sha256 = "0".repeat(64),
        )

        manifest.minimum() shouldBe FarmPlotPosition("sp11", 100, 40, 300)
        manifest.maximum() shouldBe FarmPlotPosition("sp11", 120, 70, 340)
    }

    test("zone identifiers use the same bounded namespace as farm configuration") {
        FarmWorldBackupManager.isValidZone("communal_farm") shouldBe true
        FarmWorldBackupManager.isValidZone("Farm") shouldBe false
        FarmWorldBackupManager.isValidZone("../../world") shouldBe false
    }

    test("manual backups use the complete configured farm region") {
        val world = mockk<World> { every { name } returns "sp11" }
        val area = FarmBackupArea.wholeRegion(
            CuboidActivityRegion(
                world = world,
                label = "sp11:farm",
                bounds = CuboidBounds(109, -64, 363, 298, 139, 575),
            ),
        )

        area.minimum shouldBe FarmPlotPosition("sp11", 109, -64, 363)
        area.maximum shouldBe FarmPlotPosition("sp11", 298, 139, 575)
        area.volume shouldBe 8_255_880L
    }
})
