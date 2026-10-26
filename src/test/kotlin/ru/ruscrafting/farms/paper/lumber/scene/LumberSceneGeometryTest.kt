package ru.ruscrafting.farms.paper.lumber.scene

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
})
