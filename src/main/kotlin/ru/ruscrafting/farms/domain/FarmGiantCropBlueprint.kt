package ru.ruscrafting.farms.domain

data class FarmGiantCropVoxel(
    val dx: Int,
    val dy: Int,
    val dz: Int,
    val material: String,
)

/** Small, deterministic block sculptures used by the giant-harvest incident. */
object FarmGiantCropBlueprint {
    private val supported = setOf(
        "WHEAT",
        "CARROTS",
        "POTATOES",
        "BEETROOTS",
        "SWEET_BERRY_BUSH",
        "PUMPKIN",
        "MELON",
    )

    fun supports(crop: String): Boolean = crop in supported

    fun voxels(crop: String): List<FarmGiantCropVoxel> {
        require(supports(crop)) { "Unsupported giant crop: $crop" }
        return when (crop) {
            "WHEAT" -> buildList {
                for (y in 0..3) for (x in -1..1) for (z in -1..1) {
                    add(FarmGiantCropVoxel(x, y, z, "HAY_BLOCK"))
                }
                add(FarmGiantCropVoxel(0, 4, 0, "OAK_FENCE"))
            }
            "PUMPKIN", "MELON" -> buildList {
                for (y in 0..3) for (x in -2..2) for (z in -2..2) {
                    if (kotlin.math.abs(x) == 2 && kotlin.math.abs(z) == 2) continue
                    add(FarmGiantCropVoxel(x, y, z, crop))
                }
                add(FarmGiantCropVoxel(0, 4, 0, "OAK_FENCE"))
            }
            "CARROTS" -> compactRoot("ORANGE_TERRACOTTA")
            "POTATOES" -> compactRoot("YELLOW_TERRACOTTA")
            "BEETROOTS" -> compactRoot("RED_TERRACOTTA")
            "SWEET_BERRY_BUSH" -> compactRoot("RED_WOOL")
            else -> error("Unsupported giant crop: $crop")
        }
    }

    private fun compactRoot(bodyMaterial: String): List<FarmGiantCropVoxel> = buildList {
        for (y in 0..3) for (x in -1..1) for (z in -1..1) {
            add(FarmGiantCropVoxel(x, y, z, bodyMaterial))
        }
        add(FarmGiantCropVoxel(0, 4, 0, "OAK_LEAVES"))
        add(FarmGiantCropVoxel(1, 4, 0, "OAK_LEAVES"))
        add(FarmGiantCropVoxel(-1, 4, 0, "OAK_LEAVES"))
        add(FarmGiantCropVoxel(0, 4, 1, "OAK_LEAVES"))
        add(FarmGiantCropVoxel(0, 4, -1, "OAK_LEAVES"))
    }
}
