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
    }

    test("boards slide clear of the output table before lowering onto the cart without a shoulder attachment") {
        val home = LumberSceneGeometry.boardCartHome()
        LumberSceneGeometry.boardLoadingStock(home, 0.0) shouldBe LumberSceneGeometry.outputPickup()
        LumberSceneGeometry.boardLoadingStock(home, 1.0) shouldBe LumberSceneGeometry.boardCartStock(home)
        for (tick in 0..40) {
            val stock = LumberSceneGeometry.boardLoadingStock(home, tick / 40.0)
            // The outer face of the shifted table is X193.313; stock half-width is conservatively .816.
            if (stock.x - .816 < 193.313) (stock.y >= LumberSceneGeometry.outputPickup().y - .0001) shouldBe true
        }
        val view = LumberVisualView(LumberState(phase = LumberPhase.COLLECTING_BOARDS,
            sawPasses = 2, cart = home, boardCartLoaded = true), 0, emptySet(), 0, 0)
        val parts = LumberSceneGeometry.sample(view)
        parts.count { it.id.startsWith("timber-") } shouldBe 15
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
        stockTop shouldBe (home.y.toFloat() + 1.494f plusOrMinus .0001f)
        (stockTop < home.y + 1.5) shouldBe true
        parts.filter { it.id.startsWith("board-cart-wheel-") }.forEach {
            (it.center.y - it.size.y / 2) shouldBe (home.y.toFloat() plusOrMinus .0001f)
        }
        parts.first { it.id == "board-cart-handle" }.center.z shouldBe (home.z.toFloat() - 4.45f plusOrMinus .0001f)
        CedarSawmillController.frame(view).poses.none { it.visible && it.partId.startsWith("timber-") &&
            CedarSawmillGeometry.parts.first { part -> part.id == it.partId }.motion.startsWith("timber-") } shouldBe true
    }

    test("only the currently available tree lever or output carries an actionable highlight") {
        fun view(phase: LumberPhase, running: Boolean = false) = LumberVisualView(
            LumberState(phase = phase, tree = tree, sawRunning = running), 0, emptySet(), 0, 0)
        LumberSceneGeometry.sample(view(LumberPhase.CHOPPING)).count { it.id.startsWith("tree-mark-") && it.action != null } shouldBe 2
        CedarSawmillController.highlightedParts(view(LumberPhase.SAWING)).isNotEmpty() shouldBe true
        CedarSawmillController.highlightedParts(view(LumberPhase.SAWING, true)) shouldBe emptySet()
        CedarSawmillController.highlightedParts(view(LumberPhase.COLLECTING_BOARDS)).isNotEmpty() shouldBe true
        LumberSceneGeometry.sample(view(LumberPhase.LOADING_CART)).none { it.id.startsWith("receiving-") } shouldBe true
        val halfPass = view(LumberPhase.SAWING, true).copy(sawElapsedTicks = ru.ruscrafting.farms.domain.lumber.LumberTiming.SAW_PASS_TICKS / 2)
        CedarSawmillController.frame(halfPass).poses shouldBe CedarSawmillAnimation.sample(12.5).poses
    }
})
