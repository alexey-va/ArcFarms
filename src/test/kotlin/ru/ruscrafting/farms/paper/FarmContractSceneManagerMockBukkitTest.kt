package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.FarmItemDisplayTransform
import ru.ruscrafting.farms.domain.FarmCustomerType

class FarmContractSceneManagerMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("food delivery hides the stationary cart and restores it afterwards") {
        val world = paper.server.addSimpleWorld("sp11")
        world.getChunkAt(0, 0).load()
        val manager = FarmContractSceneManager(
            paper.createSimplePlugin("FarmContractSceneTest"),
            ArcFarmsDebug({ false }) {},
        )
        val base = FarmContractSceneSpec(
            zoneId = "communal_farm",
            sequence = 9,
            customerType = FarmCustomerType.BAKER,
            customerLocation = Location(world, 2.5, 65.0, 2.5),
            cartLocation = Location(world, 5.5, 65.0, 2.5),
            cartItem = ItemStack(Material.MINECART),
            cartDisplayTransform = FarmItemDisplayTransform.GROUND,
            cartScale = 2.0f,
            loadItem = ItemStack(Material.HAY_BLOCK),
            loadCount = 2,
            loadYOffset = 0.4,
            loadScale = 1.0f,
            viewRange = 1.0f,
        )

        manager.ensure(base)
        world.entities.filter(manager::owns).mapNotNull(manager::metadata).map { it.role }
            .shouldContainExactlyInAnyOrder(
                FarmContractSceneRole.CUSTOMER,
                FarmContractSceneRole.CART,
                FarmContractSceneRole.CART_INTERACTION,
                FarmContractSceneRole.CART_LOAD,
                FarmContractSceneRole.CART_LOAD,
            )

        manager.ensure(
            base.copy(
                hiddenRoles = setOf(
                    FarmContractSceneRole.CART,
                    FarmContractSceneRole.CART_INTERACTION,
                    FarmContractSceneRole.CART_LOAD,
                ),
            ),
        )
        val remaining = world.entities.filter(manager::owns)
        remaining.size shouldBe 1
        manager.metadata(remaining.single())?.role shouldBe FarmContractSceneRole.CUSTOMER

        manager.ensure(base)
        world.entities.filter(manager::owns).size shouldBe 5
    }
})
