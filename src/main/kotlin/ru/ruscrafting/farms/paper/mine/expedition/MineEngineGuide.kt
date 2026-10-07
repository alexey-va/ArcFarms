package ru.ruscrafting.farms.paper.mine.expedition

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcInspectionFrame
import ru.arc.paper.api.ArcInspectionProvider
import ru.arc.paper.api.ArcInspectionService
import ru.ruscrafting.farms.config.ArcFarmsLocale

/** Feature meaning stays here; ARC's shared Core service owns viewer preferences, polling and the card. */
internal class MineEngineGuide(
    private val plugin: Plugin,
    private val markers: MineExpeditionMarkers,
    private val locale: ArcFarmsLocale?,
) : AutoCloseable {
    private var service: ArcInspectionService? = null
    private var registration: AutoCloseable? = null

    /** Rebind after ARC reload without keeping a registration in the retired service. */
    fun reconcile() {
        val current = plugin.server.servicesManager.load(ArcInspectionService::class.java)
        if (current === service) return
        close()
        service = current
        registration = current?.register(plugin, "mine-engine-guide", 100, ArcInspectionProvider(::resolve))
    }

    private fun resolve(player: Player): ArcInspectionFrame? {
        val hit = markers.inspectEngine(player) ?: return null
        val id = hit.part.inspection ?: return emptyFrame()
        val catalog = locale ?: return emptyFrame()
        val title = catalog.renderPath("mine.expedition.engine-guide.$id.title", player)
        val description = catalog.renderPath("mine.expedition.engine-guide.$id.description", player)
        return ArcInspectionFrame(title.append(Component.newline()).append(description), title, null)
    }

    // Avoid ARC's Kotlin default-argument constructor bridge across plugin classloaders.
    private fun emptyFrame() = ArcInspectionFrame(Component.empty(), Component.empty(), null)

    override fun close() {
        registration?.close()
        registration = null
        service = null
    }
}
