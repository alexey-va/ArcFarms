package ru.ruscrafting.farms.paper

import org.bukkit.plugin.ServicePriority
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.MockBukkit
import ru.arc.paper.api.ArcSidebarFrame
import ru.arc.paper.api.ArcSidebarHandle
import ru.arc.paper.api.ArcSidebarRegistrationSnapshot
import ru.arc.paper.api.ArcSidebarSelectionSnapshot
import ru.arc.paper.api.ArcSidebarService
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

internal fun MockBukkitTestRuntime.installArcSidebarHost() {
    val host = MockBukkit.createMockPlugin("ARC")
    val service = NoOpArcSidebarService
    server.servicesManager.register(ArcSidebarService::class.java, service, host, ServicePriority.Normal)
}

private object NoOpArcSidebarService : ArcSidebarService {
    override fun register(owner: Plugin, id: String, priority: Int): ArcSidebarHandle = object : ArcSidebarHandle {
        override fun show(player: Player, frame: ArcSidebarFrame) = Unit
        override fun hide(playerId: UUID) = Unit
        override fun close() = Unit
    }

    override fun registrations(): List<ArcSidebarRegistrationSnapshot> = emptyList()

    override fun active(playerId: UUID): ArcSidebarSelectionSnapshot? = null
}
