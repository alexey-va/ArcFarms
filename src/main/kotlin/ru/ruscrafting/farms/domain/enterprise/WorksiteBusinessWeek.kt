package ru.ruscrafting.farms.domain.enterprise

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal fun worksiteBusinessWeekStartEpochDay(
    timestampMillis: Long,
    zoneId: ZoneId,
    weekStartDay: DayOfWeek,
    weekStartTime: LocalTime,
): Long {
    val current = Instant.ofEpochMilli(timestampMillis).atZone(zoneId)
    val candidateDate = current.toLocalDate().with(TemporalAdjusters.previousOrSame(weekStartDay))
    val candidate = candidateDate.atTime(weekStartTime).atZone(zoneId)
    return if (current.isBefore(candidate)) candidateDate.minusWeeks(1).toEpochDay() else candidateDate.toEpochDay()
}
