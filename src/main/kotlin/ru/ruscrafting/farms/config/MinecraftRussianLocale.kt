package ru.ruscrafting.farms.config

import com.google.gson.JsonParser
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer
import net.kyori.adventure.translation.TranslationStore
import java.text.MessageFormat
import java.util.Locale

/** Resolves ArcFarms' vanilla labels locally, without registering a global translation source. */
internal object MinecraftRussianLocale {
    private val russian = Locale.forLanguageTag("ru-RU")
    private val argument = Regex("%(?:(\\d+)\\$)?s|%%")
    private val renderer = TranslatableComponentRenderer.usingTranslationSource(
        TranslationStore.messageFormat(Key.key("arcfarms:minecraft-1_21_11")).apply {
            defaultLocale(russian)
            requireNotNull(MinecraftRussianLocale::class.java.getResourceAsStream("/lang/minecraft/ru_ru.json")) {
                "Bundled Minecraft 1.21.11 Russian labels are missing"
            }.bufferedReader(Charsets.UTF_8).use { reader ->
                JsonParser.parseReader(reader).asJsonObject.entrySet().forEach { (key, value) ->
                    var nextArgument = 0
                    val pattern = argument.replace(value.asString.replace("'", "''")) { match ->
                        if (match.value == "%%") "%"
                        else "{${match.groupValues[1].toIntOrNull()?.minus(1) ?: nextArgument++}}"
                    }
                    register(key, russian, MessageFormat(pattern, russian))
                }
            }
        },
    )

    fun render(component: Component): Component = renderer.render(component, russian)
}
