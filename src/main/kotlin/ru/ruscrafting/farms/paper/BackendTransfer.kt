package ru.ruscrafting.farms.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.network.BackendServerId
import ru.arc.paper.network.BackendTransferResult

fun interface BackendTransfer {
    fun connect(player: Player, serverId: String): Boolean
}

class BungeeBackendTransfer(
    plugin: Plugin,
    onSendFailure: (RuntimeException) -> Unit = {},
) : BackendTransfer, AutoCloseable {
    private val delegate = ru.arc.paper.network.BungeeBackendTransfer(plugin, onSendFailure)

    override fun connect(player: Player, serverId: String): Boolean =
        delegate.connect(player, BackendServerId.of(serverId)) == BackendTransferResult.SENT

    override fun close() = delegate.close()

    companion object {
        const val CHANNEL = ru.arc.paper.network.BungeeBackendTransfer.CHANNEL
    }
}
