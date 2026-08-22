package ru.ruscrafting.farms.domain

import java.util.UUID

data class FarmMusicTransition(
    val stopSound: String? = null,
    val playSound: String? = null,
)

class FarmMusicLoop {
    private data class Session(val sound: String, val replayAt: Long)

    private val sessions = mutableMapOf<UUID, Session>()

    fun sync(
        playerId: UUID,
        desiredSound: String?,
        now: Long,
        durationMillis: Long,
    ): FarmMusicTransition {
        require(now >= 0) { "Music clock must not be negative" }
        require(durationMillis > 0) { "Music duration must be positive" }
        val current = sessions[playerId]
        if (desiredSound == null) {
            sessions.remove(playerId)
            return FarmMusicTransition(stopSound = current?.sound)
        }
        require(desiredSound.isNotBlank()) { "Music sound must not be blank" }
        if (current != null && current.sound == desiredSound && now < current.replayAt) return FarmMusicTransition()
        sessions[playerId] = Session(desiredSound, saturatingAdd(now, durationMillis))
        return FarmMusicTransition(stopSound = current?.sound, playSound = desiredSound)
    }

    fun remove(playerId: UUID): FarmMusicTransition {
        val current = sessions.remove(playerId) ?: return FarmMusicTransition()
        return FarmMusicTransition(stopSound = current.sound)
    }

    fun clear(): Map<UUID, String> = sessions.mapValues { it.value.sound }.also { sessions.clear() }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
