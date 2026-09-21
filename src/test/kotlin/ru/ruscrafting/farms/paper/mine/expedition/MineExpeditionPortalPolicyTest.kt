package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.mine.expedition.ExpeditionBounds
import ru.ruscrafting.farms.domain.mine.expedition.ExpeditionPoint
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlan

class MineExpeditionPortalPolicyTest : FunSpec({
    test("co-located factory entry and exit render one return portal") {
        val point = ExpeditionPoint(0, 5, 24)

        MineExpeditionPortalPolicy.returnStationIds(plan(point, point)) shouldContainExactly listOf("entry")
    }

    test("legacy factory stations still expose only the entry return portal") {
        val entry = ExpeditionPoint(-5, 5, 24)
        val exit = ExpeditionPoint(5, 5, 24)

        MineExpeditionPortalPolicy.returnStationIds(plan(entry, exit)) shouldContainExactly listOf("entry")
    }

    test("factory arrival moves into the hall and faces its -Z aisle") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("factory-portal")
            val entry = Location(world, 0.5, 64.0, 24.5, 0f, 22f)

            val arrival = MineExpeditionPortalPolicy.arrival(MineExpeditionKind.DEAD_FACTORY, entry)

            arrival.x shouldBe 0.5
            arrival.y shouldBe 64.0
            arrival.z shouldBe 21.5
            arrival.yaw shouldBe 180f
            arrival.pitch shouldBe 0f
            entry.z shouldBe 24.5
            entry.yaw shouldBe 0f
            entry.pitch shouldBe 22f
        } finally {
            paper.close()
        }
    }
})

private fun plan(entry: ExpeditionPoint, exit: ExpeditionPoint) = MineExpeditionPlan(
    kind = MineExpeditionKind.DEAD_FACTORY,
    seed = 1L,
    bounds = ExpeditionBounds(ExpeditionPoint(-32, 0, -32), ExpeditionPoint(32, 20, 32)),
    blocks = emptyMap(),
    stations = mapOf("entry" to entry, "exit" to exit),
    routes = emptyMap(),
    walkingRoutes = emptyList(),
)
