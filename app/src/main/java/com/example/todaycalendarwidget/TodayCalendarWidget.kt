package com.example.todaycalendarwidget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import com.example.todaycalendarwidget.ui.theme.TodayCalendarWidgetTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// --- Constants ---
private const val START_HOUR = 9
private const val END_HOUR = 24 // Represents 00:00 of the next day for a full 24h cycle in display logic
val HOUR_HEIGHT: Dp = 60.dp
val TIME_COLUMN_WIDTH: Dp = 60.dp // Width for displaying "09:00" etc.
private val EVENT_AREA_HORIZONTAL_PADDING = 8.dp // Padding within the event area
private val EVENT_HORIZONTAL_MARGIN = 4.dp // Margin around each event block
private val EVENT_TEXT_PADDING = 4.dp // Padding for text within an event block

@Composable
fun TodayCalendarWidget(
    events: List<MyCalendarEvent> = emptyList(),
    debugCurrentTime: ZonedDateTime? = null, // For previewing specific times
    modifier: Modifier = Modifier
) {
    var currentTime by remember { mutableStateOf(debugCurrentTime ?: ZonedDateTime.now()) }

    LaunchedEffect(Unit) { // Runs once when the composable enters the composition
        if (debugCurrentTime == null) { // Only run the live update if not in debug mode
            while (true) {
                currentTime = ZonedDateTime.now()
                delay(60000L) // Update every minute
            }
        }
    }

    val scrollState = rememberScrollState()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val hourHeightPx = with(density) { HOUR_HEIGHT.toPx() }
    // val timeColumnWidthPx = with(density) { TIME_COLUMN_WIDTH.toPx() } // Not directly needed for Canvas width here

    // Calculate total height for the content within the scrollable area
    val totalHours = END_HOUR - START_HOUR
    val totalContentHeight = HOUR_HEIGHT * totalHours

    Box(modifier = modifier) { // Allow parent to control size
        Row(
            modifier = Modifier
                .fillMaxSize() // Fill the size provided by the parent
                .verticalScroll(scrollState)
        ) {
            // --- Time Column (Left Side) ---
            // This Box will be as tall as the scrollable content.
            Box(
                modifier = Modifier
                    .width(TIME_COLUMN_WIDTH)
                    .height(totalContentHeight) // Explicitly set height to match timeline
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawTimeLabels(
                        drawScope = this,
                        startHour = START_HOUR,
                        endHour = END_HOUR,
                        hourHeightPx = hourHeightPx,
                        textStyle = MaterialTheme.typography.labelSmall,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        textMeasurer = textMeasurer,
                        density = density
                    )
                }
            }

            // --- Hour Separator Lines & Event Area (Right Side) ---
            // This Canvas will also be as tall as the scrollable content.
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(totalContentHeight) // Explicitly set height to match timeline
            ) {
                drawHourSeparatorLines(
                    drawScope = this,
                    startHour = START_HOUR,
                    endHour = END_HOUR,
                    hourHeightPx = hourHeightPx,
                    lineColor = MaterialTheme.colorScheme.outlineVariant
                )
                // --- Event Rendering ---
                // This is where events will be drawn.
                // We'll iterate through events and draw them based on calculated positions.
                drawEvents(
                    drawScope = this,
                    events = events,
                    startHour = START_HOUR,
                    hourHeightPx = hourHeightPx,
                    eventAreaStartOffsetPx = 0f,
                    density = density,
                    textMeasurer = textMeasurer,
                    currentTime = currentTime // Pass current time to drawEvents
                )

                // --- Current Time Marker ---
                drawCurrentTimeMarker(
                    drawScope = this,
                    currentTime = currentTime,
                    startHour = START_HOUR,
                    hourHeightPx = hourHeightPx,
                    lineColor = MaterialTheme.colorScheme.error // Example color
                )
            }
        }
    }
}

