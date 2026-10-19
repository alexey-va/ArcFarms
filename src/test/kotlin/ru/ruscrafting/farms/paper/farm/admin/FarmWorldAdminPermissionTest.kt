package ru.ruscrafting.farms.paper.farm.admin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture

class FarmWorldAdminPermissionTest : FunSpec({
    test("edit requires permission and revocation permanently clears the active session") {
        val player = mockk<Player>(relaxed = true) {
            every { uniqueId } returns UUID.randomUUID()
        }
        val audience = mockk<WorksiteAudiencePort>(relaxed = true)
        val admin = permissionAdmin(audience = audience)
        try {
            admin.toggleEdit(player) shouldBe null
            admin.isEditing(player) shouldBe false
            verify(exactly = 1) { audience.sendChat(player, MessageKey.NO_PERMISSION, any()) }

            every { player.hasPermission("arcfarms.bypass") } returns true
            admin.toggleEdit(player) shouldBe true
            admin.isEditing(player) shouldBe true
            every { player.hasPermission("arcfarms.bypass") } returns false
            admin.isEditing(player) shouldBe false
            verify(exactly = 1) { audience.sendActionBar(player, MessageKey.ADMIN_EDIT_DISABLED, any()) }
            every { player.hasPermission("arcfarms.bypass") } returns true
            admin.isEditing(player) shouldBe false
            admin.toggleEdit(player) shouldBe true
            admin.toggleEdit(player) shouldBe false

            every { player.hasPermission("arcfarms.bypass") } returns false
            every { player.hasPermission("arcfarms.admin") } returns true
            admin.toggleEdit(player) shouldBe true
            admin.isEditing(player) shouldBe true
        } finally { admin.close() }
    }

    test("edit hints are permission gated and use the existing interaction cooldown") {
        val player = mockk<Player>(relaxed = true) {
            every { uniqueId } returns UUID.randomUUID()
        }
        val access = mockk<WorksiteAccessPort>(relaxed = true)
        val audience = mockk<WorksiteAudiencePort>(relaxed = true)
        val admin = permissionAdmin(access = access, audience = audience)
        try {
            admin.remindEdit(player)
            verify(exactly = 0) { access.allowInteraction(any(), any()) }
            every { player.hasPermission("arcfarms.bypass") } returns true
            val hintKey = "farm-edit-hint:${player.uniqueId}"
            every { access.allowInteraction(hintKey, 5_000L) } returnsMany listOf(true, false)
            admin.remindEdit(player)
            admin.remindEdit(player)
            verify(exactly = 1) { audience.sendChat(player, MessageKey.ADMIN_EDIT_HINT, any()) }

            admin.toggleEdit(player)
            admin.remindEdit(player)
            verify(exactly = 2) { access.allowInteraction(any(), any()) }
            verify(exactly = 1) { audience.sendChat(player, MessageKey.ADMIN_EDIT_HINT, any()) }
        } finally { admin.close() }
    }

    test("revoked editors do not keep new farm shifts paused") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("FarmEditPermissionTest")
            val player = paper.server.addPlayer()
            val other = paper.server.addPlayer()
            val permission = player.addAttachment(plugin, "arcfarms.bypass", true)
            val admin = permissionAdmin(plugin = plugin)
            try {
                admin.toggleEdit(player) shouldBe true
                admin.anyEditing() shouldBe true
                admin.anotherEditor(other) shouldBe true
                permission.setPermission("arcfarms.bypass", false)
                admin.anyEditing() shouldBe false
                admin.anotherEditor(other) shouldBe false
                permission.setPermission("arcfarms.bypass", true)
                admin.isEditing(player) shouldBe false
            } finally { admin.close() }
        }
    }
})

private fun permissionAdmin(
    plugin: Plugin = mockk(relaxed = true),
    access: WorksiteAccessPort = mockk(relaxed = true),
    audience: WorksiteAudiencePort = mockk(relaxed = true),
) = FarmWorldAdminService(
    plugin = plugin,
    dataFolder = Path.of("unused-farm-edit-test"),
    locale = mockk(relaxed = true),
    debug = mockk(relaxed = true),
    access = access,
    audience = audience,
    ledger = mockk(relaxed = true),
    registry = mockk(relaxed = true),
    fixedCrops = mockk(relaxed = true),
    fixedCropJournal = mockk(relaxed = true),
    care = mockk(relaxed = true),
    pests = mockk(relaxed = true),
    delivery = mockk(relaxed = true),
    enterprise = mockk(relaxed = true),
    special = mockk(relaxed = true),
    transitions = mockk(relaxed = true),
    runtimes = { emptyList() },
    paused = { false },
    setPaused = { _, _ -> true },
    persistAsync = { CompletableFuture.completedFuture(Unit) },
    clock = { 1_000L },
)
