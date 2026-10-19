package ru.ruscrafting.farms.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.ruscrafting.farms.paper.MaterialRules
import java.nio.file.Files
import java.util.Locale

class ArcFarmsRussianLocaleTest : FunSpec({
    val plain = PlainTextComponentSerializer.plainText()

    for ((language, action) in mapOf("ru" to "Включить редактирование:", "en" to "Enable editing:")) {
        test("$language edit hint renders the exact suggested command on its own action row") {
            val root = russianLocaleRoot()
            Config(root, "config.yml").also {
                it.setString("locale.default", language)
                it.saveStrict()
            }
            val settings = ArcFarmsConfig.inspect(root)
            val locale = ArcFarmsLocale(root) { settings }
            val component = locale.render(MessageKey.ADMIN_EDIT_HINT)

            plain.serialize(component).lines().last() shouldBe "  $action /arcfarms admin edit"
            plain.serialize(component) shouldNotContain "<"
            val serialized = GsonComponentSerializer.gson().serialize(component)
            serialized shouldContain "suggest_command"
            serialized shouldContain "/arcfarms admin edit"
        }
    }

    test("English client receives Russian plugin text and dynamic crop labels") {
        val root = russianLocaleRoot()
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }
        val player = mockk<Player> { every { locale() } returns Locale.US }

        val rendered = locale.render(
            MessageKey.FARM_PLANTING_REQUIRED,
            player,
            mapOf("crop" to MaterialRules.cropComponent(Material.WHEAT)),
        )

        plain.serialize(rendered) shouldBe "Сначала засейте отмеченный участок: Пшеница"
        GsonComponentSerializer.gson().serialize(rendered) shouldNotContain "\"translate\""
        verify(exactly = 0) { player.locale() }
    }

    test("English custom catalog remains available without client selection") {
        val root = russianLocaleRoot()
        Config(root, "config.yml").also {
            it.setString("locale.default", "en")
            it.saveStrict()
        }
        val settings = ArcFarmsConfig.inspect(root)
        val locale = ArcFarmsLocale(root) { settings }
        val player = mockk<Player> { every { locale() } returns Locale.forLanguageTag("ru-RU") }

        plain.serialize(locale.render(MessageKey.RELOAD_OK, player)) shouldContain
            "ArcFarms configuration and locales reloaded."
        verify(exactly = 0) { player.locale() }
    }

    test("vanilla labels render nested children and hover text without changing styles or actions") {
        val action = ClickEvent.runCommand("/arcfarms")
        val component = Component.translatable("item.minecraft.wheat")
            .color(NamedTextColor.GOLD)
            .clickEvent(action)
            .hoverEvent(HoverEvent.showText(Component.translatable("gui.cancel")))
            .append(Component.text(" / "))
            .append(Component.translatable("block.minecraft.oak_log"))
            .append(Component.text(" / "))
            .append(Component.translatable("gui.back"))

        val rendered = MinecraftRussianLocale.render(component)

        plain.serialize(rendered) shouldBe "Пшеница / Дубовое бревно / Назад"
        rendered.color() shouldBe NamedTextColor.GOLD
        rendered.clickEvent() shouldBe action
        plain.serialize(rendered.hoverEvent()!!.value() as Component) shouldBe "Отмена"
        GsonComponentSerializer.gson().serialize(rendered) shouldNotContain "\"translate\""
    }

    test("Minecraft format arguments retain their component values and children") {
        val namedHead = Component.translatable("block.minecraft.player_head.named")
            .arguments(Component.text("Алексей", NamedTextColor.GREEN))
            .append(Component.text("!"))
        val editBox = Component.translatable("gui.narrate.editBox")
            .arguments(Component.translatable("gui.back"), Component.translatable("item.minecraft.iron_pickaxe"))

        plain.serialize(MinecraftRussianLocale.render(namedHead)) shouldBe "Голова Алексей!"
        plain.serialize(MinecraftRussianLocale.render(editBox)) shouldBe "поле ввода Назад: Железная кирка"
        GsonComponentSerializer.gson().serialize(MinecraftRussianLocale.render(namedHead)) shouldContain "green"
    }
})

private fun russianLocaleRoot() = Files.createTempDirectory("arcfarms-russian-locale").also { root ->
    Files.createDirectories(root.resolve("lang"))
    listOf("config.yml", "lang/ru.yml", "lang/en.yml").forEach { resource ->
        requireNotNull(ArcFarmsRussianLocaleTest::class.java.classLoader.getResourceAsStream(resource)).use { input ->
            Files.copy(input, root.resolve(resource))
        }
    }
}
