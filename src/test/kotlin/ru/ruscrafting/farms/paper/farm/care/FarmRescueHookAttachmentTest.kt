package ru.ruscrafting.farms.paper.farm.care

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Entity
import org.bukkit.entity.FishHook
import org.bukkit.entity.Mob
import org.bukkit.event.entity.ProjectileHitEvent

class FarmRescueHookAttachmentTest : FunSpec({
    test("ditch rescue animals expose native hook collision") {
        val animal = mockk<Mob>(relaxed = true)

        FarmRescueHookAttachment.configureAnimal(animal, enabled = true)

        verify(exactly = 1) { animal.isInvulnerable = false }
        verify(exactly = 1) { animal.isCollidable = true }
    }

    test("validated rescue hit prevents vanilla collision and attaches the hook") {
        val hook = mockk<FishHook>(relaxed = true)
        val animal = mockk<Entity>(relaxed = true)
        val event = ProjectileHitEvent(hook, animal)

        FarmRescueHookAttachment.attach(event) shouldBe true

        event.isCancelled shouldBe true
        verify(exactly = 1) { hook.hookedEntity = animal }
    }
})
