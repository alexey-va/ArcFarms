package ru.ruscrafting.farms.paper.farm.care

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Entity
import org.bukkit.entity.FishHook
import org.bukkit.entity.Mob
import org.bukkit.event.entity.EntityDamageByEntityEvent

class FarmRescueHookAttachmentTest : FunSpec({
    test("ditch rescue animals expose native hook collision") {
        val animal = mockk<Mob>(relaxed = true)

        FarmRescueHookAttachment.configureAnimal(animal, enabled = true)

        verify(exactly = 1) { animal.isInvulnerable = false }
        verify(exactly = 1) { animal.isCollidable = true }
    }

    test("validated rescue hit prevents damage and attaches the hook") {
        val hook = mockk<FishHook>(relaxed = true)
        val animal = mockk<Entity>(relaxed = true)
        val event = mockk<EntityDamageByEntityEvent>(relaxed = true)
        every { event.damager } returns hook
        every { event.entity } returns animal

        FarmRescueHookAttachment.attach(event) shouldBe true

        verify(exactly = 1) { event.isCancelled = true }
        verify(exactly = 1) { hook.hookedEntity = animal }
    }
})
