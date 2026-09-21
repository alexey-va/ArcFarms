package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import kotlin.math.abs

class MineFactoryCartsTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var carts: MineFactoryCarts
    lateinit var visuals: FactoryCartVisualRecorder

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("factory_cart")
        visuals = FactoryCartVisualRecorder()
        carts = MineFactoryCarts(paper.createSimplePlugin("FactoryCartsTest"), visuals)
    }

    afterEach {
        try {
            carts.close()
        } finally {
            paper.close()
        }
    }

    test("spawns in front, pushes with movement and cleans its visual and tether") {
        val player = paper.server.addPlayer()
        player.teleport(Location(world, 0.5, 65.0, 0.5, 0f, 0f))
        val cart = carts.spawn("factory:cart", player, Material.COAL_BLOCK)
        val body = visuals.bodies.single()

        (cart.at.z > player.location.z) shouldBe true
        (abs(cart.at.z - player.location.z - 1.9) < .0001) shouldBe true
        (abs(cart.at.x - player.location.x) < .0001) shouldBe true
        (abs(abs(cart.yaw) - 180f) < .0001f) shouldBe true
        world.entities.count { carts.owns(it) } shouldBe 1

        val initialPhase = cart.phase
        player.teleport(Location(world, 0.5, 65.0, 0.7, 0f, 0f))
        carts.move(cart, player, 1_000L) shouldBe true
        (cart.at.z > 2.4) shouldBe true
        (cart.at.z - player.location.z > 1.8) shouldBe true
        (cart.phase < initialPhase) shouldBe true
        val forwardPhase = cart.phase
        body.lastPhase shouldBe cart.phase

        // Looking around alone does not swing a front cart around the worker.
        val beforeCameraTurn = cart.at.clone()
        player.teleport(player.location.clone().apply { yaw = 180f })
        carts.move(cart, player, 1_100L) shouldBe true
        (abs(cart.at.x - beforeCameraTurn.x) < .0001) shouldBe true
        (abs(cart.at.z - beforeCameraTurn.z) < .0001) shouldBe true

        // Walking backward keeps the cart in front of the worker and rolls the wheels back.
        val beforeBackward = cart.at.clone()
        player.teleport(Location(world, 0.5, 65.0, 0.5, 0f, 0f))
        carts.move(cart, player, 1_200L) shouldBe true
        (cart.at.z < beforeBackward.z) shouldBe true
        (cart.phase > forwardPhase) shouldBe true

        // A translated turn steers toward the new front point without a teleport jump.
        val beforeTurnYaw = cart.yaw
        player.teleport(Location(world, 0.7, 65.0, 0.5, 90f, 0f))
        carts.move(cart, player, 1_300L) shouldBe true
        (cart.at.x < beforeBackward.x) shouldBe true
        (abs(cart.yaw - beforeTurnYaw) <= 22.0001f) shouldBe true

        carts.remove(cart)
        body.removed shouldBe true
        world.entities.count { carts.owns(it) } shouldBe 0
    }

    test("uses the player's yaw when a vertical view has no horizontal direction") {
        val player = paper.server.addPlayer()
        player.teleport(Location(world, 0.5, 65.0, 0.5, 90f, -90f))
        val cart = carts.spawn("factory:vertical-view", player, Material.IRON_BLOCK)

        (cart.at.x < player.location.x) shouldBe true
        (abs(cart.at.x - player.location.x + 1.9) < .0001) shouldBe true
        (abs(cart.at.z - player.location.z) < .0001) shouldBe true
        carts.remove(cart)
    }

    test("raw iron charge selects the connected-line cart load") {
        val player = paper.server.addPlayer()
        player.teleport(Location(world, 0.5, 65.0, 0.5, 0f, 0f))
        val cart = carts.spawn("factory:charge", player, Material.RAW_IRON_BLOCK)

        visuals.bodies.single().parts.any { it.material == Material.RAW_IRON_BLOCK } shouldBe true
        carts.remove(cart)
    }
})

private class FactoryCartVisualRecorder : MineFactoryCartVisuals {
    class Body(val parts: List<MineDisplayBlueprints.Part>) : MineFactoryCartVisuals.Body {
        var removed = false
        var lastPhase = 0f

        override fun render(at: Location, yaw: Float, phase: Float) {
            check(!removed)
            lastPhase = phase
        }

        override fun remove() {
            removed = true
        }
    }

    val bodies = mutableListOf<Body>()

    override fun spawn(at: Location, parts: List<MineDisplayBlueprints.Part>) = Body(parts).also {
        bodies += it
    }

    override fun close() {
        bodies.all { it.removed } shouldBe true
    }
}
