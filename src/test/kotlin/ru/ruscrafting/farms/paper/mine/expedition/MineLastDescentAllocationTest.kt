package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionGenerator
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlacement
import ru.ruscrafting.farms.domain.mine.expedition.MineLastDescentLayout
import ru.ruscrafting.farms.persistence.MineExpeditionSceneReceipt

class MineLastDescentAllocationTest : FunSpec({
    val site = MineExpeditionSite("world", 40, -112, 111, 10.5, 111.0, 10.5)

    fun receipt(
        kind: MineExpeditionKind,
        placement: MineExpeditionPlacement,
        reserved: Boolean = true,
        siteBuilt: Boolean = false,
    ) = MineExpeditionSceneReceipt(
        zoneId = "reserve",
        sequence = 0L,
        objectiveNonce = 1L,
        journalSequence = 1L,
        kind = kind,
        placement = placement,
        surfaceWorld = site.world,
        surfaceX = site.surfaceX,
        surfaceY = site.surfaceY,
        surfaceZ = site.surfaceZ,
        reserved = reserved,
        siteBuilt = siteBuilt,
    )

    test("last descent allocation puts entry at the surface and roof at world y 122") {
        val placement = MineExpeditionAllocation.allocate(MineExpeditionKind.LAST_DESCENT, 1L, emptyList(), site)

        placement.geometryVersion shouldBe MineExpeditionPlacement.CURRENT_GEOMETRY_VERSION
        placement.originY + MineLastDescentLayout.ENTRY_Y shouldBe 111
        placement.originY + 68 shouldBe 122
    }

    test("ark and factory keep the v3 floor-relative origin") {
        for (kind in listOf(MineExpeditionKind.DRILLING_ARK, MineExpeditionKind.DEAD_FACTORY)) {
            val placement = MineExpeditionAllocation.allocate(kind, kind.ordinal.toLong() + 1L, emptyList(), site)

            placement.geometryVersion shouldBe MineExpeditionGenerator.currentGeometryVersion(kind)
            placement.geometryVersion shouldBe 3
            placement.originY shouldBe site.floorY - 5
        }
    }

    test("only an unbuilt reserved v4 last descent with the old height needs relocation") {
        val wrong = MineExpeditionPlacement(site.world, 40, site.floorY - 5, -112, 73L, geometryVersion = 4)

        MineExpeditionAllocation.needsRelocation(receipt(MineExpeditionKind.LAST_DESCENT, wrong), site) shouldBe true
        MineExpeditionAllocation.needsRelocation(
            receipt(MineExpeditionKind.LAST_DESCENT, wrong, siteBuilt = true),
            site,
        ) shouldBe false
        MineExpeditionAllocation.needsRelocation(
            receipt(MineExpeditionKind.LAST_DESCENT, wrong, reserved = false),
            site,
        ) shouldBe false
        MineExpeditionAllocation.needsRelocation(
            receipt(MineExpeditionKind.LAST_DESCENT, wrong.copy(geometryVersion = 3)),
            site,
        ) shouldBe false
        MineExpeditionAllocation.needsRelocation(
            receipt(MineExpeditionKind.LAST_DESCENT, wrong.copy(originY = 54)), site,
        ) shouldBe false
    }
})
