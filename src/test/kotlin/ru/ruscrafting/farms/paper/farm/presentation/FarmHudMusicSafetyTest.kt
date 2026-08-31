package ru.ruscrafting.farms.paper.farm.presentation

import io.kotest.core.spec.style.FunSpec
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmScoreboardPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.harvest.FarmHarvestController
import ru.ruscrafting.farms.paper.farm.incident.route.FarmFoodDeliveryIncident
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController

class FarmHudMusicSafetyTest : FunSpec({
    test("leaving the farm stops the whole music channel even without a cached session") {
        val player = mockk<Player>(relaxed = true)
        val hud = FarmHudController(
            settings = { mockk<ArcFarmsConfig>(relaxed = true) },
            locale = mockk<ArcFarmsLocale>(relaxed = true),
            debug = ArcFarmsDebug({ false }) {},
            access = mockk<WorksiteAccessPort>(relaxed = true),
            audience = mockk<WorksiteAudiencePort>(relaxed = true),
            tasks = mockk<WorksiteTaskPort>(relaxed = true),
            delivery = mockk<FarmDeliveryController>(relaxed = true),
            foodDelivery = mockk<FarmFoodDeliveryIncident>(relaxed = true),
            actionIncidents = mockk(relaxed = true),
            special = mockk<FarmSpecialIncidentController>(relaxed = true),
            harvest = mockk<FarmHarvestController>(relaxed = true),
            clock = { 0L },
            scoreboards = mockk<FarmScoreboardPort>(relaxed = true),
        )

        hud.stopMusic(player, "left_zone")

        verify(exactly = 1) { player.stopSound(SoundCategory.MUSIC) }
    }
})
