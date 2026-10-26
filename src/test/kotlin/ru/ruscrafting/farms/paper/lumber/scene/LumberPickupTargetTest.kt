package ru.ruscrafting.farms.paper.lumber.scene

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import org.joml.Vector3f
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.*
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.lumber.LumberAction
import ru.ruscrafting.farms.domain.lumber.LumberInput
import ru.ruscrafting.farms.domain.lumber.LumberPose

/** Exact target geometry and Core ray selection; native tracking/client packets are not simulated here. */
class LumberPickupTargetTest : FunSpec({
    test("qualified rays can grab either end and both sides near the ends of the whole rotated log") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("cedar")
            val player = paper.server.addPlayer()
            val key = SpatialSceneKey("lumber-pickup-test")
            lateinit var frame: SpatialControlFrame<Long, LumberInput>
            val owner = object : SpatialControlController<Long, LumberInput>() {
                override fun current(player: Player, scene: SpatialSceneKey) = frame
                override fun activate(player: Player, scene: SpatialSceneKey, context: Long, action: LumberInput) = Unit
                // This test exercises the runtime's hit planes and reach. Native world occlusion is separate.
                override fun requiresLineOfSight(player: Player, context: Long) = false
            }
            PaperSpatialControls(paper.createSimplePlugin("LumberPickup"), mockk<PaperPacketDisplays>(relaxed = true), owner).use { controls ->
                for (action in listOf(LumberAction.LOAD_CART, LumberAction.TAKE_CART)) for (yaw in listOf(0f, 47f, 180f)) {
                    val part = LumberSceneGeometry.pickupTarget(LumberPose(world.name, 140.0, 73.612, 200.0, yaw), action)
                    frame = SpatialControlFrame(1L, SceneAudience.Public(), SpatialSceneScope.World,
                        lumberControls(world, part), SpatialInputProfile.ENTITY_BOTH, SpatialReach.Fixed(3.0))
                    controls.apply(key, frame)
                    val half = LumberSceneGeometry.STOCK_LENGTH.toFloat() / 2
                    val faces = listOf(Vector3f(0f, 0f, half) to Vector3f(0f, 0f, 1f),
                        Vector3f(0f, 0f, -half) to Vector3f(0f, 0f, -1f)) +
                        listOf(-1f, 1f).flatMap { side -> listOf(-1f, 1f).map { end ->
                            Vector3f(side * .5f, 0f, end * (half - .1f)) to Vector3f(side, 0f, 0f)
                        } }
                    for ((point, normal) in faces) {
                        val target = Vector3f(point).rotate(part.rotation).add(part.center)
                        // The client needs a native entity under the cursor even with an empty hand.
                        // Six bounded proxies cover the whole model; server authority still uses its exact planes.
                        frame.controls.size shouldBe 6
                        frame.controls.all { requireNotNull(it.nativeTarget).width < 2.4f } shouldBe true
                        frame.controls.any { control ->
                            val proxy = requireNotNull(control.nativeTarget)
                            val at = proxy.location()
                            kotlin.math.abs(target.x - at.x) <= proxy.width / 2 + .001 &&
                                kotlin.math.abs(target.z - at.z) <= proxy.width / 2 + .001 &&
                                target.y >= at.y - .001 && target.y <= at.y + proxy.height + .001
                        } shouldBe true
                        val from = Vector3f(point).add(Vector3f(normal).mul(1.8f)).rotate(part.rotation).add(part.center)
                        val at = Location(world, from.x.toDouble(), 73.0, from.z.toDouble())
                        at.direction = Vector(target.x.toDouble() - at.x, target.y.toDouble() - (at.y + player.eyeHeight), target.z.toDouble() - at.z)
                        player.teleport(at)
                        controls.target(key, player)?.action shouldBe LumberInput(action)
                    }
                }
            }
        }
    }
})
