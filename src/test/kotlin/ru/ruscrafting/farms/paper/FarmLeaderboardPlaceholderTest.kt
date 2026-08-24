package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmLeaderboardPlaceholderTest : FunSpec({
    test("farm scoreboard placeholders stay within the fixed TAB surface") {
        FarmScoreboardPlaceholder.parse("farm_active") shouldBe FarmScoreboardPlaceholder.Active
        FarmScoreboardPlaceholder.parse("farm_title") shouldBe FarmScoreboardPlaceholder.Title
        FarmScoreboardPlaceholder.parse("farm_line_1") shouldBe FarmScoreboardPlaceholder.Line(1)
        FarmScoreboardPlaceholder.parse("farm_line_15") shouldBe FarmScoreboardPlaceholder.Line(15)
        FarmScoreboardPlaceholder.parse("farm_line_0") shouldBe null
        FarmScoreboardPlaceholder.parse("farm_line_16") shouldBe null
    }

    test("leaderboard placeholders expose personal and ranked farm fields") {
        FarmLeaderboardPlaceholder.parse("farm_score") shouldBe FarmLeaderboardPlaceholder.PersonalScore
        FarmLeaderboardPlaceholder.parse("farm_rank") shouldBe FarmLeaderboardPlaceholder.PersonalRank
        FarmLeaderboardPlaceholder.parse("farm_top_1_name") shouldBe
            FarmLeaderboardPlaceholder.Top(1, FarmLeaderboardPlaceholder.Field.NAME)
        FarmLeaderboardPlaceholder.parse("farm_top_50_skin") shouldBe
            FarmLeaderboardPlaceholder.Top(50, FarmLeaderboardPlaceholder.Field.SKIN)
        FarmLeaderboardPlaceholder.parse("farm_top_2_uuid") shouldBe
            FarmLeaderboardPlaceholder.Top(2, FarmLeaderboardPlaceholder.Field.UUID)
        FarmLeaderboardPlaceholder.parse("farm_top_3_score") shouldBe
            FarmLeaderboardPlaceholder.Top(3, FarmLeaderboardPlaceholder.Field.SCORE)
        FarmLeaderboardPlaceholder.parse("farm_weekly_score") shouldBe
            FarmLeaderboardPlaceholder.PersonalWeeklyScore
        FarmLeaderboardPlaceholder.parse("farm_weekly_rank") shouldBe
            FarmLeaderboardPlaceholder.PersonalWeeklyRank
        FarmLeaderboardPlaceholder.parse("farm_weekly_top_4_name") shouldBe
            FarmLeaderboardPlaceholder.WeeklyTop(4, FarmLeaderboardPlaceholder.Field.NAME)
        FarmLeaderboardPlaceholder.parse("farm_weekly_top_50_score") shouldBe
            FarmLeaderboardPlaceholder.WeeklyTop(50, FarmLeaderboardPlaceholder.Field.SCORE)
    }

    test("leaderboard placeholder parser rejects unbounded or unknown requests") {
        FarmLeaderboardPlaceholder.parse("farm_top_0_name") shouldBe null
        FarmLeaderboardPlaceholder.parse("farm_top_51_name") shouldBe null
        FarmLeaderboardPlaceholder.parse("farm_top_1_texture") shouldBe null
        FarmLeaderboardPlaceholder.parse("mine_top_1_name") shouldBe null
        FarmLeaderboardPlaceholder.parse("farm_weekly_top_0_name") shouldBe null
        FarmLeaderboardPlaceholder.parse("farm_weekly_top_51_score") shouldBe null
    }
})
