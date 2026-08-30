package ru.ruscrafting.farms.paper.worksite

import org.bukkit.Location
import org.bukkit.entity.Player
import ru.ruscrafting.farms.paper.ActivityBarKey
import java.util.UUID

internal class WorksiteGuidancePresenter(
    private val audience: WorksiteAudiencePort,
    private val access: WorksiteAccessPort,
    private val source: WorksiteGuidanceSource,
    private val stallMillis: Long = 12_000L,
) {
    private val sessions = mutableMapOf<UUID, Session>()

    init {
        require(stallMillis in 1_000L..300_000L) { "Worksite guidance stall interval is invalid" }
    }

    val sessionCount: Int get() = sessions.size

    fun updateHud(now: Long) {
        require(now >= 0) { "Worksite guidance time cannot be negative" }
        val expected = mutableSetOf<ActivityBarKey>()
        source.participants().distinctBy(Player::getUniqueId).forEach { player ->
            if (!player.isOnline || access.isAdminEditing(player)) {
                releasePlayer(player)
                return@forEach
            }
            val view = source.view(player.uniqueId)
            if (view == null) {
                releasePlayer(player)
                return@forEach
            }
            var session = sessionFor(player.uniqueId, view, now)
            audience.updateBar(
                player,
                view.runtimeKey,
                view.barName,
                view.barProgress.coerceIn(0f, 1f),
                view.barColor,
                expected,
            )
            if (elapsed(now, session.lastProgressAt) >= stallMillis && elapsed(now, session.lastReminderAt) >= stallMillis) {
                audience.showScreenTitle(player, view.title, view.subtitle)
                session = session.copy(lastReminderAt = now)
            }
            sessions[player.uniqueId] = session
        }
        audience.reconcileBars(expected)
    }

    fun emitParticles() {
        source.participants().distinctBy(Player::getUniqueId).forEach { player ->
            if (!player.isOnline || access.isAdminEditing(player)) return@forEach
            val view = source.view(player.uniqueId) ?: return@forEach
            nearestLoadedTargets(player, view.targets).forEach { target ->
                repeat(COLUMN_PARTICLES) { index ->
                    val point = target.position.clone().add(0.0, index * COLUMN_STEP, 0.0)
                    audience.spawnGuidanceDust(player, point, target.color, target.particleSize)
                }
            }
        }
    }

    fun recordProgress(playerId: UUID, runtimeKey: String, progressVersion: Long, now: Long) {
        require(runtimeKey.isNotBlank()) { "Worksite guidance runtime key cannot be blank" }
        require(progressVersion >= 0 && now >= 0) { "Worksite guidance progress marker is invalid" }
        sessions[playerId] = Session(runtimeKey, progressVersion, now, now)
    }

    fun releasePlayer(player: Player) {
        sessions.remove(player.uniqueId)
        audience.removePlayerBars(player)
    }

    private fun sessionFor(playerId: UUID, view: WorksiteGuidanceView, now: Long): Session {
        val current = sessions[playerId]
        if (current == null || current.runtimeKey != view.runtimeKey || current.progressVersion != view.progressVersion) {
            return Session(view.runtimeKey, view.progressVersion, now, now)
        }
        return current
    }

    private fun nearestLoadedTargets(
        player: Player,
        targets: List<WorksiteGuidanceTarget>,
    ): List<WorksiteGuidanceTarget> = targets.asSequence()
        .filter { it.loaded && it.position.world === player.world }
        .groupBy(WorksiteGuidanceTarget::role)
        .values
        .mapNotNull { roleTargets ->
            roleTargets.minWithOrNull(compareBy<WorksiteGuidanceTarget>({ distanceSquared(player.location, it.position) }, { it.id }))
        }
        .sortedWith(compareBy({ it.role.value }, { it.id }))

    private fun distanceSquared(origin: Location, target: Location): Double {
        val dx = origin.x - target.x
        val dy = origin.y - target.y
        val dz = origin.z - target.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun elapsed(now: Long, then: Long): Long = if (now >= then) now - then else 0L

    private data class Session(
        val runtimeKey: String,
        val progressVersion: Long,
        val lastProgressAt: Long,
        val lastReminderAt: Long,
    )

    private companion object {
        const val COLUMN_PARTICLES = 8
        const val COLUMN_STEP = 0.32
    }
}
