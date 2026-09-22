package ru.ruscrafting.farms.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.minimessage.MiniMessage
import ru.arc.config.Config
import java.nio.file.Files
import java.nio.file.Path

class MineLastDescentLocaleTest : FunSpec({
    test("v4 controls and stages parse and match all declared locale mirrors") {
        val folder=Files.createTempDirectory("last-descent-locale")
        Files.createDirectories(folder.resolve("lang"))
        val keys=listOf("brake-jam","tension","brake-release","cell-source","cell-socket","intake","prime","pump-start","down","up")
            .map { "control.descent-$it" } + listOf("middle","counterweights","power_cells","bottom","core_valves","engine")
            .map { "stage.descent_v4_$it" }
        val ops=System.getProperty("ruscrafting.opsRoot")?.let(Path::of)
        for(lang in listOf("ru","en")) {
            requireNotNull(javaClass.classLoader.getResourceAsStream("lang/$lang.yml")).use {
                Files.copy(it,folder.resolve("lang/$lang.yml"))
            }
            val bundled=Config(folder,"lang/$lang.yml")
            for(key in keys) {
                val text=bundled.string("mine.expedition.$key")
                text.isNotBlank() shouldBe true
                text.contains("<prefix>") shouldBe false
                MiniMessage.builder().strict(true).build().deserialize(text)
                for(profile in if(ops == null) emptyList() else listOf("classic","classic_survival","parkour")) {
                    Config(requireNotNull(ops).resolve("$profile/plugins/ArcFarms"),"lang/$lang.yml")
                        .string("mine.expedition.$key") shouldBe text
                }
            }
        }
    }
})
