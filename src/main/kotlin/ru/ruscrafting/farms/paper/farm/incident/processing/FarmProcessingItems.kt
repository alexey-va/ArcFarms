package ru.ruscrafting.farms.paper.farm.incident.processing

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.farms.config.FarmProcessingSettings
import ru.ruscrafting.farms.config.FarmProcessingVisualRole
import ru.ruscrafting.farms.config.FarmProcessingVisualSettings
import ru.ruscrafting.farms.paper.FarmRuntime

internal enum class ProcessingCargo { RAW, PRODUCT }

/** Resolves portable vanilla visuals and optional live ItemsAdder item models. */
internal object FarmProcessingItems {
    fun packageItem(runtime: FarmRuntime, cargo: ProcessingCargo): ItemStack {
        val visual = visual(runtime.settings.processing, packageRole(cargo))
        if (visual.itemModel != null || visual.customModelData > 0) return item(visual)
        val crop = runtime.state.processing?.crop ?: runtime.state.incidentCrop.orEmpty()
        val material = when (cargo) {
            ProcessingCargo.RAW -> RAW_CROP_MATERIALS[crop] ?: Material.WHEAT
            ProcessingCargo.PRODUCT -> PRODUCT_MATERIALS[crop] ?: Material.BREAD
        }
        return ItemStack(material)
    }

    fun item(visual: FarmProcessingVisualSettings): ItemStack {
        val stack = ItemStack(requireNotNull(Material.matchMaterial(visual.material)))
        stack.editMeta { meta ->
            if (visual.customModelData > 0) meta.setCustomModelData(visual.customModelData)
            visual.itemModel?.let { model -> meta.setItemModel(requireNotNull(NamespacedKey.fromString(model))) }
        }
        return stack
    }

    fun visual(settings: FarmProcessingSettings, role: FarmProcessingVisualRole): FarmProcessingVisualSettings =
        requireNotNull(settings.visuals[role])

    fun packageRole(cargo: ProcessingCargo): FarmProcessingVisualRole = when (cargo) {
        ProcessingCargo.RAW -> FarmProcessingVisualRole.RAW_PACKAGE
        ProcessingCargo.PRODUCT -> FarmProcessingVisualRole.PRODUCT_PACKAGE
    }

    private val RAW_CROP_MATERIALS = mapOf(
        "WHEAT" to Material.WHEAT,
        "CARROTS" to Material.CARROT,
        "POTATOES" to Material.POTATO,
        "BEETROOTS" to Material.BEETROOT,
        "MELON" to Material.MELON_SLICE,
        "PUMPKIN" to Material.PUMPKIN,
        "SWEET_BERRY_BUSH" to Material.SWEET_BERRIES,
    )
    private val PRODUCT_MATERIALS = mapOf(
        "WHEAT" to Material.BREAD,
        "CARROTS" to Material.GOLDEN_CARROT,
        "POTATOES" to Material.BAKED_POTATO,
        "BEETROOTS" to Material.BEETROOT_SOUP,
        "MELON" to Material.GLISTERING_MELON_SLICE,
        "PUMPKIN" to Material.PUMPKIN_PIE,
        "SWEET_BERRY_BUSH" to Material.COOKIE,
    )
}
