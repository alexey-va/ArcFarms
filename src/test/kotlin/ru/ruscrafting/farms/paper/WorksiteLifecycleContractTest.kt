package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.mockk
import org.bukkit.Chunk
import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.ActivityKind

class WorksiteLifecycleContractTest : FunSpec({
    test("registry invokes every lifecycle phase once in stable activity order") {
        val calls = mutableListOf<String>()
        val registry = WorksiteModuleRegistry(
            listOf(
                LifecycleModule(ActivityKind.MINE, calls),
                LifecycleModule(ActivityKind.FARM, calls),
                LifecycleModule(ActivityKind.LUMBER, calls),
            ),
        )
        val chunk = mockk<Chunk>()

        registry.activateLoadedState()
        registry.reconcileChunk(chunk)
        registry.beforeReload("config_reload")
        registry.cleanup("shutdown")

        calls shouldContainExactly listOf(
            "FARM:activate",
            "LUMBER:activate",
            "MINE:activate",
            "FARM:chunk",
            "LUMBER:chunk",
            "MINE:chunk",
            "FARM:before:config_reload",
            "LUMBER:before:config_reload",
            "MINE:before:config_reload",
            "FARM:cleanup:shutdown",
            "LUMBER:cleanup:shutdown",
            "MINE:cleanup:shutdown",
        )
    }
})

private class LifecycleModule(
    override val kind: ActivityKind,
    private val calls: MutableList<String>,
) : WorksiteModule<Any> {
    override val zoneCount: Int = 1

    override fun states(): Map<String, Any> = emptyMap()
    override fun statuses(): List<ActivityStatus> = emptyList()
    override fun tick(now: Long) = Unit
    override fun canAccess(player: Player): Boolean = true
    override fun activateLoadedState() {
        calls += "$kind:activate"
    }

    override fun reconcileChunk(chunk: Chunk) {
        calls += "$kind:chunk"
    }

    override fun beforeReload(reason: String) {
        calls += "$kind:before:$reason"
    }

    override fun cleanup(reason: String) {
        calls += "$kind:cleanup:$reason"
    }
}
