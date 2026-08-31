package ru.ruscrafting.farms.domain.enterprise

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class WorksiteBusinessWeekTest : FunSpec({
    test("the configured boundary instant starts a new business week") {
        val zone = ZoneId.of("Europe/Moscow")
        val boundaryDate = LocalDate.parse("2026-08-30")
        val boundary = boundaryDate
            .atTime(LocalTime.of(20, 0))
            .atZone(zone)
            .toInstant()

        worksiteBusinessWeekStartEpochDay(
            boundary.minusMillis(1).toEpochMilli(),
            zone,
            DayOfWeek.SUNDAY,
            LocalTime.of(20, 0),
        ) shouldBe boundaryDate.minusWeeks(1).toEpochDay()
        worksiteBusinessWeekStartEpochDay(
            boundary.toEpochMilli(),
            zone,
            DayOfWeek.SUNDAY,
            LocalTime.of(20, 0),
        ) shouldBe boundaryDate.toEpochDay()
    }

    test("the boundary honors the supplied zone day and time") {
        val zone = ZoneId.of("Asia/Tokyo")
        val boundaryDate = LocalDate.parse("2026-09-02")
        val boundaryTime = LocalTime.of(6, 15)
        val afterBoundary = boundaryDate
            .atTime(boundaryTime.plusMinutes(1))
            .atZone(zone)
            .toInstant()

        worksiteBusinessWeekStartEpochDay(
            afterBoundary.toEpochMilli(),
            zone,
            DayOfWeek.WEDNESDAY,
            boundaryTime,
        ) shouldBe boundaryDate.toEpochDay()
    }
})
