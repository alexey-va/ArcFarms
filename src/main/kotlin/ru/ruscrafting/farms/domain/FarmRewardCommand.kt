package ru.ruscrafting.farms.domain

import java.util.UUID

/**
 * Trusted operator-authored console command with explicit dynamic slots.
 *
 * Literal command text is deliberately not allowlisted. Parsing only bounds
 * the record, rejects line injection and catches misspelled placeholders.
 * Write `%%` when a literal percent sign is required.
 */
class TrustedFarmCommandTemplate private constructor(
    val value: String,
    private val segments: List<Segment>,
) {
    fun resolve(context: FarmRewardCommandContext): ResolvedFarmRewardCommand {
        val output = buildString {
            segments.forEach { segment ->
                when (segment) {
                    is Segment.Literal -> append(segment.value)
                    is Segment.Placeholder -> append(context.value(segment.name))
                }
            }
        }
        return ResolvedFarmRewardCommand.of(output)
    }

    private sealed interface Segment {
        data class Literal(val value: String) : Segment
        data class Placeholder(val name: String) : Segment
    }

    companion object {
        private const val MAX_TEMPLATE_LENGTH = 512
        private val placeholders = setOf(
            "player",
            "uuid",
            "zone",
            "sequence",
            "contribution",
            "rank",
            "grant_id",
        )

        fun parse(raw: String): TrustedFarmCommandTemplate {
            val value = raw.trim().removePrefix("/")
            require(value.length in 1..MAX_TEMPLATE_LENGTH && '\n' !in value && '\r' !in value) {
                "Farm reward command template is empty, multiline, or too long"
            }

            val segments = mutableListOf<Segment>()
            val literal = StringBuilder()
            fun flushLiteral() {
                if (literal.isNotEmpty()) {
                    segments += Segment.Literal(literal.toString())
                    literal.setLength(0)
                }
            }

            var cursor = 0
            while (cursor < value.length) {
                if (value[cursor] != '%') {
                    literal.append(value[cursor++])
                    continue
                }
                if (cursor + 1 < value.length && value[cursor + 1] == '%') {
                    literal.append('%')
                    cursor += 2
                    continue
                }
                val end = value.indexOf('%', cursor + 1)
                require(end > cursor + 1) { "Farm reward command contains an incomplete placeholder" }
                val name = value.substring(cursor + 1, end)
                require(name in placeholders) { "Farm reward command uses unknown placeholder %$name%" }
                flushLiteral()
                segments += Segment.Placeholder(name)
                cursor = end + 1
            }
            flushLiteral()
            return TrustedFarmCommandTemplate(value, segments.toList())
        }
    }
}

/** Values accepted into command placeholders; each one remains one command token. */
data class FarmRewardCommandContext(
    val playerName: String,
    val playerId: UUID,
    val zoneId: String,
    val sequence: Long,
    val contribution: Int,
    val rank: Int,
    val grantId: String,
) {
    private val values = mapOf(
        "player" to safeToken("player", playerName),
        "uuid" to safeToken("uuid", playerId.toString()),
        "zone" to safeToken("zone", zoneId),
        "sequence" to safeToken("sequence", sequence.toString()),
        "contribution" to safeToken("contribution", contribution.toString()),
        "rank" to safeToken("rank", rank.toString()),
        "grant_id" to safeToken("grant_id", grantId),
    )

    init {
        require(sequence >= 0) { "Farm reward sequence is negative" }
        require(contribution >= 0) { "Farm reward contribution is negative" }
        require(rank > 0) { "Farm reward rank is not positive" }
    }

    internal fun value(name: String): String = requireNotNull(values[name])

    private companion object {
        private val SAFE_TOKEN = Regex("[A-Za-z0-9_.:@+-]{1,160}")

        fun safeToken(label: String, value: String): String = value.also {
            require(SAFE_TOKEN.matches(it)) { "Farm reward $label placeholder is not one safe command token" }
        }
    }
}

@JvmInline
value class ResolvedFarmRewardCommand private constructor(val value: String) {
    companion object {
        fun of(value: String): ResolvedFarmRewardCommand {
            require(value.length in 1..1_024 && '\n' !in value && '\r' !in value) {
                "Resolved farm reward command is empty, multiline, or too long"
            }
            return ResolvedFarmRewardCommand(value)
        }
    }
}
