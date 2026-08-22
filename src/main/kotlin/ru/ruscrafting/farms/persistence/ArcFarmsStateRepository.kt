package ru.ruscrafting.farms.persistence

import ru.ruscrafting.farms.domain.ArcFarmsState
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class ArcFarmsStateRepository(dataRoot: Path) : AutoCloseable {
    private val store = AtomicJsonStore(
        path = dataRoot.resolve("data/state.json"),
        type = ArcFarmsState::class.java,
        emptyValue = ::ArcFarmsState,
        validate = ::validateState,
    )

    fun load(): ArcFarmsState = store.load()

    fun saveAsync(state: ArcFarmsState): CompletableFuture<Unit> = store.saveAsync(state)

    fun saveBlocking(state: ArcFarmsState) = store.saveBlocking(state)

    override fun close() = store.close()

    private companion object {
        fun validateState(state: ArcFarmsState) {
            require(state.schemaVersion == ArcFarmsState.SCHEMA_VERSION) { "Unsupported ArcFarms state schema" }
            require(state.farms.size <= 256 && state.lumbermills.size <= 256 && state.mines.size <= 256) {
                "ArcFarms state contains too many zones"
            }
            require(state.stats.size <= 1_000_000) { "ArcFarms player statistics are unbounded" }
            state.farms.values.forEach { farm ->
                require(farm.preparationPatch.size <= 512) { "Farm preparation patch is unbounded" }
                require(farm.preparationPatch.distinct().size == farm.preparationPatch.size) {
                    "Farm preparation patch contains duplicate plots"
                }
                require(farm.preparationPatch.map { it.world }.distinct().size <= 1) {
                    "Farm preparation patch crosses worlds"
                }
                require(farm.preparationPatch.all { plot ->
                    plot.world.matches(Regex("[A-Za-z0-9._-]{1,128}")) &&
                        plot.x in -30_000_000..30_000_000 && plot.z in -30_000_000..30_000_000 &&
                        plot.y in -4_096..4_096
                }) { "Farm preparation patch contains an invalid position" }
                require(farm.preparationProgress >= 0 && farm.plantingProgress >= 0 && farm.preparationRequired >= 0) {
                    "Farm preparation progress is negative"
                }
                require(farm.deliveredCrates.size <= 8 && farm.deliveredCrates.all { it in 0..7 }) {
                    "Farm delivery crate state is invalid"
                }
                require(farm.droughtPlots.size <= 64 && farm.droughtDamagedPlots.size <= 4_096) {
                    "Farm drought state is unbounded"
                }
                require(farm.pestNests.size <= 16 && farm.pestNests.distinctBy { it.position }.size == farm.pestNests.size) {
                    "Farm pest nests are invalid"
                }
                require(farm.pestAlive in 0..32 && farm.pestDamagedCrops.size <= 4_096) { "Farm pest state is unbounded" }
                require(farm.pestDamagedCrops.distinctBy { it.position }.size == farm.pestDamagedCrops.size) {
                    "Farm pest crop damage contains duplicate plots"
                }
                require(farm.tilledPlots.all(farm.preparationPatch::contains)) { "Farm tilled plots escaped their patch" }
                require(farm.plantedPlots.all(farm.tilledPlots::contains)) { "Farm planted plots were not tilled" }
                if (farm.preparationPatch.isNotEmpty()) {
                    require(farm.preparationCrop != null) { "Farm preparation patch has no crop" }
                    require(farm.preparationRequired == farm.preparationPatch.size) { "Farm preparation quota drifted from its patch" }
                    require(farm.preparationProgress == farm.tilledPlots.size) { "Farm tilling progress drifted from its plots" }
                    require(farm.plantingProgress == farm.plantedPlots.size) { "Farm planting progress drifted from its plots" }
                }
            }
            state.stats.values.forEach { stats ->
                require(stats.contributions.values.all { it in 0..Long.MAX_VALUE }) { "Negative contribution" }
                require(stats.completedShifts.values.all { it >= 0 }) { "Negative completion count" }
            }
        }
    }
}
