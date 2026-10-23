package ru.ruscrafting.farms.paper.integration

import dev.aurelium.auraskills.api.event.loot.LootDropEvent
import dev.aurelium.auraskills.api.event.skill.XpGainEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin

/** Keeps the optional API out of always-loaded plugin and listener descriptors. */
internal object MineAuraSkillsGuard {
    fun install(plugin: Plugin, managedMineWorlds: () -> Set<String>): AutoCloseable? {
        val auraSkills = plugin.server.pluginManager.getPlugin("AuraSkills") ?: return null
        check(auraSkills.isEnabled) { "AuraSkills is installed but unavailable; native mine rewards cannot be guarded" }
        try {
            val loader = MineAuraSkillsGuard::class.java.classLoader
            Class.forName("dev.aurelium.auraskills.api.event.skill.XpGainEvent", false, loader)
            Class.forName("dev.aurelium.auraskills.api.event.loot.LootDropEvent", false, loader)
            return MineAuraSkillsListener(managedMineWorlds).also {
                plugin.server.pluginManager.registerEvents(it, plugin)
                plugin.logger.info("Managed mine ore rewards guarded for AuraSkills ${auraSkills.pluginMeta.version}")
            }
        } catch (failure: ReflectiveOperationException) {
            throw unsupported(auraSkills, failure)
        } catch (failure: LinkageError) {
            throw unsupported(auraSkills, failure)
        }
    }

    private fun unsupported(auraSkills: Plugin, failure: Throwable) = IllegalStateException(
        "AuraSkills ${auraSkills.pluginMeta.version} lacks the supported 2.3.12 event API; native mine rewards cannot be guarded",
        failure,
    )
}

/** Only native ore sources and Mining Luck are suppressed; command XP and combat keep their owners. */
private class MineAuraSkillsListener(private val managedMineWorlds: () -> Set<String>) : Listener, AutoCloseable {
    private val miningLuck = LootDropEvent.Cause.MINING_LUCK

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onXp(event: XpGainEvent) {
        if (event.player.world.name !in managedMineWorlds()) return
        val skill = event.skill.id
        if (skill.namespace != "auraskills" || skill.key != "mining") return
        val source = event.source?.id ?: return
        if (source.namespace == "auraskills" && source.key in ORE_SOURCES) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onLoot(event: LootDropEvent) {
        if (event.cause == miningLuck && event.location.world?.name in managedMineWorlds()) event.isCancelled = true
    }

    override fun close() = HandlerList.unregisterAll(this)

    private companion object {
        val ORE_SOURCES = setOf(
            "coal_ore", "nether_quartz_ore", "iron_ore", "lapis_ore", "redstone_ore", "gold_ore",
            "diamond_ore", "emerald_ore", "nether_gold_ore", "ancient_debris", "copper_ore",
            "deepslate_coal_ore", "deepslate_iron_ore", "deepslate_copper_ore", "deepslate_gold_ore",
            "deepslate_redstone_ore", "deepslate_lapis_ore", "deepslate_emerald_ore", "deepslate_diamond_ore",
        )
    }
}
