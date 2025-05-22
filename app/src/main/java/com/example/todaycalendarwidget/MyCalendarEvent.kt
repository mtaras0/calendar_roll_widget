package com.example.todaycalendarwidget

import java.time.ZonedDateTime

data class MyCalendarEvent(
    val id: String?,
    val summary: String?,
    val startTime: ZonedDateTime?,
    val endTime: ZonedDateTime?,
    val isAllDay: Boolean
)
