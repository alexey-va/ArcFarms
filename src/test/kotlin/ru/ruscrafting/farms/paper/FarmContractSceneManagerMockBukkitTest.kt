package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.TextDisplay
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.FarmItemDisplayTransform
import ru.ruscrafting.farms.domain.FarmCustomerType
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmTextDisplays

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
            MockBukkitFarmTextDisplays,
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
            customerLabel = net.kyori.adventure.text.Component.text("Пекарь\nЗапасы пекарни\nПКМ — узнать заказ"),
        )

        manager.ensure(base)
        val customerLabel = world.entities.single {
            manager.metadata(it)?.role == FarmContractSceneRole.CUSTOMER_LABEL
        } as TextDisplay
        PlainTextComponentSerializer.plainText().serialize(requireNotNull(customerLabel.text())) shouldBe
            "Пекарь\nЗапасы пекарни\nПКМ — узнать заказ"
        world.entities.filter(manager::owns).mapNotNull(manager::metadata).map { it.role }
            .shouldContainExactlyInAnyOrder(
                FarmContractSceneRole.CUSTOMER,
                FarmContractSceneRole.CUSTOMER_LABEL,
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
        remaining.mapNotNull(manager::metadata).map { it.role }.shouldContainExactlyInAnyOrder(
            FarmContractSceneRole.CUSTOMER,
            FarmContractSceneRole.CUSTOMER_LABEL,
        )

        manager.ensure(base)
        world.entities.filter(manager::owns).size shouldBe 6
    }

    test("enterprise badge is one reconstructible display and disappears at stage zero") {
        val world = paper.server.addSimpleWorld("sp12")
        world.getChunkAt(0, 0).load()
        val manager = FarmContractSceneManager(paper.createSimplePlugin("FarmContractBadgeTest"), ArcFarmsDebug({ false }) {}, MockBukkitFarmTextDisplays)
        val base = FarmContractSceneSpec(
            zoneId = "communal_farm", sequence = 1, customerType = FarmCustomerType.BAKER,
            customerLocation = Location(world, 2.5, 65.0, 2.5), cartLocation = Location(world, 5.5, 65.0, 2.5),
            cartItem = ItemStack(Material.MINECART), cartDisplayTransform = FarmItemDisplayTransform.GROUND, cartScale = 2f,
            loadItem = ItemStack(Material.HAY_BLOCK), loadCount = 0, loadYOffset = 0.4, loadScale = 1f, viewRange = 1f,
            customerLabel = net.kyori.adventure.text.Component.text("Customer"), enterpriseBadgeItem = ItemStack(Material.WHEAT),
        )
        manager.ensure(base)
        world.entities.count { manager.metadata(it)?.role == FarmContractSceneRole.ENTERPRISE_BADGE } shouldBe 1
        world.entities.count { it is ItemDisplay && manager.metadata(it)?.role == FarmContractSceneRole.ENTERPRISE_BADGE } shouldBe 1
        manager.ensure(base)
        world.entities.count { manager.metadata(it)?.role == FarmContractSceneRole.ENTERPRISE_BADGE } shouldBe 1
        manager.ensure(base.copy(enterpriseBadgeItem = null))
        world.entities.count { manager.metadata(it)?.role == FarmContractSceneRole.ENTERPRISE_BADGE } shouldBe 0
        manager.ensure(base.copy(hiddenRoles = setOf(FarmContractSceneRole.CUSTOMER)))
        world.entities.count { manager.metadata(it)?.role == FarmContractSceneRole.ENTERPRISE_BADGE } shouldBe 0
        manager.ensure(base)
        manager.cleanupLoaded("reload")
        world.entities.count(manager::owns) shouldBe 0
    }
})
