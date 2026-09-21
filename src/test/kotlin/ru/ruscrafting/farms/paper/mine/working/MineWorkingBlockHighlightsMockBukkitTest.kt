package ru.ruscrafting.farms.paper.mine.working

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Axis
import org.bukkit.Material
import org.bukkit.block.data.Orientable
import org.bukkit.entity.BlockDisplay
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.MineIncidentState
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.MineWorkingStage
import ru.ruscrafting.farms.domain.MineWorkingState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.mineV2Settings

class MineWorkingBlockHighlightsMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("diamond goal outlines actual veins and retires every glow entity") {
        val world = paper.server.addSimpleWorld("world")
        val plugin = paper.createSimplePlugin("MineWorkingBlockHighlightsTest")
        val placement = MineWorkingPlacement(
            WorksitePosition(world.name, 18, 64, 18), direction = 0, floorId = "fixture-floor", geometryVersion = 6,
        )
        val plan = MineWorkingLayout.plan(MineIncidentType.TUNNEL_DRIVE, placement)
        val runtime = MineRuntime(
            settings = mineV2Settings(),
            region = CuboidActivityRegion(world, "old_shafts", CuboidBounds(-32, 48, -32, 64, 80, 64)),
            cooldownMillis = 0L,
            state = MineShiftState(
                engineVersion = 2,
                phase = MinePhase.INCIDENT,
                sequence = 11,
                incident = MineIncidentState(
                    type = MineIncidentType.TUNNEL_DRIVE,
                    required = 1,
                    objectiveNonce = 17,
                    working = MineWorkingState(placement, MineWorkingStage.EXCAVATE),
                ),
            ),
        )
        val scene = mockk<MineWorkingScene>()
        every { scene.plan } returns plan

        MineDriveLayout.goalOres(plan).forEach { world.getBlockAt(it.x,it.y,it.z).type = Material.DEEPSLATE_DIAMOND_ORE }
        val highlights=MineWorkingBlockHighlights(plugin)
        highlights.reconcile(runtime,scene)
        val glows=world.entities.filterIsInstance<BlockDisplay>()
        (glows.size>=12) shouldBe true
        glows.all { it.isGlowing && it.block.material==Material.DEEPSLATE_DIAMOND_ORE } shouldBe true
        highlights.cleanup(runtime.settings.id)
        world.entities.filterIsInstance<BlockDisplay>().size shouldBe 0
    }


    test("support frame previews keep log axes and full brightness") {
        val world = paper.server.addSimpleWorld("world")
        val plugin = paper.createSimplePlugin("MineWorkingBlockHighlightsTest")
        val placement = MineWorkingPlacement(
            WorksitePosition(world.name, 18, 64, 18), direction = 0, floorId = "fixture-floor", geometryVersion = 4,
        )
        val plan = MineWorkingLayout.plan(MineIncidentType.TUNNEL_DRIVE, placement)
        val runtime = MineRuntime(
            settings = mineV2Settings(),
            region = CuboidActivityRegion(world, "old_shafts", CuboidBounds(-32, 48, -32, 64, 80, 64)),
            cooldownMillis = 0L,
            state = MineShiftState(
                engineVersion = 2,
                phase = MinePhase.INCIDENT,
                sequence = 11,
                incident = MineIncidentState(
                    type = MineIncidentType.TUNNEL_DRIVE,
                    required = 1,
                    objectiveNonce = 17,
                    working = MineWorkingState(placement, MineWorkingStage.SUPPORT),
                ),
            ),
        )
        val scene = mockk<MineWorkingScene>()
        every { scene.plan } returns plan

        MineWorkingBlockHighlights(plugin).reconcile(runtime, scene)

        fun displayAt(position: WorksitePosition): BlockDisplay = world.entities.filterIsInstance<BlockDisplay>().single {
            it.location.blockX == position.x && it.location.blockY == position.y && it.location.blockZ == position.z
        }
        fun assertSupport(position: WorksitePosition, axis: Axis) {
            val display = displayAt(position)
            display.block.material shouldBe Material.SPRUCE_LOG
            (display.block as Orientable).axis shouldBe axis
            display.brightness?.blockLight shouldBe 15
            display.brightness?.skyLight shouldBe 15
            display.isGlowing shouldBe true
            display.transformation.scale.x shouldBe 1.002f
            display.transformation.scale.y shouldBe 1.002f
            display.transformation.scale.z shouldBe 1.002f
        }
        val frame = plan.supportBlocks(0)
        assertSupport(frame.entries.first { it.value.contains("axis=y") }.key, Axis.Y)
        assertSupport(frame.entries.first { it.value.contains("axis=x") }.key, Axis.X)
    }
})
