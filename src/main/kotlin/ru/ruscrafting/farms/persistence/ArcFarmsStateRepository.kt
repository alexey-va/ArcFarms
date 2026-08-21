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
            state.stats.values.forEach { stats ->
                require(stats.contributions.values.all { it in 0..Long.MAX_VALUE }) { "Negative contribution" }
                require(stats.completedShifts.values.all { it >= 0 }) { "Negative completion count" }
            }
        }
    }
}
