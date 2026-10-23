package ru.ruscrafting.farms.paper.integration

import dev.aurelium.auraskills.api.event.loot.LootDropEvent
import dev.aurelium.auraskills.api.event.skill.XpGainEvent
import dev.aurelium.auraskills.api.registry.NamespacedId
import dev.aurelium.auraskills.api.skill.Skill
import dev.aurelium.auraskills.api.source.XpSource
import dev.aurelium.auraskills.api.user.SkillsUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

class MineAuraSkillsGuardTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var plugin: Plugin
    lateinit var player: Player
    var worlds = setOf("rc_atelier_compact_mine")
    var guard: AutoCloseable? = null

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        plugin = paper.createSimplePlugin("MineAuraGuardTest")
        val mine = paper.server.addSimpleWorld("rc_atelier_compact_mine")
        paper.server.addSimpleWorld("survival")
        player = paper.server.addPlayer("Miner").also { it.teleport(Location(mine, 5.5, 83.0, 5.5)) }
        worlds = setOf(mine.name)
    }
    afterEach {
        guard?.close()
        guard = null
        paper.close()
    }

    fun install() {
        paper.createSimplePlugin("AuraSkills")
        guard = MineAuraSkillsGuard.install(plugin) { worlds }
        (guard != null) shouldBe true
    }

    fun xp(skillKey: String = "mining", sourceKey: String? = "iron_ore", namespace: String = "auraskills"): XpGainEvent {
        val skill = mockk<Skill> { every { id } returns NamespacedId.of("auraskills", skillKey) }
        val source = sourceKey?.let { key -> mockk<XpSource> { every { id } returns NamespacedId.of(namespace, key) } }
        return XpGainEvent(player, mockk<SkillsUser>(), skill, source, 5.0)
    }

    fun loot(cause: LootDropEvent.Cause, world: String = "rc_atelier_compact_mine") = LootDropEvent(
        player, mockk<SkillsUser>(), ItemStack(Material.DIAMOND),
        Location(paper.server.getWorld(world), 5.5, 83.0, 5.5), cause, false,
    )

    test("registered guard cancels the configured ordinary ore sources only") {
        install()
        listOf("coal_ore", "nether_quartz_ore", "iron_ore", "lapis_ore", "redstone_ore", "gold_ore",
            "diamond_ore", "emerald_ore", "nether_gold_ore", "ancient_debris", "copper_ore",
            "deepslate_coal_ore", "deepslate_iron_ore", "deepslate_copper_ore", "deepslate_gold_ore",
            "deepslate_redstone_ore", "deepslate_lapis_ore", "deepslate_emerald_ore", "deepslate_diamond_ore").forEach {
            paper.callEvent(xp(sourceKey = it)).isCancelled shouldBe true
        }
        paper.callEvent(xp(sourceKey = "amethyst_cluster")).isCancelled shouldBe false
        paper.callEvent(xp(sourceKey = "stone")).isCancelled shouldBe false
        paper.callEvent(xp(namespace = "custom")).isCancelled shouldBe false
    }

    test("other worlds other skills and source-null administrative XP are preserved") {
        install()
        paper.callEvent(xp(skillKey = "fighting")).isCancelled shouldBe false
        paper.callEvent(xp(sourceKey = null)).isCancelled shouldBe false
        player.teleport(Location(paper.server.getWorld("survival"), 0.5, 64.0, 0.5))
        paper.callEvent(xp()).isCancelled shouldBe false
    }

    test("guarded ore cannot reach NORMAL XP conversion or Mending side effects") {
        install()
        player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE).apply {
            editMeta { (it as Damageable).damage = 20 }
        })
        // AuraSkills 2.3.12's NORMAL XP Convert and Skill Mender handlers check cancellation before these effects.
        paper.server.pluginManager.registerEvent(XpGainEvent::class.java, object : Listener {}, EventPriority.NORMAL,
            EventExecutor { _, raw ->
                val event = raw as XpGainEvent
                if (!event.isCancelled) {
                    event.player.giveExp(2)
                    val held = event.player.inventory.itemInMainHand
                    held.editMeta { (it as Damageable).damage -= 3 }
                    event.player.inventory.setItemInMainHand(held)
                }
            }, plugin)

        paper.callEvent(xp()).isCancelled shouldBe true
        player.totalExperience shouldBe 0
        (player.inventory.itemInMainHand.itemMeta as Damageable).damage shouldBe 20

        paper.callEvent(xp(sourceKey = null)).isCancelled shouldBe false
        player.totalExperience shouldBe 2
        (player.inventory.itemInMainHand.itemMeta as Damageable).damage shouldBe 17

        player.teleport(Location(paper.server.getWorld("survival"), 0.5, 64.0, 0.5))
        paper.callEvent(xp()).isCancelled shouldBe false
        player.totalExperience shouldBe 4
        (player.inventory.itemInMainHand.itemMeta as Damageable).damage shouldBe 14
    }

    test("only Mining Luck loot at the managed source world is cancelled") {
        install()
        LootDropEvent.Cause.entries.forEach { cause ->
            paper.callEvent(loot(cause)).isCancelled shouldBe (cause == LootDropEvent.Cause.MINING_LUCK)
        }
        paper.callEvent(loot(LootDropEvent.Cause.MINING_LUCK, "survival")).isCancelled shouldBe false
        player.teleport(Location(paper.server.getWorld("survival"), 0.5, 64.0, 0.5))
        paper.callEvent(loot(LootDropEvent.Cause.MINING_LUCK)).isCancelled shouldBe true
    }

    test("Mining Luck is cancelled before NORMAL loot observers while combat and other worlds retain their effects") {
        install()
        var granted = 0
        paper.server.pluginManager.registerEvent(LootDropEvent::class.java, object : Listener {}, EventPriority.NORMAL,
            EventExecutor { _, raw ->
                val event = raw as LootDropEvent
                event.player.inventory.addItem(event.item.clone())
                granted++
            }, plugin, true)

        paper.callEvent(loot(LootDropEvent.Cause.MINING_LUCK)).isCancelled shouldBe true
        granted shouldBe 0
        player.inventory.contains(Material.DIAMOND) shouldBe false

        paper.callEvent(loot(LootDropEvent.Cause.MOB_LOOT_TABLE)).isCancelled shouldBe false
        granted shouldBe 1
        player.inventory.itemInMainHand.amount shouldBe 1

        paper.callEvent(loot(LootDropEvent.Cause.MINING_LUCK, "survival")).isCancelled shouldBe false
        granted shouldBe 2
        player.inventory.itemInMainHand.amount shouldBe 2
    }

    test("settings changes and close take effect without retaining an old world snapshot") {
        install()
        paper.callEvent(xp()).isCancelled shouldBe true
        worlds = emptySet()
        paper.callEvent(xp()).isCancelled shouldBe false
        worlds = setOf("rc_atelier_compact_mine")
        guard!!.close()
        paper.callEvent(xp()).isCancelled shouldBe false
        paper.callEvent(loot(LootDropEvent.Cause.MINING_LUCK)).isCancelled shouldBe false
    }

    test("optional bootstrap loads and exposes methods with the entire AuraSkills API absent") {
        withoutAuraSkillsApi().use { loader ->
            val bootstrap = Class.forName(MineAuraSkillsGuard::class.java.name, true, loader)
            val instance = bootstrap.getField("INSTANCE").get(null)
            val install = bootstrap.getDeclaredMethod("install", Plugin::class.java, Function0::class.java)
            install.invoke(instance, plugin, { worlds }) shouldBe null
        }
    }

    test("an installed provider with an unsupported API fails registration instead of enabling unguarded mining") {
        paper.createSimplePlugin("AuraSkills")
        withoutAuraSkillsApi().use { loader ->
            val bootstrap = Class.forName(MineAuraSkillsGuard::class.java.name, true, loader)
            val instance = bootstrap.getField("INSTANCE").get(null)
            val install = bootstrap.getDeclaredMethod("install", Plugin::class.java, Function0::class.java)
            val failure = shouldThrow<InvocationTargetException> { install.invoke(instance, plugin, { worlds }) }.cause
            (failure is IllegalStateException) shouldBe true
            failure!!.message!! shouldContain "native mine rewards cannot be guarded"
        }
    }
})

/** An actual missing dependency, rather than a provider-presence flag on a classpath containing its API. */
private fun withoutAuraSkillsApi(): URLClassLoader = object : URLClassLoader(
    arrayOf(MineAuraSkillsGuard::class.java.protectionDomain.codeSource.location),
    MineAuraSkillsGuard::class.java.classLoader,
) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith("dev.aurelium.auraskills.")) throw ClassNotFoundException(name)
        if (!name.startsWith("ru.ruscrafting.farms.paper.integration.MineAuraSkills")) return super.loadClass(name, resolve)
        return synchronized(getClassLoadingLock(name)) {
            (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
        }
    }
}
