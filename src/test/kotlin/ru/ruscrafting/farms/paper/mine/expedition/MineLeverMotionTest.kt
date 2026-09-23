package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.joml.Vector3f
import ru.ruscrafting.farms.paper.worksite.WorksiteDisplayGeometryValidator
import kotlin.math.PI
import kotlin.math.abs

class MineLeverMotionTest : FunSpec({
    test("control handles throw forward and down around their fixed pivot, then return to the rest detent") {
        val handles = listOf(
            MineDisplayBlueprints.model("assembly_bench").single {
                it.motion == "lever" && it.material == Material.RED_CONCRETE
            },
            MineDisplayBlueprints.model("descent_brake_lever").single {
                it.motion == "lever" && it.material == Material.RED_CONCRETE
            },
        )

        handles.forEach { handle ->
            val front = if (handle.pivot.z < 0f) -1f else 1f
            val rest = MineDisplayBlueprints.center(handle, 0f)
            val thrown = MineDisplayBlueprints.center(handle, PI.toFloat())
            val returned = MineDisplayBlueprints.center(handle, (2 * PI).toFloat())
            val upAtThrow = MineDisplayBlueprints.rotation(handle, PI.toFloat()).transform(Vector3f(0f, 1f, 0f))

            (abs(thrown.x - rest.x) < .0001f) shouldBe true
            (abs(thrown.y - handle.pivot.y) < .0001f) shouldBe true
            (front * (thrown.z - rest.z) > 0f) shouldBe true
            (abs(rest.distance(handle.pivot) - thrown.distance(handle.pivot)) < .0001f) shouldBe true
            (returned.distance(rest) < .0001f) shouldBe true
            (abs(upAtThrow.x) < .0001f) shouldBe true
            (abs(upAtThrow.y) < .0001f) shouldBe true
            (front * upAtThrow.z > .999f) shouldBe true

            val frontTravel = (0..16).map { step ->
                val phase = step * PI.toFloat() / 8f
                val center = MineDisplayBlueprints.center(handle, phase)
                (abs(center.x - rest.x) < .0001f) shouldBe true
                (center.y <= rest.y + .0001f) shouldBe true
                (abs(center.distance(handle.pivot) - rest.distance(handle.pivot)) < .0001f) shouldBe true
                front * (center.z - rest.z)
            }
            (frontTravel.take(9).zipWithNext().all { (before, after) -> after + .0001f >= before }) shouldBe true
            (frontTravel.drop(8).zipWithNext().all { (before, after) -> after <= before + .0001f }) shouldBe true
            (abs(frontTravel.first()) < .0001f) shouldBe true
            (abs(frontTravel.last()) < .0001f) shouldBe true
        }
    }

    test("horizontal route gate keeps its sideways sweep around the hinge") {
        val gate = MineFactoryExperimentModels.model("factory_route_gate").single { it.motion == "lever" }
        val rest = MineDisplayBlueprints.center(gate, 0f)
        val open = MineDisplayBlueprints.center(gate, PI.toFloat())
        val returned = MineDisplayBlueprints.center(gate, (2 * PI).toFloat())
        val armAtOpen = MineDisplayBlueprints.rotation(gate, PI.toFloat()).transform(Vector3f(1f, 0f, 0f))

        (abs(open.x - gate.pivot.x) < .0001f) shouldBe true
        (abs(open.y - rest.y) < .0001f) shouldBe true
        (open.z < rest.z) shouldBe true
        (abs(rest.distance(gate.pivot) - open.distance(gate.pivot)) < .0001f) shouldBe true
        (returned.distance(rest) < .0001f) shouldBe true
        (abs(armAtOpen.x) < .0001f) shouldBe true
        (abs(armAtOpen.y) < .0001f) shouldBe true
        (armAtOpen.z < -.999f) shouldBe true
    }

    test("descent brake handle clears its mounting plate at the throw detent") {
        val parts = MineDisplayBlueprints.model("descent_brake_lever")
        val plate = parts.single { it.material == Material.POLISHED_BLACKSTONE }
        val stem = parts.single { it.material == Material.IRON_BLOCK && it.motion == "lever" }
        val phase = PI.toFloat()
        val boxes = listOf(plate, stem).mapIndexed { index, part ->
            WorksiteDisplayGeometryValidator.Box(
                id = "$index:${part.material}",
                center = MineDisplayBlueprints.center(part, phase),
                size = part.size,
                rotation = MineDisplayBlueprints.rotation(part, phase),
            )
        }

        WorksiteDisplayGeometryValidator.conflicts(boxes) shouldBe emptyList()
    }
})