private fun drawCurrentTimeMarker(
    drawScope: DrawScope,
    currentTime: ZonedDateTime,
    startHour: Int,
    hourHeightPx: Float,
    lineColor: Color
) {
    drawScope.apply {
        val currentHour = currentTime.hour
        val currentMinute = currentTime.minute

        // Only draw if current time is within the displayed range
        if (currentHour < startHour || currentHour >= END_HOUR) { // currentHour can be END_HOUR if it's exactly midnight
            if (!(currentHour == END_HOUR && currentMinute == 0)) return // Allow marker at END_HOUR for 00:00
        }


        val timeOffsetInTimelineHours = (currentHour - startHour) + (currentMinute / 60f)
        val yPosition = timeOffsetInTimelineHours * hourHeightPx

        // Ensure marker is within canvas bounds (0 to total height)
        val totalTimelineHeightPx = (END_HOUR - startHour) * hourHeightPx
        if (yPosition < 0 || yPosition > totalTimelineHeightPx) return


        drawLine(
            color = lineColor,
            start = Offset(x = 0f, y = yPosition),
            end = Offset(x = size.width, y = yPosition),
            strokeWidth = 2.dp.toPx() // Slightly thicker line
        )
        // Optional: Draw a circle at the start of the line
        drawCircle(
            color = lineColor,
            radius = 4.dp.toPx(),
            center = Offset(x = 0f, y = yPosition)
        )
    }
}


private fun drawEvents(
    drawScope: DrawScope,
    events: List<MyCalendarEvent>,
    startHour: Int,
    hourHeightPx: Float,
    eventAreaStartOffsetPx: Float,
    density: androidx.compose.ui.unit.Density,
    textMeasurer: TextMeasurer,
    currentTime: ZonedDateTime
) {
    drawScope.apply {
        val eventHorizontalMarginPx = with(density) { EVENT_HORIZONTAL_MARGIN.toPx() }
        val eventTextPaddingPx = with(density) { EVENT_TEXT_PADDING.toPx() }

        for (event in events) {
            if (event.startTime == null || event.endTime == null) continue // Skip events without proper timing

            val eventStartHour = event.startTime.hour
            val eventStartMinute = event.startTime.minute
            val eventEndHour = event.endTime.hour
            val eventEndMinute = event.endTime.minute

            // --- Calculate Y-Position (Top Offset) ---
            // Offset from the start of the timeline (e.g., 09:00 line)
            val startOffsetInTimelineHours = (eventStartHour - startHour) + (eventStartMinute / 60f)
            val topOffsetPx = startOffsetInTimelineHours * hourHeightPx

            // --- Calculate Height ---
            val endOffsetInTimelineHours = (eventEndHour - startHour) + (eventEndMinute / 60f)
            val eventDurationInHours = endOffsetInTimelineHours - startOffsetInTimelineHours
            val eventHeightPx = eventDurationInHours * hourHeightPx

            // Clip events that are partially outside the visible hour range (START_HOUR to END_HOUR)
            val effectiveTopOffsetPx = topOffsetPx.coerceAtLeast(0f)
            val effectiveBottomPx = (topOffsetPx + eventHeightPx).coerceAtMost((END_HOUR - startHour) * hourHeightPx)
            val effectiveHeightPx = (effectiveBottomPx - effectiveTopOffsetPx).coerceAtLeast(0f)

            if (effectiveHeightPx <= 0) continue // Skip events that are completely outside or have no visible height

            // --- Calculate X-Position ---
            // For now, full width within the event area, with some padding
            val eventLeftPx = eventAreaStartOffsetPx + eventHorizontalMarginPx
            val eventWidthPx = size.width - (2 * eventHorizontalMarginPx)

            // --- Draw Event Background ---
            val eventColor = MaterialTheme.colorScheme.primaryContainer
            drawRoundRect(
                color = eventColor,
                topLeft = Offset(eventLeftPx, effectiveTopOffsetPx),
                size = androidx.compose.ui.geometry.Size(eventWidthPx, effectiveHeightPx),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
            )

            // --- Draw Event Text (Summary) ---
            val availableTextWidth = eventWidthPx - (2 * eventTextPaddingPx)
            val eventSummary = event.summary ?: "Event"

            // Determine event state (past, current, future)
            val isCurrent = currentTime.isAfter(event.startTime) && currentTime.isBefore(event.endTime)
            val isPast = currentTime.isAfter(event.endTime)

            val baseEventColor = MaterialTheme.colorScheme.primaryContainer
            val baseTextColor = MaterialTheme.colorScheme.onPrimaryContainer

            val finalEventColor = when {
                isCurrent -> MaterialTheme.colorScheme.primary // Highlight current event
                isPast -> baseEventColor.copy(alpha = 0.6f) // Dim past event
                else -> baseEventColor // Future event
            }
            val finalTextColor = when {
                isCurrent -> MaterialTheme.colorScheme.onPrimary
                isPast -> baseTextColor.copy(alpha = 0.7f)
                else -> baseTextColor
            }
            val textStyle = MaterialTheme.typography.bodySmall.copy(color = finalTextColor)


            // Draw Event Background (already have this logic)
            drawRoundRect(
                color = finalEventColor,
                topLeft = Offset(eventLeftPx, effectiveTopOffsetPx),
                size = androidx.compose.ui.geometry.Size(eventWidthPx, effectiveHeightPx),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
            )

             // Draw Event Text (Summary)
            val textLayoutResult = textMeasurer.measure(
                text = eventSummary, // Use the determined text style
                style = textStyle,
                constraints = androidx.compose.ui.unit.Constraints(
                    maxWidth = availableTextWidth.toInt(),
                    maxHeight = effectiveHeightPx.toInt() - (2 * eventTextPaddingPx.toInt()) // Max height for text
                )
                // overflow = TextOverflow.Ellipsis // Handled by constraints + potential clipping
            )

            if (textLayoutResult.size.width > 0 && textLayoutResult.size.height > 0) {
                drawText(
                    textLayoutResult = textLayoutResult,
                    topLeft = Offset(
                        x = eventLeftPx + eventTextPaddingPx,
                        y = effectiveTopOffsetPx + eventTextPaddingPx
                    ),
                    // Ensure text does not draw outside the event box (clipping is implicit by drawText area)
                )
            }
        }
    }
}


