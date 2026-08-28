package ru.ruscrafting.farms.paper.farm.care.mole

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Rabbit
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.paper.fixtures.FarmMoleScenarioFixture
import ru.ruscrafting.farms.paper.fixtures.requiredMockBukkitScenario

class FarmMoleLifecycleMockBukkitIntegrationTest : FunSpec({
    test("three workers explore independent burrows and exact block recovery closes the event") {
        requiredMockBukkitScenario { FarmMoleScenarioFixture.open().use { fixture ->
            val scenes = fixture.prepareAndBuild().sortedBy(FarmMoleBurrowScene::burrowId)
            val originals = scenes.flatMap(FarmMoleBurrowScene::records).associate { record ->
                Triple(record.x, record.y, record.z) to record.originalData
            }
            val workers = listOf(
                fixture.paper.addPlayer("MoleScoutOne"),
                fixture.paper.addPlayer("MoleScoutTwo"),
                fixture.paper.addPlayer("MoleScoutThree"),
            )

            scenes.map(FarmMoleBurrowScene::burrowId).shouldContainExactlyInAnyOrder(0, 1, 2)
            scenes.map { it.surface.blockX }.toSet() shouldHaveSize 3
            originals.size shouldBeGreaterThanOrEqual 1_000

            val moles = fixture.world.entities.filterIsInstance<Rabbit>().filter(fixture.controller::owns)
            moles.size shouldBeGreaterThanOrEqual scenes.size
            moles.all { it.health == 1.0 } shouldBe true
            moles.mapNotNull(Rabbit::customName).map(PlainTextComponentSerializer.plainText()::serialize).toSet() shouldBe
                setOf("Крот")

            workers.zip(scenes).forEach { (worker, scene) ->
                fixture.enter(worker, scene)
                worker.location.blockY shouldBe scene.start.blockY
            }

            workers.zip(scenes).forEachIndexed { index, (worker, scene) ->
                fixture.finish(worker, scene)
                fixture.runtime.state.careTargets.count { it.complete } shouldBe index + 1
                fixture.runtime.state.phase shouldBe if (index == scenes.lastIndex) FarmPhase.HARVESTING else FarmPhase.CARE
            }

            fixture.runtime.state.contributors.keys.shouldContainExactlyInAnyOrder(workers.map { it.uniqueId })
            fixture.runtime.state.contributors.values.toList().shouldContainExactlyInAnyOrder(1, 1, 1)
            fixture.transitions.count { it.result.accepted } shouldBe 3

            fixture.restoreWorld()
            workers.zip(scenes).forEach { (worker, scene) ->
                worker.location.distanceSquared(scene.surface) shouldBe 0.0
            }
            originals.forEach { (position, blockData) ->
                fixture.currentBlockData(position) shouldBe blockData
            }
            fixture.world.entities.none(fixture.controller::owns) shouldBe true
        } }
    }

    test("durable burrow return survives a controller restart and is acknowledged exactly once") {
        requiredMockBukkitScenario { FarmMoleScenarioFixture.open().use { fixture ->
            val scene = fixture.prepareAndBuild().first { it.burrowId == 0 }
            val worker = fixture.paper.addPlayer("RestartedMoleScout")

            fixture.enter(worker, scene)
            fixture.pendingReturn(worker) shouldBe true
            worker.location.blockY shouldBe scene.start.blockY

            fixture.controller.releasePlayer(worker, "player_quit")
            fixture.pendingReturn(worker) shouldBe true
            fixture.restartController().recoverPlayer(worker)

            worker.location.distanceSquared(scene.surface) shouldBe 0.0
            fixture.pendingReturn(worker) shouldBe false

            fixture.controller.recoverPlayer(worker)
            worker.location.distanceSquared(scene.surface) shouldBe 0.0
            fixture.pendingReturn(worker) shouldBe false
            fixture.restoreWorld()
        } }
    }
})
