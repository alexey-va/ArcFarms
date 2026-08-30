package ru.ruscrafting.farms.domain.lumber

enum class LumberSawSide {
    LEFT,
    RIGHT;

    fun opposite(): LumberSawSide = if (this == LEFT) RIGHT else LEFT
}

data class LumberSawSequenceState(
    val expected: LumberSawSide = LumberSawSide.LEFT,
    val lastAcceptedAt: Long = 0L,
)

data class LumberSawSequenceResult(
    val state: LumberSawSequenceState,
    val accepted: Boolean,
)

object LumberSawSequence {
    fun use(
        current: LumberSawSequenceState,
        side: LumberSawSide,
        now: Long,
        debounceMillis: Long = 350L,
    ): LumberSawSequenceResult {
        require(now >= 0L)
        require(debounceMillis in 0L..5_000L)
        if (side != current.expected) return LumberSawSequenceResult(current, false)
        if (current.lastAcceptedAt > 0L && now - current.lastAcceptedAt < debounceMillis) {
            return LumberSawSequenceResult(current, false)
        }
        return LumberSawSequenceResult(
            current.copy(expected = side.opposite(), lastAcceptedAt = now),
            true,
        )
    }
}
