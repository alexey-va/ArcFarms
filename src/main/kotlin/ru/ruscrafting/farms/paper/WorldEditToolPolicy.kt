package ru.ruscrafting.farms.paper

internal object WorldEditToolPolicy {
    fun ownsInteraction(
        heldItemId: String,
        selectionWandId: String?,
        navigationWandId: String?,
        hasBoundTool: Boolean,
        superPickaxeActive: Boolean,
    ): Boolean {
        val held = normalize(heldItemId)
        return hasBoundTool || superPickaxeActive ||
            held == selectionWandId?.let(::normalize) || held == navigationWandId?.let(::normalize)
    }

    private fun normalize(id: String): String {
        val normalized = id.trim().lowercase()
        return if (':' in normalized) normalized else "minecraft:$normalized"
    }
}
