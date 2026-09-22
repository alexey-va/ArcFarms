package ru.ruscrafting.farms.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import java.nio.file.Files

class MineDrivePersistenceTest : FunSpec({
    test("rail route and interrupted service roundtrip through the shared repository") {
        val root=Files.createTempDirectory("mine-rail-roundtrip")
        val rail=MineRailProgress(route=(0..14).map { it*33+16 },service=MineRailService(0,MineRailServiceKind.JAM,2))
        rail.validate(1485)
        val work=MineWorkingState(MineWorkingPlacement(WorksitePosition("mine",30,64,50),2,"upper"),
            MineWorkingStage.EXCAVATE,drive=MineDriveProgress(checkpoint=14*33+16,rail=rail))
        val expected=ArcFarmsState(mines=mapOf("shafts" to MineShiftState(engineVersion=2,
            phase=MinePhase.INCIDENT,sequence=7,resumePhase=MinePhase.MINING,
            incident=MineIncidentState(MineIncidentType.RAIL_EXTENSION,required=93,objectiveNonce=9,working=work))))
        ArcFarmsStateRepository(root).use { it.saveAsync(expected).get() }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    }
    test("drilled cells lamps and carrier checkpoint survive the real state repository") {
        val root = Files.createTempDirectory("mine-drive-roundtrip")
        val working = MineWorkingState(MineWorkingPlacement(WorksitePosition("mine",30,64,50),1,"upper"),
            MineWorkingStage.EXCAVATE, drive = MineDriveProgress(setOf(42,59,60),setOf(42),59,275f,prepared=setOf(42,59,60,61,62)))
        val expected = ArcFarmsState(mines = mapOf("shafts" to MineShiftState(engineVersion=2,
            phase=MinePhase.INCIDENT, sequence=7, resumePhase=MinePhase.MINING,
            incident=MineIncidentState(MineIncidentType.TUNNEL_DRIVE,required=93,objectiveNonce=9,working=working))))
        ArcFarmsStateRepository(root).use { it.saveAsync(expected).get() }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
        // Saves from before the excavation buffer have no prepared property.
        val path = root.resolve("data/state.json")
        val json = Files.readString(path)
        Files.writeString(path, json.replace(Regex(",?\\s*\"prepared\"\\s*:\\s*\\[[^]]*]"), ""))
        val legacyWorking = working.copy(drive = working.drive!!.copy(prepared=emptySet()))
        val legacyMine = expected.mines.getValue("shafts").let { it.copy(incident=it.incident!!.copy(working=legacyWorking)) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected.copy(mines=mapOf("shafts" to legacyMine)) }
    }
})
