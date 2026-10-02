package exh.ui.nhentaidate

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Builds nhentai's absolute-date query so the selected calendar days are inclusive. */
object NhentaiDateQuery {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun build(startDate: String, endDate: String, excludedTags: List<String> = emptyList()): String {
        val start = LocalDate.parse(startDate, formatter)
        val end = LocalDate.parse(endDate, formatter)
        require(!start.isAfter(end)) { "Start date must not be after end date" }

        // Use an exclusive boundary on each side. This avoids dropping galleries uploaded
        // at any time on the first or last selected day due to timezone/time-of-day rounding.
        val filters = mutableListOf(
            "uploaded:>${start.minusDays(1).format(formatter)}",
            "uploaded:<${end.plusDays(1).format(formatter)}",
        )
        filters += excludedTags.map { "-tags:$it" }
        return filters.joinToString(" ")
    }
}
