package ru.ruscrafting.farms.paper.lumber.scene

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.lumber.*
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillController
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class LumberIncidentGeometryTest : FunSpec({
    val world = "rc_atelier_cedar_sawmill"
    val tree = LumberTreeSnapshot(WorksitePosition(world, 139, 73, 202), emptyList(), emptyList())
    val baseline = LumberState(roundId = 2, phase = LumberPhase.SAWING, tree = tree, sawPasses = 1)
    val actor = UUID.fromString("00000000-0000-0000-0000-000000000001")
    fun view(state: LumberState, angle: Double = 0.0) = LumberVisualView(state, 0, emptySet(), 0, 0, angle)

    test("the capstan hand follows the physical signed angle and highlights only before it is claimed") {
        val ready = LumberIncidentEngine.start(baseline, LumberIncidentType.FEED_JAM)
        val readyParts = LumberIncidentGeometry.sample(view(ready))
        readyParts.filter { it.action != null }.map { it.id } shouldBe listOf("incident-crank-grip")
        readyParts.single { it.id == "incident-crank-base" }.let {
            (it.center.y - it.size.y / 2) shouldBe (73f plusOrMinus .0001f)
        }
        val held = LumberIncidentEngine.startCrank(ready, actor)
        val anchor = LumberSceneGeometry.incidentCrank()
        for (angle in listOf(0.0, PI / 2, PI, -PI / 2)) {
            val parts = LumberIncidentGeometry.sample(view(held, angle))
            parts.none { it.action != null } shouldBe true
            parts.none { it.itemModel != null } shouldBe true
            val grip = parts.single { it.id == "incident-crank-grip" }
            grip.center.x shouldBe ((anchor.x + cos(angle) * 1.23).toFloat() plusOrMinus .0001f)
            grip.center.z shouldBe ((anchor.z + sin(angle) * 1.23).toFloat() plusOrMinus .0001f)
        }
    }

    test("an incident retains the exact stopped workpiece and restores its next action when resolved") {
        val started = LumberIncidentEngine.start(baseline, LumberIncidentType.FEED_JAM)
        val held = LumberIncidentEngine.startCrank(started, actor)
        val generation = requireNotNull(held.incident).generation
        val firstTurn = LumberIncidentEngine.completeTurn(held, actor, generation)
        val finished = LumberIncidentEngine.completeTurn(firstTurn, actor, generation)
        val expected = CedarSawmillController.frame(view(baseline)).poses
        for (state in listOf(started, held, firstTurn, finished)) {
            CedarSawmillController.frame(view(state)).poses shouldBe expected
        }
        CedarSawmillController.highlightedParts(view(held)) shouldBe emptySet()
        CedarSawmillController.highlightedParts(view(finished)) shouldBe CedarSawmillController.highlightedParts(view(baseline))
        LumberIncidentGeometry.sample(view(finished)) shouldBe emptyList()
    }

    test("sawdust objectives stay on the floor and only the extinguished pile disappears") {
        val positions = LumberSceneGeometry.smolderTargets().map { WorksitePosition(it.world, it.x.toInt(), it.y.toInt(), it.z.toInt()) }
        val ready = LumberIncidentEngine.start(baseline.copy(phase = LumberPhase.COLLECTING_BOARDS, sawPasses = 2),
            LumberIncidentType.SAWDUST_SMOLDER, positions)
        fun piles(state: LumberState) = LumberIncidentGeometry.sample(view(state)).filter { it.action != null }
        piles(ready).map { it.action?.index }.toSet() shouldBe setOf(0, 1, 2)
        piles(ready).forEach { (it.center.y - it.size.y / 2) shouldBe (73f plusOrMinus .0001f) }
        val after = LumberIncidentEngine.extinguish(ready, "pile_1", actor, requireNotNull(ready.incident).generation)
        piles(after).map { it.action?.index }.toSet() shouldBe setOf(0, 2)
        LumberIncidentGeometry.sample(view(after)).none { it.id.startsWith("incident-sawdust-1-") } shouldBe true
        CedarSawmillController.highlightedParts(view(after)) shouldBe emptySet()
    }
})
