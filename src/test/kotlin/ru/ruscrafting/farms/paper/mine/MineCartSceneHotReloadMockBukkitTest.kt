package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.bukkit.Material
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.joml.Vector3f
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.FarmItemDisplayTransform
import ru.ruscrafting.farms.config.MineCartVisualSettings
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.mine.extraction.MineExtractionRoute
import ru.ruscrafting.farms.paper.mine.extraction.MineCartScene
import ru.ruscrafting.farms.paper.mine.extraction.PaperMineCartEffects

class MineCartSceneHotReloadMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("reconfigure refreshes the active mine cart in place without resetting progress") {
        val world = paper.server.addSimpleWorld("world")
        val plugin = paper.createSimplePlugin("MineCartReloadTest")
        val graph = testMineComponentGraph(
            plugin = plugin,
            regions = CuboidRegionGateway(),
            port = immediateMinePort(),
            clock = { 1_000L },
            journal = ImmediateMineJournal(),
        )
        val initial = mineV2Settings()
        val state = MineShiftState(
            engineVersion = 2,
            phase = MinePhase.EXTRACTION,
            sequence = 4,
            orderId = "ore_run",
            routeIndex = 1,
        )
        val route = MineExtractionRoute(
            listOf(
                WorksitePosition(world.name, 1, 64, 1),
                WorksitePosition(world.name, 2, 64, 1),
                WorksitePosition(world.name, 3, 64, 1),
            ),
        )
        graph.module.rebuild(listOf(initial), mapOf(initial.id to state), 5_000L)
        val runtime = graph.registry.byId(initial.id)!!
        graph.cartScene.reconcile(runtime, route)
        val display = world.entities.filterIsInstance<ItemDisplay>().single()
        val interaction = world.entities.filterIsInstance<Interaction>().single()
        display.isPersistent shouldBe false
        interaction.isPersistent shouldBe false

        // Simulate a new controller instance adopting already-loaded tagged entities before it renders.
        val restartedScene = MineCartScene(PaperMineCartEffects(plugin))
        restartedScene.reconcileChunk(display.location.chunk)

        val updated = initial.copy(
            cartVisual = MineCartVisualSettings(
                material = "DIAMOND",
                customModelData = 777,
                itemModel = "minecraft:diamond",
                displayTransform = FarmItemDisplayTransform.FIXED,
                scale = 2.25f,
                yOffset = 0.75,
                viewRange = 10.0f,
                interactionWidth = 3.25f,
                interactionHeight = 2.25f,
            ),
        )
        graph.module.reconfigure(listOf(updated), graph.module.states(), 5_000L)
        val reloaded = graph.registry.byId(initial.id)!!
        restartedScene.reconcile(reloaded, route)

        reloaded shouldBeSameInstanceAs runtime
        reloaded.state.phase shouldBe MinePhase.EXTRACTION
        reloaded.state.routeIndex shouldBe 1
        world.entities.filterIsInstance<ItemDisplay>() shouldHaveSize 1
        world.entities.filterIsInstance<Interaction>() shouldHaveSize 1
        world.entities.filterIsInstance<ItemDisplay>().single() shouldBeSameInstanceAs display
        world.entities.filterIsInstance<Interaction>().single() shouldBeSameInstanceAs interaction
        display.itemStack.type shouldBe Material.DIAMOND
        @Suppress("DEPRECATION")
        display.itemStack.itemMeta.customModelData shouldBe 777
        // MockBukkit's ItemStack copy constructor currently drops the item-model component.
        // Parser coverage asserts the exact key while this integration test covers the live entity mutation.
        reloaded.settings.cartVisual.itemModel shouldBe "minecraft:diamond"
        display.itemDisplayTransform shouldBe ItemDisplay.ItemDisplayTransform.FIXED
        display.transformation.scale shouldBe Vector3f(2.25f)
        display.location.y shouldBe (65.75 plusOrMinus 0.0001)
        display.viewRange shouldBe 10.0f
        interaction.location.y shouldBe (65.0 plusOrMinus 0.0001)
        interaction.interactionWidth shouldBe 3.25f
        interaction.interactionHeight shouldBe 2.25f
    }
})
