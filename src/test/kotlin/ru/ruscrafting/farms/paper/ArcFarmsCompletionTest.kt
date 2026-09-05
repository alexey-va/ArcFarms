package ru.ruscrafting.farms.paper

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminHandler
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminRegistry

class ArcFarmsCompletionTest : FunSpec({
    test("completion preserves argument routing order permissions and case insensitive prefixes") {
        val mine = mockk<WorksiteAdminHandler> {
            every { kind } returns ActivityKind.MINE
            every { zoneIds() } returns listOf("OldMine")
            every { incidentIds() } returns listOf("collapse", "gas")
        }
        val service = mockk<ArcFarmsService> {
            every { farmZoneIds() } returns listOf("FarmAlpha", "FarmBeta")
            every { farmOrderIds("FarmAlpha") } returns listOf("wheat", "carrot")
            every { adminFarmRouteNames("FarmAlpha") } returns listOf("main", "market")
            every { worksiteAdmins } returns WorksiteAdminRegistry(listOf(mine))
        }
        val sender = mockk<CommandSender> { every { hasPermission("arcfarms.admin") } returns true }
        val command = mockk<Command>()
        val handler = ArcFarmsCommand(service, mockk<ArcFarmsLocale>(), mockk<ArcFarmsMenu>()) { Result.success(Unit) }
        val cases = listOf(
            emptyList<String>() to emptyList(),
            listOf("") to listOf("status", "top", "travel", "reload", "admin", "debug"),
            listOf("TrAvEl", "M") to listOf("mine"),
            listOf("top", "") to listOf("farm", "lumber", "mine"),
            listOf("unknown", "") to emptyList(),
            listOf("admin", "WORK") to listOf("worksite"),
            listOf("debug", "FaRmB") to listOf("FarmBeta"),
            listOf("ADMIN", "point", "FARM") to listOf("FarmAlpha", "FarmBeta"),
            listOf("admin", "reset-farm", "he") to listOf("help"),
            listOf("admin", "edit", "") to listOf("help"),
            listOf("admin", "worksite", "") to listOf("lumber", "mine", "help"),
            listOf("admin", "worksite", "MiNe", "O") to listOf("OldMine"),
            listOf("admin", "worksite", "invalid", "") to listOf("help"),
            listOf("admin", "point", "FarmAlpha", "processing-input") to
                listOf("processing-input", "processing-input-2", "processing-input-3", "processing-input-4"),
            listOf("admin", "event", "FarmAlpha", "TOR") to listOf("tornado"),
            listOf("admin", "stage", "FarmAlpha", "PRE") to listOf("preparation"),
            listOf("admin", "route", "FarmAlpha", "") to listOf("start", "finish", "cancel", "status", "clear", "help"),
            listOf("admin", "blockreset", "FarmAlpha", "") to listOf("status", "help"),
            listOf("admin", "backup", "FarmAlpha", "") to listOf("save", "list", "status", "restore", "help"),
            listOf("admin", "next", "FarmAlpha", "") to listOf("help"),
            listOf("debug", "FarmAlpha", "contract", "C") to listOf("carrot"),
            listOf("debug", "FarmAlpha", "give", "") to listOf("tool", "seeds", "water", "archery"),
            listOf("admin", "route", "FarmAlpha", "REMOVE", "MA") to listOf("main", "market"),
            listOf("admin", "point", "FarmAlpha", "tool", "") to listOf("clear", "remove", "help"),
            listOf("admin", "backup", "FarmAlpha", "restore", "") to listOf("help"),
            listOf("admin", "worksite", "mine", "OldMine", "") to listOf("status", "start", "incident", "reindex", "help"),
            listOf("admin", "worksite", "mine", "OldMine", "incident", "G") to listOf("gas"),
            listOf("admin", "worksite", "mine", "OldMine", "reindex", "") to listOf("start", "tick", "cancel"),
            listOf("admin", "worksite", "invalid", "OldMine", "incident", "") to emptyList(),
            listOf("admin", "point", "FarmAlpha", "tool", "clear", "extra", "") to emptyList(),
        )
        cases.forEach { (args, expected) ->
            withClue(args) { handler.onTabComplete(sender, command, "arcfarms", args.toTypedArray()) shouldBe expected }
        }

        every { sender.hasPermission("arcfarms.admin") } returns false
        handler.onTabComplete(sender, command, "arcfarms", arrayOf("")) shouldBe listOf("status", "top", "travel")
        cases.filter { it.first.firstOrNull()?.lowercase() in setOf("admin", "debug") }.forEach { (args, _) ->
            withClue(args) { handler.onTabComplete(sender, command, "arcfarms", args.toTypedArray()) shouldBe emptyList() }
        }
        verify(exactly = 0) { service.reportCommandFailure(any(), any(), any()) }
    }
})
