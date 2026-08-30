package ru.ruscrafting.farms.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.LumberIncidentType
import java.nio.file.Files
import java.nio.file.Path

class LumberConfigTest : FunSpec({
    test("v2 lumber orders parse bounded phases and distinct incidents") {
        val project = Path.of(requireNotNull(System.getProperty("arcfarms.projectDir")))
        val root = Files.createTempDirectory("arcfarms-lumber-config")
        val source = Files.readString(project.resolve("src/main/resources/config.yml"))
        val v2 = source.replace(
            "    felling-quota: 16\n",
            "    engine-version: 2\n" +
                "    target-multiplier: 2\n" +
                "    incident-count-min: 3\n" +
                "    incident-count-max: 4\n" +
                "    recovery-seconds: 90\n" +
                "    felling-quota: 16\n" +
                "    orders:\n" +
                "      oak_contract:\n" +
                "        species: [OAK, SPRUCE]\n" +
                "        phases:\n" +
                "          felling-required: 12\n" +
                "          skidding-required: 5\n" +
                "          sawing-required: 8\n" +
                "          stacking-required: 4\n" +
                "        incidents: [WINDTHROW, BARK_BEETLES, SAW_JAM, CONVEYOR_BREAKDOWN]\n",
        )
        Files.writeString(root.resolve("config.yml"), v2)

        val lumber = ArcFarmsConfig.inspect(root).lumbermills.single()

        lumber.engineVersion shouldBe 2
        lumber.targetMultiplier shouldBe 2
        lumber.recoverySeconds shouldBe 90
        lumber.orders.single().let { order ->
            order.id shouldBe "oak_contract"
            order.species shouldContainExactly listOf("OAK", "SPRUCE")
            order.fellingRequired shouldBe 12
            order.skiddingRequired shouldBe 5
            order.sawingRequired shouldBe 8
            order.stackingRequired shouldBe 4
            order.incidentTypes shouldContainExactly listOf(
                LumberIncidentType.WINDTHROW,
                LumberIncidentType.BARK_BEETLES,
                LumberIncidentType.SAW_JAM,
                LumberIncidentType.CONVEYOR_BREAKDOWN,
            )
        }
    }
})
