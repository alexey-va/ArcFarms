package ru.ruscrafting.farms.paper.mine.incident.scenario

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import com.google.gson.JsonParser
import ru.ruscrafting.farms.domain.MineIncidentType

class MineScenarioEnvironmentTest : FunSpec({
    test("selected room scenarios have bounded environment mappings") {
        MineScenarioEnvironment.mapping.keys shouldContainExactlyInAnyOrder listOf(
            MineIncidentType.CAVE_IN, MineIncidentType.FLOODING, MineIncidentType.GAS_LEAK,
            MineIncidentType.POWER_FAILURE, MineIncidentType.FUNGAL_BLOOM, MineIncidentType.ROOT_INVASION,
            MineIncidentType.LAVA_BREACH, MineIncidentType.ANCIENT_DOOR, MineIncidentType.OLD_WAREHOUSE,
            MineIncidentType.DRILL_TRIAL,
        )
        MineScenarioEnvironment.mapping.values.toSet().size shouldBe 10
    }

    test("explicit environment shapes fit the real room metadata without route or objective pads") {
        val metadata = requireNotNull(javaClass.getResourceAsStream("/mine/events/room.metadata.json"))
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        metadata.getAsJsonArray("size").map { it.asInt } shouldBe listOf(17, 8, 25)
        val forbidden = buildSet {
            for (x in 7..9) for (z in 3..22) for (y in 2..3) add(Triple(x,y,z))
            metadata.getAsJsonArray("route").forEach { point ->
                val a = point.asJsonArray
                add(Triple(a[0].asInt, a[1].asInt + 1, a[2].asInt))
            }
            metadata.getAsJsonArray("objectivePads").forEach { pad ->
                val a = pad.asJsonObject.getAsJsonArray("center")
                add(Triple(a[0].asInt, a[1].asInt, a[2].asInt))
                add(Triple(a[0].asInt + if(a[0].asInt < 8) 1 else -1, a[1].asInt, a[2].asInt))
            }
        }
        listOf("alcove", "cave_in", "flood", "lava", "containment").forEach { mode ->
            MineScenarioEnvironment.localShape(mode).intersect(forbidden) shouldBe emptySet()
        }
        MineScenarioEnvironment.localShape("ancient").intersect(forbidden) shouldBe emptySet()
    }
})
