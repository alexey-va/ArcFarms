package ru.ruscrafting.farms.domain

/**
 * Stable resource names used by mine orders.  A resource can have more than
 * one physical block representation (for example COAL_ORE and
 * DEEPSLATE_COAL_ORE), while the order and its saved progress use this one
 * bucket.
 *
 * The fallback for an unknown legacy material deliberately remains the
 * upper-case material name.  This keeps old configurations readable while
 * all known ore variants use resource semantics.
 */
object MineResource {
    private val variantsByResource = linkedMapOf(
        "COAL" to setOf("COAL_ORE", "DEEPSLATE_COAL_ORE"),
        "IRON" to setOf("IRON_ORE", "DEEPSLATE_IRON_ORE"),
        "COPPER" to setOf("COPPER_ORE", "DEEPSLATE_COPPER_ORE"),
        "GOLD" to setOf("GOLD_ORE", "DEEPSLATE_GOLD_ORE", "NETHER_GOLD_ORE"),
        "REDSTONE" to setOf("REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE"),
        "QUARTZ" to setOf("NETHER_QUARTZ_ORE"),
        "DIAMOND" to setOf("DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE"),
        // These aliases keep the material list accepted by older server
        // profiles usable if an operator chose these less common resources.
        "LAPIS" to setOf("LAPIS_ORE", "DEEPSLATE_LAPIS_ORE"),
        "EMERALD" to setOf("EMERALD_ORE", "DEEPSLATE_EMERALD_ORE"),
        "ANCIENT_DEBRIS" to setOf("ANCIENT_DEBRIS"),
    )
    private val resourceByVariant = variantsByResource.flatMap { (resource, variants) ->
        variants.map { it to resource }
    }.toMap()

    val knownResources: Set<String> get() = variantsByResource.keys

    /** Converts a resource name or a physical material name to its bucket. */
    fun normalize(value: String): String {
        val name = value.trim().uppercase().removePrefix("MINECRAFT:")
        return resourceByVariant[name] ?: name
    }

    fun fromMaterial(material: String): String = normalize(material)

    /** Returns every physical block accepted for this resource bucket. */
    fun variants(resource: String): Set<String> {
        val normalized = normalize(resource)
        return variantsByResource[normalized] ?: setOf(normalized)
    }

    fun normalizeSet(values: Iterable<String>): Set<String> = values.mapTo(linkedSetOf(), ::normalize)

    fun normalizeRequirements(values: Map<String, Int>): Map<String, Int> = buildMap {
        values.forEach { (resource, amount) ->
            val normalized = normalize(resource)
            put(normalized, saturatedAdd(get(normalized) ?: 0, amount))
        }
    }

    /** Converts old exact-material progress into resource buckets. */
    fun normalizeProgress(values: Map<String, Int>): Map<String, Int> = buildMap {
        values.forEach { (resource, amount) ->
            if (amount <= 0) return@forEach
            val normalized = normalize(resource)
            val merged = saturatedAdd(get(normalized) ?: 0, amount).coerceAtMost(MAX_PROGRESS)
            put(normalized, merged)
        }
    }

    private fun saturatedAdd(left: Int, right: Int): Int =
        (left.toLong() + right.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private const val MAX_PROGRESS = 100_000

    fun displayName(resource: String): String = when (normalize(resource)) {
        "COAL" -> "Coal"
        "IRON" -> "Iron"
        "COPPER" -> "Copper"
        "GOLD" -> "Gold"
        "REDSTONE" -> "Redstone"
        "QUARTZ" -> "Quartz"
        "DIAMOND" -> "Diamond"
        "LAPIS" -> "Lapis"
        "EMERALD" -> "Emerald"
        "ANCIENT_DEBRIS" -> "Ancient debris"
        else -> normalize(resource).lowercase().replace('_', ' ')
    }
}
