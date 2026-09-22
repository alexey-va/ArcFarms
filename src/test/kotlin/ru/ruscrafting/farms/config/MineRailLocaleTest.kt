package ru.ruscrafting.farms.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files
import java.nio.file.Path

class MineRailLocaleTest:FunSpec({
    test("rail guidance and equipment are complete and mirrored on every tracked profile") {
        val temp=Files.createTempDirectory("mine-rail-locale")
        Files.createDirectories(temp.resolve("lang"))
        val keys=listOf("rail-controls","rail-complete","rail-jammed","rail-empty",
            "rail-service-jam-target","rail-service-cassette-target","rail-service-feeder-target","rail-cassette-carried","rail-service-done",
            "stage.rail_goal","item.rail_cassette")
        val ops=Path.of(requireNotNull(System.getProperty("ruscrafting.opsRoot")))
        for(lang in listOf("ru","en")) {
            requireNotNull(javaClass.classLoader.getResourceAsStream("lang/$lang.yml")).use {
                Files.copy(it,temp.resolve("lang/$lang.yml"))
            }
            val bundled=Config(temp,"lang/$lang.yml")
            for(key in keys) {
                val expected=bundled.string("mine.working.$key")
                expected.isNotBlank() shouldBe true
                for(profile in listOf("classic","classic_survival","parkour"))
                    Config(ops.resolve("$profile/plugins/ArcFarms"),"lang/$lang.yml").string("mine.working.$key") shouldBe expected
            }
        }
    }
})
