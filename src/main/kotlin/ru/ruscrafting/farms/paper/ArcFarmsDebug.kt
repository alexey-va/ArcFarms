package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player

class ArcFarmsDebug(
    private val enabled: () -> Boolean,
    private val sink: (String) -> Unit,
) {
    private val plain = PlainTextComponentSerializer.plainText()

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        if (!enabled()) return
        val suffix = fields.joinToString(" ") { (key, value) -> "${token(key)}=${encoded(value)}" }
        sink("ARCFARMS_DEBUG event=${token(name)}${if (suffix.isEmpty()) "" else " $suffix"}")
    }

    fun message(
        surface: String,
        scope: String,
        key: String,
        player: Player,
        component: Component,
    ) = event(
        "message",
        "surface" to surface,
        "scope" to scope,
        "key" to key,
        "player" to player.name,
        "text" to plain.serialize(component),
    )

    private fun encoded(value: Any?): String {
        val raw = value?.toString().orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(MAX_VALUE_CHARS)
        if (raw.matches(SAFE_VALUE)) return raw.ifEmpty { "-" }
        return "\"${raw.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

    private fun token(value: String): String = value.lowercase().replace(TOKEN_INVALID, "_").take(MAX_TOKEN_CHARS)

    companion object {
        private val SAFE_VALUE = Regex("[A-Za-z0-9_.:/@+-]{1,240}")
        private val TOKEN_INVALID = Regex("[^a-z0-9_.-]")
        private const val MAX_VALUE_CHARS = 240
        private const val MAX_TOKEN_CHARS = 64
    }
}