private fun drawTimeLabels(
    drawScope: DrawScope,
    startHour: Int,
    endHour: Int,
    hourHeightPx: Float,
    textStyle: TextStyle,
    textColor: Color,
    textMeasurer: TextMeasurer,
    density: androidx.compose.ui.unit.Density
) {
    drawScope.apply {
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        for (hour in startHour until endHour) { // Iterate up to, but not including, endHour for labels
            val time = LocalTime.of(hour % 24, 0)
            val timeLabel = time.format(timeFormatter)
            val textLayoutResult = textMeasurer.measure(
                text = timeLabel,
                style = textStyle.copy(color = textColor)
            )

            // Calculate Y position to center text vertically within its hour slot's start
            // The label "09:00" should be near the line for 09:00.
            // Position text slightly above the line, or centered on the line's Y if preferred.
            // Here, centering text to the hour line.
            val yPositionText = (hour - startHour) * hourHeightPx - (textLayoutResult.size.height / 2f)

            // Ensure text is not drawn outside the canvas bounds (especially for the first hour)
            val clampedYPosition = yPositionText.coerceAtLeast(0f)

            val textXOffset = with(density) { 4.dp.toPx() } // Padding from the right edge

            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = Offset(
                    x = size.width - textLayoutResult.size.width - textXOffset, // Right align
                    y = clampedYPosition
                )
            )
        }
    }
}


