package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryLine

/** Pure contact checks for the authored experiment fixture poses. */
class MineFactoryExperimentLayoutTest : FunSpec({
    test("modern experiment fixtures use canonical anchors and replacement ids") {
        val anchorIds = MineFactoryLine.machines.keys + MineFactoryLine.stations.keys
        val fixtures = MineFactoryExperimentLayout.staticFixtures()

        fixtures.map { it.id }.distinct().size shouldBe fixtures.size
        fixtures.forEach { fixture ->
            anchorIds.contains(fixture.anchor) shouldBe true
            (fixture.scale > 0f) shouldBe true
            MineDisplayBlueprints.model(fixture.model).isNotEmpty() shouldBe true
        }

        // These ids deliberately replace the permanent furnishing in the
        // experiment scope, so the old hopper/rack cannot render underneath.
        MineFactoryExperimentLayout.routeBin.id shouldBe "crushed_output"
        MineFactoryExperimentLayout.routeBin.model shouldBe "charge_hopper"
        MineFactoryExperimentLayout.craneLanding.id shouldBe "crane_load"
        MineFactoryExperimentLayout.craneLanding.model shouldBe "factory_crane_landing"
    }

    test("fixture contacts stay on the authored machine surfaces") {
        val rock = bounds(MineFactoryExperimentLayout.rockJam)
        // Crusher anchor is (-22,5,-6); rolls are around world y=8.15 and
        // their front axial end is near z=-4.55.
        (rock.centerY in 8.0f..8.3f) shouldBe true
        (rock.maxZ > -4.65f && rock.minZ < -4.35f) shouldBe true

        val gate = bounds(MineFactoryExperimentLayout.routeGate)
        (gate.minY >= 6.20f && gate.minY < 6.30f) shouldBe true

        val bin = bounds(MineFactoryExperimentLayout.routeBin)
        (bin.minY <= 5.05f) shouldBe true
        (horizontalDistance(gate, bin) < 4.0f) shouldBe true

        val socket = bounds(MineFactoryExperimentLayout.mouldSocket)
        (socket.minY >= 6.49f && socket.minY < 6.55f) shouldBe true
        // Assembly-bench front is around world z=-3.6; the socket occupies
        // the top/front apron without reaching the standing moulds.
        (socket.maxZ < -3.95f) shouldBe true

        val moulds = (0..2).map { bounds(MineFactoryExperimentLayout.mould(it)) }
        moulds.all { it.minY >= 4.99f && it.minZ > -3.60f } shouldBe true
        moulds.zipWithNext().all { (left, right) -> left.maxX < right.minX } shouldBe true
        moulds.all { it.minZ > socket.maxZ } shouldBe true

        val bearing = bounds(MineFactoryExperimentLayout.hotBearing)
        (bearing.minY > 5.95f && bearing.maxY < 6.45f) shouldBe true
        (bearing.minZ < -4.55f && bearing.maxZ > -4.45f) shouldBe true

        val landing = bounds(MineFactoryExperimentLayout.craneLanding)
        // Its upstream overhang has its own floor legs; the pad is at 6.4.
        (landing.minY in 4.99f..5.05f && landing.maxY < 6.75f) shouldBe true
    }

    test("floor mould stands and the supported output hopper do not overlap the press") {
        val moulds = (0..2).map { bounds(MineFactoryExperimentLayout.mould(it)) }
        val socket = bounds(MineFactoryExperimentLayout.mouldSocket)
        moulds.none { overlaps(it, socket) } shouldBe true

        // routeBin uses the existing supported charge hopper at crushed_output;
        // a second off-deck factory_route_bin is intentionally absent.
        MineFactoryExperimentLayout.staticFixtures().count { it.model == "factory_route_bin" } shouldBe 0
        MineFactoryExperimentLayout.staticFixtures().count { it.id == "crushed_output" } shouldBe 0
    }
})

private data class Bounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val minZ: Float,
    val maxZ: Float,
) {
    val centerY: Float get() = (minY + maxY) / 2f
}

private fun bounds(fixture: MineFactoryExperimentLayout.Fixture): Bounds {
    val anchor = MineFactoryLine.machines[fixture.anchor]
        ?: MineFactoryLine.stations.getValue(fixture.anchor)
    val yaw = Quaternionf().rotateY(Math.toRadians(fixture.yaw.toDouble()).toFloat())
    val origin = Vector3f(
        anchor.x.toFloat() + fixture.offset.x.toFloat(),
        anchor.y.toFloat() + fixture.offset.y.toFloat(),
        anchor.z.toFloat() + fixture.offset.z.toFloat(),
    )
    val points = MineDisplayBlueprints.model(fixture.model).flatMap { part ->
        val localRotation = MineDisplayBlueprints.rotation(part, 0f)
        val localCenter = MineDisplayBlueprints.center(part, 0f)
        buildList {
            for (sx in listOf(-.5f, .5f)) for (sy in listOf(-.5f, .5f)) for (sz in listOf(-.5f, .5f)) {
                val local = localRotation.transform(Vector3f(
                    part.size.x * sx,
                    part.size.y * sy,
                    part.size.z * sz,
                )).add(localCenter).mul(fixture.scale)
                add(yaw.transform(local).add(origin))
            }
        }
    }
    return Bounds(
        minX = points.minOf { it.x }, maxX = points.maxOf { it.x },
        minY = points.minOf { it.y }, maxY = points.maxOf { it.y },
        minZ = points.minOf { it.z }, maxZ = points.maxOf { it.z },
    )
}

private fun overlaps(first: Bounds, second: Bounds): Boolean =
    first.minX < second.maxX && second.minX < first.maxX &&
        first.minY < second.maxY && second.minY < first.maxY &&
        first.minZ < second.maxZ && second.minZ < first.maxZ

private fun horizontalDistance(first: Bounds, second: Bounds): Float {
    val dx = when {
        first.maxX < second.minX -> second.minX - first.maxX
        second.maxX < first.minX -> first.minX - second.maxX
        else -> 0f
    }
    val dz = when {
        first.maxZ < second.minZ -> second.minZ - first.maxZ
        second.maxZ < first.minZ -> first.minZ - second.maxZ
        else -> 0f
    }
    return kotlin.math.sqrt(dx * dx + dz * dz)
}
