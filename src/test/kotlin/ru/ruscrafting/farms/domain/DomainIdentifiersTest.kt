package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DomainIdentifiersTest : FunSpec({
    test("allocation-free domain identifier checks preserve the persisted format contract") {
        listOf("sp11", "world_nether", "farm-zone.2").forEach { DomainIdentifiers.isWorld(it) shouldBe true }
        listOf("", "мир", "world/one", "a".repeat(129)).forEach { DomainIdentifiers.isWorld(it) shouldBe false }

        listOf("WHEAT", "SWEET_BERRY_BUSH", "A1").forEach { DomainIdentifiers.isContent(it) shouldBe true }
        listOf("A", "wheat", "MELON-STEM", "A".repeat(65)).forEach { DomainIdentifiers.isContent(it) shouldBe false }

        listOf("miners_rations", "order-2").forEach { DomainIdentifiers.isOrder(it) shouldBe true }
        listOf("", "Order", "order.2", "a".repeat(49)).forEach { DomainIdentifiers.isOrder(it) shouldBe false }
    }
})