private fun drawHourSeparatorLines(
    drawScope: DrawScope,
    startHour: Int,
    endHour: Int,
    hourHeightPx: Float,
    lineColor: Color
) {
    drawScope.apply {
        for (hour in startHour..endHour) { // Iterate up to and including END_HOUR to draw the last line
            val yPosition = (hour - startHour) * hourHeightPx
            drawLine(
                color = lineColor,
                start = Offset(x = 0f, y = yPosition),
                end = Offset(x = size.width, y = yPosition),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}


// --- Preview ---
@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun TodayCalendarWidgetPreviewWithEvents() {
    val sampleEvents = listOf(
        MyCalendarEvent(
            id = "1", summary = "Team Meeting @ Room A - Discussing Q3 Roadmap and Deliverables",
            startTime = ZonedDateTime.now().withHour(9).withMinute(0),
            endTime = ZonedDateTime.now().withHour(10).withMinute(30), // Past
            isAllDay = false
        ),
        MyCalendarEvent(
            id = "2", summary = "Lunch with Alex (Current)",
            startTime = ZonedDateTime.now().withHour(11).withMinute(0), // Current
            endTime = ZonedDateTime.now().withHour(12).withMinute(30), // Current
            isAllDay = false
        ),
        MyCalendarEvent(
            id = "3", summary = "Quick Sync (Future)",
            startTime = ZonedDateTime.now().withHour(13).withMinute(0), // Future
            endTime = ZonedDateTime.now().withHour(13).withMinute(30), // Future
            isAllDay = false
        ),
        MyCalendarEvent(
            id = "4", summary = "Doctor's Appointment (Future)",
            startTime = ZonedDateTime.now().withHour(14).withMinute(30),
            endTime = ZonedDateTime.now().withHour(15).withMinute(30),
            isAllDay = false
        ),
         MyCalendarEvent( // Event starting before START_HOUR, but ends in view (Past)
            id = "6", summary = "Early Bird (Ends in view, Past)",
            startTime = ZonedDateTime.now().withHour(START_HOUR - 2).withMinute(0), // e.g. 7am
            endTime = ZonedDateTime.now().withHour(START_HOUR + 0).withMinute(30),   // e.g. 9:30am
            isAllDay = false
        ),
        MyCalendarEvent( // Event starting in view, but ends after END_HOUR (Current)
            id = "7", summary = "Late Finish (Starts in view, Current)",
            startTime = ZonedDateTime.now().withHour(END_HOUR - 1).withMinute(0), // e.g. 16:00 if END_HOUR=17 or 23:00 if END_HOUR=24
            endTime = ZonedDateTime.now().withHour(END_HOUR + 1).withMinute(30),   // e.g. 18:30 or 00:30 next day
            isAllDay = false
        )
    )
    TodayCalendarWidgetTheme {
        TodayCalendarWidget(
            events = sampleEvents,
            // Simulate current time to be 11:30 for consistent preview
            debugCurrentTime = ZonedDateTime.now().withHour(11).withMinute(30).withSecond(0).withNano(0),
            modifier = Modifier.fillMaxSize().padding(16.dp)
        )
    }
}


@Preview(showBackground = true, widthDp = 360)
@Composable
fun TodayCalendarWidgetShortPreview() {
    // Simulate current time for this short preview
    val debugTime = ZonedDateTime.now().withHour(10).withMinute(15)
    TodayCalendarWidgetTheme {
        TodayCalendarWidget(
            events = listOf(
                 MyCalendarEvent(
                    id = "s1", summary = "Past Event",
                    startTime = debugTime.minusHours(1), // 09:15
                    endTime = debugTime.minusMinutes(15), // 10:00
                    isAllDay = false
                ),
                MyCalendarEvent(
                    id = "s2", summary = "Current Event",
                    startTime = debugTime.minusMinutes(15), // 10:00
                    endTime = debugTime.plusMinutes(45),    // 11:00
                    isAllDay = false
                )
            ),
            debugCurrentTime = debugTime,
            modifier = Modifier.fillMaxWidth().height(300.dp).padding(16.dp)
        )
    }
}
