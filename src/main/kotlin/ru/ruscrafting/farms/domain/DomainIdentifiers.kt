package ru.ruscrafting.farms.domain

/** Allocation-free validation shared by high-frequency immutable domain values. */
internal object DomainIdentifiers {
    fun isWorld(value: String): Boolean = value.length in 1..128 && value.all { character ->
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "._-"
    }

    fun isContent(value: String): Boolean = value.length in 2..64 && value.all { character ->
        character in 'A'..'Z' || character in '0'..'9' || character == '_'
    }

    fun isOrder(value: String): Boolean = value.length in 1..48 && value.all { character ->
        character in 'a'..'z' || character in '0'..'9' || character == '_' || character == '-'
    }
}
