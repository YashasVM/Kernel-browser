package com.kernel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebDateTimeTest {
    private val date = 1
    private val month = 2
    private val week = 3
    private val time = 4
    private val dateTime = 5

    @Test
    fun roundTripsEachInputType() {
        listOf(
            date to "2026-10-14",
            month to "2026-10",
            time to "09:05",
            dateTime to "2026-10-14T21:30",
            week to "2026-W42"
        ).forEach { (type, value) ->
            val parsed = WebDateTime.parse(type, value)!!
            assertEquals(value, WebDateTime.format(type, parsed))
        }
    }

    @Test
    fun parsesTimeWithSeconds() {
        val parsed = WebDateTime.parse(time, "07:45:30")!!
        assertEquals(7, parsed.hour)
        assertEquals(45, parsed.minute)
    }

    @Test
    fun isoWeekYearIsUsedAroundNewYear() {
        // 2027-01-01 is a Friday, so it belongs to week 53 of 2026.
        val newYear = WebDateTime.Value(2027, 1, 1, 0, 0)
        assertEquals("2026-W53", WebDateTime.format(week, newYear))
    }

    @Test
    fun invalidValuesParseToNull() {
        assertNull(WebDateTime.parse(date, ""))
        assertNull(WebDateTime.parse(date, "not a date"))
        assertNull(WebDateTime.parse(time, "25"))
    }
}
