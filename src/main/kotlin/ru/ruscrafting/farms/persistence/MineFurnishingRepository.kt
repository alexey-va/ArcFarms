package ru.ruscrafting.farms.persistence

import java.nio.file.Path

/** Local offsets belong to a permanent site, never to a shift or event nonce. */
data class MineFurnishingPose(val x: Int = 0, val y: Int = 0, val z: Int = 0, val yaw: Int = 0) {
    fun validate() {
        require(x in -96..96 && y in -40..40 && z in -96..96)
        require(yaw in 0..359)
    }
}
data class MineFurnishingState(val schemaVersion: Int = 1, val poses: Map<String, MineFurnishingPose> = emptyMap())

class MineFurnishingRepository(root: Path) {
    private val store = AtomicJsonStore(root.resolve("data/mine-expedition-furnishings.json"),
        MineFurnishingState::class.java, ::MineFurnishingState, { state ->
            require(state.schemaVersion == 1 && state.poses.size <= 8192)
            state.poses.forEach { (id, pose) ->
                require(id.matches(Regex("[0-9]+/[a-z0-9_-]{1,64}")))
                pose.validate()
            }
        })
    fun loadAsync() = store.loadAsync()
    fun saveAsync(state: MineFurnishingState) = store.saveAsync(state)
    fun close() = store.shutdown()
}
