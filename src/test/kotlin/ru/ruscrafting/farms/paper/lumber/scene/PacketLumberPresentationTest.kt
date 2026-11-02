package ru.ruscrafting.farms.paper.lumber.scene

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.SceneDisplayStyle
import ru.arc.paper.scene.ScenePart
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.lumber.*
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillDisplays

class PacketLumberPresentationTest : FunSpec({
    test("shared workpiece styles preserve every rendered field for both glow inputs and content types") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("lumber_style")
            val presentation = PacketLumberPresentation(paper.createSimplePlugin("LumberStyle"),
                mockk<PaperPacketDisplays>(relaxed = true), world.name, "arcfarms.use",
                LumberPose(world.name, 188.5, 73.0, 182.2)) {}
            val render = PacketLumberPresentation::class.java.getDeclaredMethod(
                "rendered", World::class.java, LumberSceneGeometry.Part::class.java, LumberVisualView::class.java,
            ).apply { isAccessible = true }
            val baseStyle = SceneDisplayStyle(Display.Billboard.FIXED, Display.Brightness(12, 12),
                0, 2, 1.25f, 0f, 0f, 0f, 0f, false, null)
            val styles = mutableMapOf<Boolean, SceneDisplayStyle>()
            val tree = LumberTreeSnapshot(WorksitePosition(world.name, -17, 72, 31), emptyList(), emptyList())
            try {
                for (root in listOf(null, tree)) for (action in listOf(null, LumberInput(LumberAction.CHOP_TREE))) {
                    for (highlight in listOf(false, true)) for (visible in listOf(false, true)) {
                        for (model in listOf(null, "arc_farms:cedar_sawmill_raw")) {
                            val part = LumberSceneGeometry.Part("workpiece", Vector3f(-16.5f, 74f, 33f),
                                Vector3f(.5f, 2f, .75f), Quaternionf().rotationY(.7f),
                                block = "minecraft:oak_log[axis=y]", itemModel = model,
                                visible = visible, action = action, highlight = highlight)
                            val view = LumberVisualView(LumberState(tree = root), 0, emptySet(), 0, 0)
                            val actual = render.invoke(presentation, world, part, view) as ScenePart<*>
                            val glowing = action != null || highlight
                            actual.style shouldBe baseStyle.copy(glowing = glowing,
                                glowColor = if (glowing) Color.fromRGB(0xffc857) else null)
                            (actual.style === styles.getOrPut(glowing) { actual.style }) shouldBe true
                            val again = render.invoke(presentation, world, part, view) as ScenePart<*>
                            (again.style === actual.style) shouldBe true
                            actual.key shouldBe part.id
                            actual.visible shouldBe visible
                            val anchor = if (root == null) Location(world, 188.5, 73.0, 182.2)
                                else Location(world, -17.0, 72.0, 31.0)
                            val translation = Vector3f(part.center).sub(anchor.x.toFloat(), anchor.y.toFloat(), anchor.z.toFloat())
                            if (model == null) translation.add(part.rotation.transform(Vector3f(part.size).mul(-.5f)))
                            actual.pose.location() shouldBe anchor
                            actual.pose.transformation() shouldBe Transformation(translation, part.rotation, part.size, Quaternionf())
                            if (model == null) (actual as ScenePart.Block<*>).block() shouldBe Bukkit.createBlockData(part.block!!)
                            else {
                                (actual as ScenePart.Item<*>).item() shouldBe CedarSawmillDisplays.item(model)
                                actual.itemTransform shouldBe ItemDisplay.ItemDisplayTransform.NONE
                            }
                        }
                    }
                }
                styles.size shouldBe 2
            } finally { presentation.cleanup("test") }
        }
    }
})
