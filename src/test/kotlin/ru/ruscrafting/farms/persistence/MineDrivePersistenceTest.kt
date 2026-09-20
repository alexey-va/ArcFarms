package ru.ruscrafting.farms.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import java.nio.file.Files

class MineDrivePersistenceTest : FunSpec({
    test("drilled cells lamps and carrier checkpoint survive the real state repository") {
        val root = Files.createTempDirectory("mine-drive-roundtrip")
        val working = MineWorkingState(MineWorkingPlacement(WorksitePosition("mine",30,64,50),1,"upper"),
            MineWorkingStage.EXCAVATE, drive = MineDriveProgress(setOf(42,59,60),setOf(42),59,95f))
        val expected = ArcFarmsState(mines = mapOf("shafts" to MineShiftState(engineVersion=2,
            phase=MinePhase.INCIDENT, sequence=7, resumePhase=MinePhase.MINING,
            incident=MineIncidentState(MineIncidentType.TUNNEL_DRIVE,required=93,objectiveNonce=9,working=working))))
        ArcFarmsStateRepository(root).use { it.saveAsync(expected).get() }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }
})
