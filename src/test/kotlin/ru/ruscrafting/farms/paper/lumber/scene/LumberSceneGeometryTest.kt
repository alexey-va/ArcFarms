package ru.ruscrafting.farms.paper.lumber.scene

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus
import org.joml.Vector3f
import ru.ruscrafting.farms.domain.lumber.LumberBlockSnapshot
import ru.ruscrafting.farms.domain.lumber.LumberPhase
import ru.ruscrafting.farms.domain.lumber.LumberPose
import ru.ruscrafting.farms.domain.lumber.LumberState
import ru.ruscrafting.farms.domain.lumber.LumberTreeSnapshot
import ru.ruscrafting.farms.domain.lumber.LumberVisualView
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillAnimation
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillGeometry
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillController

class LumberSceneGeometryTest : FunSpec({
    val root = WorksitePosition("rc_atelier_cedar_sawmill", 140, 72, 204)
    val tree = LumberTreeSnapshot(root, (0..11).map { LumberBlockSnapshot(root.copy(y = root.y + it), "minecraft:oak_log[axis=y]") }, emptyList())

    test("fall starts at captured blocks and lands away from the two-block visible stump") {
        fun parts(age: Int) = LumberSceneGeometry.sample(LumberVisualView(LumberState(phase = LumberPhase.FALLING, tree = tree), age, emptySet(), 0, 0))
        val first = parts(0).first()
        first.center shouldBe Vector3f(140.5f, 74.5f, 204.5f)
        val last = parts(60).first()
        last.center.x shouldBe (141.5f plusOrMinus .0001f)
        last.center.y shouldBe (72.5f plusOrMinus .0001f)
        last.center.z shouldBe (204.5f plusOrMinus .0001f)
        LumberSceneGeometry.branchTargets(tree).size shouldBe 3
        LumberSceneGeometry.branchTargets(tree).distinct().size shouldBe 3
        LumberSceneGeometry.fallCollisionCells(tree).any {
            it.x == root.x && it.z == root.z && it.y < root.y + 2
        } shouldBe false
    }

    test("outdoor transfer meets every exact saw input section without changing stock length") {
        val cart = LumberSceneGeometry.receivingCenter().copy(z = 204.75, yaw = 180f)
        val view = LumberVisualView(LumberState(phase = LumberPhase.LOADING_SAWMILL, tree = tree, cart = cart), ru.ruscrafting.farms.domain.lumber.LumberTiming.SAWMILL_LOAD_TICKS, emptySet(), 0, 0)
        val incoming = LumberSceneGeometry.sample(view).filter { it.itemModel != null }
        val expected = CedarSawmillAnimation.sample(0.0).poses.filter { it.partId.endsWith("-raw") }
        incoming.size shouldBe 5
        incoming.zip(expected).forEach { (actual, wanted) ->
            actual.id shouldBe wanted.partId
            actual.center.distance(Vector3f(wanted.center).add(CedarSawmillGeometry.origin)) shouldBe (0f plusOrMinus .0001f)
            actual.size shouldBe wanted.size
        }
        incoming.sumOf { it.size.z.toDouble() } shouldBe (7.48 plusOrMinus .0001)
    }

    test("pickup glows on the whole stem and handoff starts at any frozen trailing pose") {
        val loading = LumberVisualView(LumberState(phase = LumberPhase.LOADING_CART, tree = tree), 0, emptySet(), 0, 0)
        val stem = LumberSceneGeometry.sample(loading).filter { it.id.startsWith("tree-") }
        stem.isNotEmpty() shouldBe true
        stem.all { it.action?.action == ru.ruscrafting.farms.domain.lumber.LumberAction.LOAD_CART } shouldBe true
        for (from in listOf(LumberSceneGeometry.receivingCenter().copy(z = 204.75, yaw = 180f),
                LumberPose(root.world, 195.0, 73.0, 205.5, 47f), LumberPose(root.world, 185.0, 73.0, 201.5, -90f))) {
            LumberSceneGeometry.loadingStock(from, 0.0) shouldBe LumberSceneGeometry.draggedStock(from)
            val end = LumberSceneGeometry.loadingStock(from, 1.0)
            end.x shouldBe (LumberSceneGeometry.sawIntake().x plusOrMinus .0001)
            end.y shouldBe (LumberSceneGeometry.sawIntake().y plusOrMinus .0001)
            end.z shouldBe (LumberSceneGeometry.sawIntake().z plusOrMinus .0001)
            LumberSceneGeometry.sample(LumberVisualView(LumberState(phase = LumberPhase.HAULING, cart = from),
                0, emptySet(), 0, 0)).any { it.id.startsWith("cart-") } shouldBe false
        }
    }

    test("collected boards start at the exact finished three-layer output and never scale down") {
        val boards = LumberSceneGeometry.boards(LumberSceneGeometry.outputPickup())
        val final = CedarSawmillAnimation.sample(25.0, 1).poses.associateBy { it.partId }
        boards.size shouldBe 15
        boards.forEach { actual ->
            val expected = final.getValue(actual.id)
            actual.center.distance(Vector3f(expected.center).add(CedarSawmillGeometry.origin)) shouldBe (0f plusOrMinus .0001f)
            actual.size shouldBe expected.size
            kotlin.math.abs(actual.rotation.dot(expected.rotation)) shouldBe (1f plusOrMinus .0001f)
        }
        val cant = boards.single { it.id == "timber-final-3" }
        val plank = boards.single { it.id == "timber-board-3" }
        cant.rotation.transform(Vector3f(1f, 0f, 0f)).y shouldBe (1f plusOrMinus .0001f)
        kotlin.math.abs(cant.center.z - plank.center.z) shouldBe (.272f plusOrMinus .0001f)
    }

    test("crane keeps all fifteen pieces continuous from output to warehouse and below the real ceiling") {
        val warehouse = LumberPose(root.world, 194.1, 73.0, 185.5)
        LumberSceneGeometry.craneStock(warehouse, 0.0) shouldBe LumberSceneGeometry.outputPickup()
        LumberSceneGeometry.craneStock(warehouse, 1.0) shouldBe LumberSceneGeometry.warehouseStock(warehouse)
        val view = LumberVisualView(LumberState(phase = LumberPhase.CRANE_TRANSFER,
            sawPasses = 2), 60, emptySet(), 0, 0)
        val parts = LumberSceneGeometry.sample(view, warehouse)
        parts.count { it.id.startsWith("timber-") } shouldBe 15
        parts.none { it.id.startsWith("board-cart-") } shouldBe true
        val models = LumberSceneGeometry::class.java.getResourceAsStream("/sawmill/cedar-machine.json")!!.bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject.getAsJsonObject("nativeModels")
        }
        val stockTop = parts.filter { it.id.startsWith("timber-") }.maxOf { part ->
            val boxes = part.itemModel?.let { models.getAsJsonObject(it.substringAfterLast('_')).getAsJsonArray("boxes") }
            val vertices = boxes?.flatMap { box ->
                val lower = box.asJsonObject.getAsJsonArray("min").map { it.asFloat }
                val upper = box.asJsonObject.getAsJsonArray("max").map { it.asFloat }
                (0..7).map { i -> Vector3f(if (i and 1 == 0) lower[0] else upper[0], if (i and 2 == 0) lower[1] else upper[1], if (i and 4 == 0) lower[2] else upper[2]) }
            } ?: (0..7).map { i -> Vector3f(if (i and 1 == 0) -.5f else .5f, if (i and 2 == 0) -.5f else .5f, if (i and 4 == 0) -.5f else .5f) }
            vertices.maxOf { it.mul(part.size).rotate(part.rotation).add(part.center).y }
        }
        stockTop shouldBe (78.514f plusOrMinus .0001f)
        (stockTop < 79.0) shouldBe true
        parts.filter { it.id.startsWith("crane-roof-mount-") }.forEach {
            (it.center.y + it.size.y / 2) shouldBe (79f plusOrMinus .0001f)
        }
        CedarSawmillController.frame(view).poses.none { it.visible && it.partId.startsWith("timber-") &&
            CedarSawmillGeometry.parts.first { part -> part.id == it.partId }.motion.startsWith("timber-") } shouldBe true
    }

    test("only the currently available tree lever or output carries an actionable highlight") {
        fun view(phase: LumberPhase, running: Boolean = false) = LumberVisualView(
            LumberState(phase = phase, tree = tree, sawRunning = running), 0, emptySet(), 0, 0)
        LumberSceneGeometry.sample(view(LumberPhase.CHOPPING)).count { it.id.startsWith("standing-") && it.highlight } shouldBe tree.blocks.size
        CedarSawmillController.highlightedParts(view(LumberPhase.SAWING)).isNotEmpty() shouldBe true
        CedarSawmillController.highlightedParts(view(LumberPhase.SAWING, true)) shouldBe emptySet()
        CedarSawmillController.highlightedParts(view(LumberPhase.COLLECTING_BOARDS)) shouldBe emptySet()
        LumberSceneGeometry.sample(view(LumberPhase.LOADING_CART)).none { it.id.startsWith("receiving-") } shouldBe true
        val halfPass = view(LumberPhase.SAWING, true).copy(sawElapsedTicks = ru.ruscrafting.farms.domain.lumber.LumberTiming.SAW_PASS_TICKS / 2)
        CedarSawmillController.frame(halfPass).poses shouldBe CedarSawmillAnimation.sample(12.5).poses
    }

    test("selected tree clones the whole captured tree exactly and masks only that tree for participants") {
        val withLeaves = tree.copy(leaves = listOf(LumberBlockSnapshot(root.copy(x = root.x + 1, y = root.y + 8), "minecraft:oak_leaves[persistent=true]")))
        val state = LumberState(phase = LumberPhase.CHOPPING, tree = withLeaves)
        val pieces = LumberSceneGeometry.sample(LumberVisualView(state, 0, emptySet(), 0, 0))
        pieces.size shouldBe withLeaves.blocks.size
        pieces.zip(withLeaves.blocks).forEach { (part, block) ->
            part.center shouldBe Vector3f(block.position.x + .5f, block.position.y + .5f, block.position.z + .5f)
            part.size shouldBe Vector3f(1f)
            part.block shouldBe block.blockData
            part.highlight shouldBe true
        }
        LumberSceneGeometry.maskedTreeBlocks(state) shouldBe withLeaves.blocks
        LumberSceneGeometry.maskedTreeBlocks(state.copy(phase = LumberPhase.FALLING)) shouldBe withLeaves.maskedBlocks
        LumberSceneGeometry.maskedTreeBlocks(state.copy(phase = LumberPhase.IDLE)) shouldBe emptyList()
    }

    test("accepted hits grow the notch and briefly recoil with chips but recovered state stays still") {
        val state = LumberState(phase = LumberPhase.CHOPPING, tree = tree, chopHits = 1)
        val steady = LumberVisualView(state, 0, emptySet(), 0, 0)
        val impact = steady.copy(chopHitAgeTicks = 2)
        val before = LumberSceneGeometry.sample(steady)
        val during = LumberSceneGeometry.sample(impact)
        before.none { it.id.startsWith("chop-chip-") } shouldBe true
        during.count { it.id.startsWith("chop-chip-") } shouldBe 7
        (during.single { it.id == "standing-11" }.center.distance(before.single { it.id == "standing-11" }.center) in .01f.. .15f) shouldBe true
        during.single { it.id == "standing-0" }.center shouldBe before.single { it.id == "standing-0" }.center
        val later = LumberSceneGeometry.sample(steady.copy(state = state.copy(chopHits = 4)))
        val notch = before.single { it.id == "standing-1-notch-2" }
        (later.single { it.id == notch.id }.size.x < notch.size.x) shouldBe true
        LumberChopGeometry.chips(impact.copy(chopHitAgeTicks = LumberChopGeometry.HIT_TICKS)) shouldBe emptyList()
        LumberChopGeometry.chips(impact.copy(state = state.copy(chopHits = 0))) shouldBe emptyList()
    }

    test("hit chips fan around both sides and stay outside the stem at every age and facing") {
        for ((fallX, fallZ) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val state = LumberState(phase = LumberPhase.CHOPPING, tree = tree.copy(fallX = fallX, fallZ = fallZ), chopHits = 1)
            for (age in 0 until LumberChopGeometry.HIT_TICKS) {
                val chips = LumberChopGeometry.chips(LumberVisualView(state, 0, emptySet(), 0, 0, chopHitAgeTicks = age))
                chips.size shouldBe 7
                chips.count { (it.center.x - root.x - .5f) * fallX + (it.center.z - root.z - .5f) * fallZ < -.5f } shouldBe 2
                for (chip in chips) {
                    val corners = buildList {
                        for (x in listOf(-.5f, .5f)) for (y in listOf(-.5f, .5f)) for (z in listOf(-.5f, .5f)) {
                            add(Vector3f(x, y, z).mul(chip.size).rotate(chip.rotation).add(chip.center))
                        }
                    }
                    (corners.maxOf { it.x } < root.x || corners.minOf { it.x } > root.x + 1 ||
                        corners.maxOf { it.z } < root.z || corners.minOf { it.z } > root.z + 1) shouldBe true
                }
            }
        }
    }
})
