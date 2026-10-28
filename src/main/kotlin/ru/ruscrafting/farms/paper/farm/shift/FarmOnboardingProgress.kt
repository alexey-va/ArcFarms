package ru.ruscrafting.farms.paper.farm.shift

import org.bukkit.Bukkit
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.onboarding.api.OptionalOnboardingProgressService
import java.util.UUID
import java.util.logging.Level

/** The guard keeps older ARC providers binary-safe; only the typed adapter links the optional API. */
internal fun recordFarmOnboardingCompletion(
    players: Set<UUID>,
    eventId: String,
    tasks: WorksiteTaskPort,
) {
    if (farmOnboardingApiAvailable) FarmOnboardingProgress.record(players, eventId, tasks)
}

/** Called only with a successfully saved snapshot; per-player receipts survive reload and cooldown reset. */
internal fun replayFarmOnboardingCompletions(receipts: Map<UUID, String>, tasks: WorksiteTaskPort) {
    receipts.forEach { (player, eventId) -> recordFarmOnboardingCompletion(setOf(player), eventId, tasks) }
}

private val farmOnboardingApiAvailable: Boolean by lazy {
    val provider = Bukkit.getPluginManager().getPlugin("ARC") ?: return@lazy false
    runCatching { Class.forName("ru.ruscrafting.onboarding.api.OptionalOnboardingProgressService", false, provider.javaClass.classLoader) }.isSuccess
}

internal object FarmOnboardingProgress {
    fun record(players: Set<UUID>, eventId: String, tasks: WorksiteTaskPort, attempt: Int = 0) {
        check(Bukkit.isPrimaryThread())
        val service = Bukkit.getServicesManager().load(OptionalOnboardingProgressService::class.java) ?: return
        val token = tasks.lifecycleToken()
        players.forEach { playerId ->
            service.recordFarmShift(playerId, eventId).whenComplete { _, failure ->
                if (failure != null) {
                    if (attempt < 3) tasks.runLater(token, 100L * (attempt + 1)) { record(setOf(playerId), eventId, tasks, attempt + 1) }
                    else Bukkit.getLogger().log(Level.WARNING, "Could not retain optional farm introduction player=$playerId", failure)
                }
            }
        }
    }
}
