package ru.ruscrafting.farms.paper.farm.care

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmSeederStage
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.block

/** Stateless visual and audio vocabulary shared by every farm care story. */
internal class FarmCarePresentation(private val settings: () -> ArcFarmsConfig) {
    fun feedback(player: Player, location: Location, role: FarmCareRole, completed: Boolean) {
        if (settings().particles) {
            player.spawnParticle(
                Particle.DUST,
                location.clone().add(0.0, 0.55, 0.0),
                if (completed) 7 else 4,
                0.35, 0.25, 0.35, 0.0,
                Particle.DustOptions(if (completed) SUCCESS_COLOR else CARE_COLOR, if (completed) 1.35f else 1.05f),
            )
        }
        if (settings().sounds) player.playSound(location, feedbackSound(role), 0.7f, if (completed) 1.2f else 0.95f)
    }

    fun machineSwath(runtime: FarmRuntime, plots: Collection<FarmPlotPosition>, stage: FarmSeederStage) {
        if (!settings().particles) return
        val color = if (stage == FarmSeederStage.TILLING) TILL_COLOR else PLANT_COLOR
        plots.asSequence().filterIndexed { index, _ -> index % 4 == 0 }.forEach { position ->
            val above = position.block()?.getRelative(org.bukkit.block.BlockFace.UP) ?: return@forEach
            runtime.region.world.spawnParticle(
                Particle.DUST, above.location.toCenterLocation().add(0.0, 0.75, 0.0),
                2, 0.16, 0.16, 0.16, 0.0, Particle.DustOptions(color, 0.95f),
            )
        }
    }

    fun color(role: FarmCareRole): Color = when (role) {
        FarmCareRole.SEEDER_HORSE, FarmCareRole.SEEDER_WAYPOINT -> PLANT_COLOR
        FarmCareRole.WEED_ROOT -> Color.fromRGB(194, 137, 70)
        FarmCareRole.VALVE -> Color.fromRGB(79, 195, 247)
        FarmCareRole.HIVE, FarmCareRole.FLOWER_PATCH -> AMBER_COLOR
        FarmCareRole.COVER_ANCHOR -> Color.fromRGB(154, 140, 255)
        FarmCareRole.SCARECROW -> DANGER_COLOR
        FarmCareRole.ANIMAL, FarmCareRole.PEN -> SUCCESS_COLOR
        FarmCareRole.DISEASED_CROP -> Color.fromRGB(190, 82, 214)
        FarmCareRole.MOLE_MOUND -> Color.fromRGB(151, 105, 72)
        FarmCareRole.APPLE -> Color.fromRGB(235, 67, 53)
    }

    fun startSound(type: FarmCareType): Sound = when (type) {
        FarmCareType.SEEDER -> Sound.ENTITY_HORSE_SADDLE
        FarmCareType.WEEDS -> Sound.BLOCK_ROOTED_DIRT_BREAK
        FarmCareType.IRRIGATION -> Sound.BLOCK_CHAIN_PLACE
        FarmCareType.POLLINATION -> Sound.ENTITY_BEE_POLLINATE
        FarmCareType.STORM_COVERS -> Sound.WEATHER_RAIN_ABOVE
        FarmCareType.SCARECROWS -> Sound.ENTITY_PARROT_FLY
        FarmCareType.ANIMAL_RESCUE -> Sound.ENTITY_CHICKEN_AMBIENT
        FarmCareType.DISEASE -> Sound.BLOCK_SCULK_SPREAD
        FarmCareType.MOLES -> Sound.ENTITY_RABBIT_JUMP
        FarmCareType.APPLE_HARVEST -> Sound.BLOCK_CHERRY_LEAVES_BREAK
        FarmCareType.DITCH_RESCUE -> Sound.ENTITY_CHICKEN_AMBIENT
    }

    fun stageId(type: FarmCareType): String = when (type) {
        FarmCareType.SEEDER -> "seeder"
        FarmCareType.WEEDS -> "weeds"
        FarmCareType.IRRIGATION -> "irrigation"
        FarmCareType.POLLINATION -> "pollination"
        FarmCareType.STORM_COVERS -> "covers"
        FarmCareType.SCARECROWS -> "scarecrows"
        FarmCareType.ANIMAL_RESCUE -> "animals"
        FarmCareType.DISEASE -> "disease"
        FarmCareType.MOLES -> "moles"
        FarmCareType.APPLE_HARVEST -> "apples"
        FarmCareType.DITCH_RESCUE -> "ditch-animals"
    }

    fun scale(display: ItemDisplay, scale: Float) {
        val current = display.transformation
        display.transformation = Transformation(
            current.translation, AxisAngle4f(current.leftRotation), Vector3f(scale, scale, scale), AxisAngle4f(current.rightRotation),
        )
    }

    private fun feedbackSound(role: FarmCareRole): Sound = when (role) {
        FarmCareRole.SEEDER_HORSE, FarmCareRole.SEEDER_WAYPOINT -> Sound.ENTITY_HORSE_STEP_WOOD
        FarmCareRole.WEED_ROOT, FarmCareRole.MOLE_MOUND -> Sound.BLOCK_ROOTED_DIRT_BREAK
        FarmCareRole.VALVE -> Sound.BLOCK_CHAIN_PLACE
        FarmCareRole.HIVE, FarmCareRole.FLOWER_PATCH -> Sound.ENTITY_BEE_POLLINATE
        FarmCareRole.COVER_ANCHOR -> Sound.BLOCK_WOOL_PLACE
        FarmCareRole.SCARECROW -> Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE
        FarmCareRole.ANIMAL, FarmCareRole.PEN, FarmCareRole.APPLE -> Sound.ENTITY_ITEM_PICKUP
        FarmCareRole.DISEASED_CROP -> Sound.BLOCK_BREWING_STAND_BREW
    }

    companion object {
        val TILL_COLOR: Color = Color.fromRGB(255, 173, 66)
        val PLANT_COLOR: Color = Color.fromRGB(199, 120, 255)
        val AMBER_COLOR: Color = Color.fromRGB(255, 200, 87)
        val DANGER_COLOR: Color = Color.fromRGB(255, 95, 109)
        val SUCCESS_COLOR: Color = Color.fromRGB(85, 217, 139)
        val CARE_COLOR: Color = Color.fromRGB(84, 201, 185)
    }
}
