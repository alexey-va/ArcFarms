package ru.ruscrafting.farms.config

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.redis.RedisModuleConfig
import java.nio.file.Path

data class NetworkSettings(
    val enabled: Boolean,
    val hubServer: String,
    val transferCommand: String,
    val allowedOrigins: Set<String>,
    val callsEnabled: Boolean,
    val completionsEnabled: Boolean,
    val workdayEnabled: Boolean,
    val nodeProbeEnabled: Boolean,
)

data class CuboidBounds(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
) {
    val volume: Long = (maxX - minX + 1L) * (maxY - minY + 1L) * (maxZ - minZ + 1L)

    init {
        require(minX <= maxX && minY <= maxY && minZ <= maxZ) { "Cuboid bounds are inverted" }
        require(volume <= 20_000_000L) { "Cuboid bounds exceed 20,000,000 blocks" }
    }

    fun contains(x: Int, y: Int, z: Int): Boolean = x in minX..maxX && y in minY..maxY && z in minZ..maxZ
}

data class ZoneReference(
    val world: String,
    val region: String?,
    val bounds: CuboidBounds?,
) {
    init {
        require(world.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid world name: $world" }
        require((region != null) xor (bounds != null)) { "Zone $world must define exactly one region or bounds" }
        region?.let { require(it.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid region name: $it" } }
    }
}

data class FarmZoneSettings(
    val id: String,
    val reference: ZoneReference,
    val permission: String,
    val incidentTriggerPercent: Int,
    val incidentQuota: Int,
    val goldenWindowSeconds: Int,
    val crops: Set<String>,
    val orders: List<FarmOrderSettings>,
)

data class FarmOrderSettings(
    val id: String,
    val required: Map<String, Int>,
)

data class LumberZoneSettings(
    val id: String,
    val reference: ZoneReference,
    val station: ZoneReference,
    val permission: String,
    val fellingQuota: Int,
    val processingQuota: Int,
    val processingPerUse: Int,
    val species: List<String>,
    val stationMaterials: Set<String>,
)

data class MineZoneSettings(
    val id: String,
    val priority: Int,
    val reference: ZoneReference,
    val permission: String,
    val cartQuota: Int,
    val hazardTrigger: Int,
    val supportsRequired: Int,
    val restoreSeconds: Int,
    val temporaryMaterial: String,
    val baseMaterial: String,
    val materialWeights: LinkedHashMap<String, Int>,
)

class ArcFarmsConfig private constructor(
    val enabled: Boolean,
    val serverId: String,
    val network: NetworkSettings,
    val defaultLocale: String,
    val useClientLocale: Boolean,
    val bossbars: Boolean,
    val particles: Boolean,
    val sounds: Boolean,
    val saveSeconds: Int,
    val completedCooldownSeconds: Int,
    val navigation: Map<String, String>,
    val farms: List<FarmZoneSettings>,
    val lumbermills: List<LumberZoneSettings>,
    val mines: List<MineZoneSettings>,
) {
    companion object {
        fun load(dataRoot: Path): ArcFarmsConfig = parse(ConfigManager.of(dataRoot, "config.yml"))

        fun inspect(dataRoot: Path): ArcFarmsConfig = parse(Config(dataRoot, "config.yml"))

        private fun parse(config: Config): ArcFarmsConfig {
            val serverId = serverId(config.string("server-id", "spawn"), "server-id")
            val defaultLocale = config.string("locale.default", "ru").lowercase()
            require(defaultLocale in setOf("ru", "en")) { "locale.default must be ru or en" }
            val farms = config.keys("farm-zones").sorted().mapNotNull { id ->
                val section = config.section("farm-zones.$id")
                if (!section.boolean("enabled", true)) return@mapNotNull null
                validateId(id, "farm zone")
                val crops = section.stringList("crops").map(::materialName).toSet()
                require(crops.isNotEmpty()) { "Farm zone $id has no crops" }
                val orders = section.keys("orders").sorted().map { orderId ->
                    validateId(orderId, "farm order")
                    val required = parseWeightedList(section.stringList("orders.$orderId"), "farm order $orderId")
                    require(required.keys.all(crops::contains)) { "Farm order $orderId contains a crop outside farm-zones.$id.crops" }
                    FarmOrderSettings(orderId, required.toMap())
                }
                require(orders.isNotEmpty()) { "Farm zone $id has no orders" }
                FarmZoneSettings(
                    id = id,
                    reference = parseReference(section, "", id),
                    permission = permission(section.string("permission", "arcfarms.farm")),
                    incidentTriggerPercent = section.int("incident-trigger-percent", 35).checked("incident-trigger-percent", 1, 99),
                    incidentQuota = section.int("incident-quota", 4).checked("incident-quota", 1, 64),
                    goldenWindowSeconds = section.int("golden-window-seconds", 45).checked("golden-window-seconds", 5, 600),
                    crops = crops,
                    orders = orders,
                )
            }

            val lumbermills = config.keys("lumber-zones").sorted().mapNotNull { id ->
                val section = config.section("lumber-zones.$id")
                if (!section.boolean("enabled", true)) return@mapNotNull null
                validateId(id, "lumber zone")
                val reference = parseReference(section, "", id)
                val station = parseReference(section, "station-", "$id station", reference.world)
                LumberZoneSettings(
                    id = id,
                    reference = reference,
                    station = station,
                    permission = permission(section.string("permission", "arcfarms.lumber")),
                    fellingQuota = section.int("felling-quota", 16).checked("felling-quota", 1, 100_000),
                    processingQuota = section.int("processing-quota", 6).checked("processing-quota", 1, 100_000),
                    processingPerUse = section.int("processing-per-use", 2).checked("processing-per-use", 1, 100_000),
                    species = section.stringList("species").map(::speciesName).distinct().also {
                        require(it.isNotEmpty()) { "Lumber zone $id has no species" }
                    },
                    stationMaterials = section.stringList("station-materials").map(::materialName).toSet().also {
                        require(it.isNotEmpty()) { "Lumber zone $id has no station materials" }
                    },
                ).also {
                    require(it.processingPerUse <= it.processingQuota) { "processing-per-use exceeds processing-quota in $id" }
                }
            }

            val mines = config.keys("mine-zones").sorted().mapNotNull { id ->
                val section = config.section("mine-zones.$id")
                if (!section.boolean("enabled", true)) return@mapNotNull null
                validateId(id, "mine zone")
                val materialWeights = parseWeightedList(section.stringList("materials"), "mine $id materials")
                val baseMaterial = materialName(section.string("base-material"))
                require(baseMaterial in materialWeights) { "Mine $id base-material must be present in materials" }
                MineZoneSettings(
                    id = id,
                    priority = section.int("priority", 0).checked("mine priority", -1000, 1000),
                    reference = parseReference(section, "", id),
                    permission = permission(section.string("permission", "arcfarms.mine")),
                    cartQuota = section.int("cart-quota", 16).checked("cart-quota", 2, 100_000),
                    hazardTrigger = section.int("hazard-trigger", 6).checked("hazard-trigger", 1, 99_999),
                    supportsRequired = section.int("supports-required", 1).checked("supports-required", 1, 32),
                    restoreSeconds = section.int("restore-seconds", 60).checked("restore-seconds", 5, 3600),
                    temporaryMaterial = materialName(section.string("temp-material")),
                    baseMaterial = baseMaterial,
                    materialWeights = LinkedHashMap(materialWeights),
                ).also {
                    require(it.hazardTrigger < it.cartQuota) { "Mine $id hazard-trigger must be below cart-quota" }
                    require(it.temporaryMaterial !in it.materialWeights) { "Mine $id temp-material must not be a generated material" }
                }
            }

            val navigation = mapOf(
                "farm" to command(config.string("navigation.farm-command", "warp farm")),
                "lumber" to command(config.string("navigation.lumber-command", "warp lumber")),
                "mine" to command(config.string("navigation.mine-command", "warp mine")),
            )
            val allowedOrigins = config.stringList("network.allowed-origins")
                .ifEmpty { listOf("spawn", "survival", "parkour") }
                .mapTo(linkedSetOf()) { serverId(it, "network.allowed-origins") }
            require(serverId in allowedOrigins) { "network.allowed-origins must include server-id" }
            val hubServer = serverId(config.string("network.hub-server", "spawn"), "network.hub-server")
            require(hubServer in allowedOrigins) { "network.allowed-origins must include network.hub-server" }
            val network = NetworkSettings(
                enabled = config.boolean("network.enabled", true),
                hubServer = hubServer,
                transferCommand = command(config.string("network.transfer-command", "server $hubServer")),
                allowedOrigins = allowedOrigins,
                callsEnabled = config.boolean("network.calls", true),
                completionsEnabled = config.boolean("network.completions", true),
                workdayEnabled = config.boolean("network.workday", true),
                nodeProbeEnabled = config.boolean("network.node-probe", true),
            )
            return ArcFarmsConfig(
                enabled = config.boolean("enabled", true),
                serverId = serverId,
                network = network,
                defaultLocale = defaultLocale,
                useClientLocale = config.boolean("locale.use-client-locale", true),
                bossbars = config.boolean("ui.bossbars", true),
                particles = config.boolean("ui.particles", true),
                sounds = config.boolean("ui.sounds", true),
                saveSeconds = config.int("state.save-seconds", 10).checked("state.save-seconds", 1, 300),
                completedCooldownSeconds = config.int("state.completed-cooldown-seconds", 180).checked("completed cooldown", 0, 3600),
                navigation = navigation,
                farms = farms,
                lumbermills = lumbermills,
                mines = mines.sortedByDescending(MineZoneSettings::priority),
            )
        }

        private fun parseReference(
            section: ru.arc.config.ConfigSection,
            prefix: String,
            label: String,
            defaultWorld: String? = null,
        ): ZoneReference {
            val world = section.stringOrNull("${prefix}world") ?: defaultWorld
                ?: error("$label is missing ${prefix}world")
            val region = section.stringOrNull("${prefix}region")?.takeIf(String::isNotBlank)
            val min = section.stringListOrNull("${prefix}bounds.min")
            val max = section.stringListOrNull("${prefix}bounds.max")
            val bounds = if (min != null || max != null) {
                require(min?.size == 3 && max?.size == 3) { "$label bounds require three min and max coordinates" }
                val a = min.map { it.toIntOrNull() ?: error("$label has a non-integer bound") }
                val b = max.map { it.toIntOrNull() ?: error("$label has a non-integer bound") }
                CuboidBounds(a[0], a[1], a[2], b[0], b[1], b[2])
            } else null
            return ZoneReference(world, region, bounds)
        }

        private fun parseWeightedList(values: List<String>, label: String): LinkedHashMap<String, Int> {
            require(values.isNotEmpty()) { "$label must not be empty" }
            val parsed = linkedMapOf<String, Int>()
            values.forEach { entry ->
                val split = entry.lastIndexOf(':')
                require(split in 1 until entry.lastIndex) { "$label entry must be MATERIAL:WEIGHT: $entry" }
                val material = materialName(entry.substring(0, split))
                val amount = entry.substring(split + 1).trim().toIntOrNull()
                    ?: error("$label has a non-integer value: $entry")
                require(amount in 1..100_000) { "$label value is outside 1..100000: $entry" }
                require(parsed.put(material, amount) == null) { "$label duplicates $material" }
            }
            require(parsed.values.sumOf(Int::toLong) <= 1_000_000L) { "$label total weight is unbounded" }
            return parsed
        }

        private fun validateId(value: String, label: String) {
            require(value.matches(Regex("[a-z0-9_-]{1,48}"))) { "Invalid $label id: $value" }
        }

        private fun serverId(value: String, label: String): String = value.trim().lowercase().also {
            require(it.matches(Regex("[a-z0-9_-]{1,32}"))) { "$label must use lowercase letters, digits, _ or -" }
        }

        private fun materialName(value: String): String = value.trim().uppercase().also {
            require(it.matches(Regex("[A-Z0-9_]{2,64}"))) { "Invalid material name: $value" }
        }

        private fun speciesName(value: String): String = materialName(value).also {
            require(!it.endsWith("_LOG") && !it.endsWith("_WOOD")) { "Use a wood species, not a block material: $value" }
        }

        private fun permission(value: String): String = value.trim().also {
            require(it.matches(Regex("[a-z0-9._-]{1,128}"))) { "Invalid permission: $value" }
        }

        private fun command(value: String): String = value.trim().removePrefix("/").also {
            require(it.length in 1..128 && '\n' !in it && '\r' !in it && ';' !in it) { "Invalid navigation command" }
        }

        private fun Int.checked(label: String, minimum: Int, maximum: Int): Int = also {
            require(it in minimum..maximum) { "$label must be in $minimum..$maximum" }
        }
    }
}

object ArcFarmsRedisBootstrap {
    fun load(dataRoot: Path, settings: ArcFarmsConfig): RedisModuleConfig {
        val redis = RedisModuleConfig.load(dataRoot)
        require(!settings.network.enabled || redis.enabled) { "Redis must be enabled when ArcFarms network is enabled" }
        require(redis.serverName == settings.serverId) {
            "modules/redis.yml server-name must match config.yml server-id"
        }
        require(redis.host.isNotBlank() && redis.host.length <= 253) { "Redis host is invalid" }
        require(redis.port in 1..65_535) { "Redis port is outside 1..65535" }
        require(redis.username.length <= 128 && redis.password.length <= 512) { "Redis credentials exceed safe bounds" }
        return redis
    }
}
