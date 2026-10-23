package ru.ruscrafting.farms.paper.mine.lift

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.block.BlockFace

class MineLiftCallButtonTest : FunSpec({
    test("native button lies wholly outside the wall face for all four orientations and three floors") {
        for (height in listOf(111.0, 97.0, 83.0)) {
            for ((yaw, facing) in listOf(0f to BlockFace.SOUTH, 90f to BlockFace.WEST,
                180f to BlockFace.NORTH, -90f to BlockFace.EAST)) {
                val panel = LiftPoint(25.0, height, 39.5, yaw)
                val button = MineLiftCallButton(panel)
                button.facing shouldBe facing
                button.displayOrigin.y shouldBe (height + 1.0 plusOrMinus 1e-8)
                // The button's virtual block is adjacent to the intact column;
                // the support-facing model edge is 2 mm outside its real face.
                val supportX = button.displayOrigin.x + .5 - facing.modX * .5
                val supportZ = button.displayOrigin.z + .5 - facing.modZ * .5
                ((supportX - panel.x) * facing.modX + (supportZ - panel.z) * facing.modZ) shouldBe
                    (.002 plusOrMinus 1e-8)
                button.label.yaw shouldBe yaw
                button.blockData shouldBe "minecraft:spruce_button[face=wall,facing=${facing.name.lowercase()},powered=false]"
            }
        }
    }
})
