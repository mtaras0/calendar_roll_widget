package com.example.todaycalendarwidget

import com.google.api.client.util.DateTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object DateTimeUtils {

    fun getStartOfTodayRFC3339(): String {
        val today = LocalDate.now()
        val startOfDay = today.atStartOfDay(ZoneId.systemDefault())
        return formatToRFC3339(startOfDay)
    }

    fun getEndOfTodayRFC3339(): String {
        val today = LocalDate.now()
        val endOfDay = today.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault())
        return formatToRFC3339(endOfDay)
    }

    // Helper to convert Google's DateTime to a more usable ZonedDateTime
    fun convertGoogleDateTime(googleDateTime: com.google.api.services.calendar.model.EventDateTime?): ZonedDateTime? {
        if (googleDateTime == null) return null

        return if (googleDateTime.dateTime != null) {
            // This is a specific point in time
            ZonedDateTime.parse(googleDateTime.dateTime.toStringRfc3339())
        } else if (googleDateTime.date != null) {
            // This is an all-day event, parse the date and assume start of day in system default timezone
            val localDate = LocalDate.parse(googleDateTime.date.toString())
            localDate.atStartOfDay(ZoneId.systemDefault())
        } else {
            null
        }
    }


    private fun formatToRFC3339(zonedDateTime: ZonedDateTime): String {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zonedDateTime)
    }

    // For Google API Client's DateTime object
    fun getStartOfTodayDateTime(): DateTime {
        val zonedDateTime = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
        return DateTime(zonedDateTime.toInstant().toEpochMilli())
    }

    fun getEndOfTodayDateTime(): DateTime {
        val zonedDateTime = LocalDate.now().atTime(LocalTime.MAX).atZone(ZoneId.systemDefault())
        return DateTime(zonedDateTime.toInstant().toEpochMilli())
    }
}
