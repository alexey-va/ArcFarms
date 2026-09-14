package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class SidebarLineWrapperTest : FunSpec({
    test("long mine guidance wraps without losing text or effective color") {
        val row = Component.text("Разберите камни завала ", NamedTextColor.GRAY)
            .append(Component.text("обычной киркой", NamedTextColor.GOLD))

        val wrapped = SidebarLineWrapper.wrap(listOf(row), 28)

        wrapped shouldHaveSize 2
        wrapped.joinToString(" ") { PLAIN.serialize(it).trim() } shouldBe "Разберите камни завала обычной киркой"
        wrapped.last().children().last().color() shouldBe NamedTextColor.GOLD
    }

    test("short rows retain their original component identity") {
        val row = Component.text("Короткая строка", NamedTextColor.AQUA)
        SidebarLineWrapper.wrap(listOf(row), 28).single() shouldBe row
    }
}) {
    companion object {
        private val PLAIN = PlainTextComponentSerializer.plainText()
    }
}
