package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind

/** Reserve management never modifies an occupied or claimed expedition. Caller enforces arcfarms.admin. */
internal class MineExpeditionAdminCommand(private val service: ArcFarmsService, private val locale: ArcFarmsLocale) {
    fun execute(sender: CommandSender, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "edit" -> (sender as? org.bukkit.entity.Player)?.let { service.editExpeditionFurnishings(it,args.getOrNull(1)) }
            "status" -> service.expeditionStock().forEach { status ->
                sender.sendMessage(locale.renderPath("admin.expeditions.status", sender, mapOf(
                    "kind" to Component.text(status.kind.name.lowercase()),
                    "ready" to Component.text(status.ready), "building" to Component.text(status.building),
                    "failed" to Component.text(status.failed), "retiring" to Component.text(status.retiring),
                )))
            }
            "rebuild" -> {
                val raw = args.getOrNull(1)?.lowercase() ?: "all"
                val kind = MineExpeditionKind.entries.firstOrNull { it.name.equals(raw, true) }
                if (args.size > 2 || kind == null && raw != "all") { help(sender); return }
                val count = service.rebuildExpeditionStock(kind)
                sender.sendMessage(locale.renderPath("admin.expeditions.rebuilding", sender,
                    mapOf("count" to Component.text(count))))
            }
            "factory" -> configureFactory(sender, args.drop(1))
            else -> help(sender)
        }
    }

    private fun configureFactory(sender: CommandSender, args: List<String>) {
        val zoneArg = args.getOrNull(0)
        val preset = args.getOrNull(1)?.lowercase()
        if (args.size != 2 || preset == null || preset !in FACTORY_PRESETS) {
            help(sender)
            return
        }
        val zone = service.mineZoneIds().firstOrNull { it.equals(zoneArg, ignoreCase = true) }
        if (zone == null) {
            sender.sendMessage(locale.renderPath("admin.expeditions.factory.unknown-zone", sender,
                mapOf("zone" to Component.text(zoneArg ?: "?"))))
            return
        }
        if (service.factoryExperiments(zone, preset)) {
            sender.sendMessage(locale.renderPath("admin.expeditions.factory.queued", sender,
                mapOf("zone" to Component.text(zone), "preset" to Component.text(preset))))
        } else {
            sender.sendMessage(locale.renderPath("admin.expeditions.factory.rejected", sender,
                mapOf("zone" to Component.text(zone))))
        }
    }

    private fun help(sender: CommandSender) { sender.sendMessage(locale.renderPath("admin.expeditions.help", sender)) }

    companion object {
        internal val FACTORY_PRESETS = listOf("random", "none", "all", "rock", "crane", "cooling")
    }
}
