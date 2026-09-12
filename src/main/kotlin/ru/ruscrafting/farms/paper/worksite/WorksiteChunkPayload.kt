package ru.ruscrafting.farms.paper.worksite

import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

/** Shared chunk storage for farm ledgers and mine topology; payload codecs remain domain-specific. */
internal object WorksiteChunkPayload {
    fun read(container: PersistentDataContainer, key: NamespacedKey): ByteArray? = when {
        container.has(key, PersistentDataType.BYTE_ARRAY) -> container.get(key, PersistentDataType.BYTE_ARRAY)
        container.has(key, PersistentDataType.STRING) -> container.get(key, PersistentDataType.STRING)?.toByteArray(Charsets.UTF_8)
        else -> null
    }

    fun write(container: PersistentDataContainer, key: NamespacedKey, bytes: ByteArray) {
        // NBT strings use writeUTF with a 65,535-byte limit; byte arrays do not.
        container.set(key, PersistentDataType.BYTE_ARRAY, bytes)
    }
}
