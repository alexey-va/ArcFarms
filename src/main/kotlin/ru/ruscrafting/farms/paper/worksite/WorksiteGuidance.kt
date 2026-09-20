package ru.ruscrafting.farms.paper.worksite

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import java.util.UUID

internal data class WorksiteGuidanceTarget(
    val id: String,
    val role: ObjectiveTargetRole,
    val position: Location,
    val color: Color,
    val loaded: Boolean = true,
    val particleSize: Float = 1.1f,
    val columnParticles: Int = 8,
    val columnStep: Double = 0.32,
    val bright: Boolean = false,
) {
    init {
        require(particleSize > 0f && particleSize.isFinite()) { "Guidance particle size must be positive" }
        require(columnParticles in 1..64) { "Guidance column particle count is invalid" }
        require(columnStep in 0.05..2.0) { "Guidance column step is invalid" }
    }
}

internal data class WorksiteGuidanceView(
    val runtimeKey: String,
    val progressVersion: Long,
    val title: Component,
    val subtitle: Component,
    val barName: Component,
    val barProgress: Float,
    val barColor: BossBar.Color,
    val targets: List<WorksiteGuidanceTarget> = emptyList(),
    val sidebarRows: List<Component> = listOf(barName),
    val quietProgress: Boolean = false,
    val screenTitles: Boolean = true,
) {
    init {
        require(runtimeKey.isNotBlank()) { "Worksite guidance runtime key cannot be blank" }
        require(progressVersion >= 0) { "Worksite guidance progress version cannot be negative" }
        require(barProgress.isFinite()) { "Worksite guidance progress must be finite" }
    }
}

internal interface WorksiteGuidanceSource {
    fun participants(): Collection<Player>
    fun view(playerId: UUID): WorksiteGuidanceView?
}
