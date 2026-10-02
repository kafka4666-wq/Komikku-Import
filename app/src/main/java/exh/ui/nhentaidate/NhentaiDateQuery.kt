package exh.ui.nhentaidate

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Builds a supported relative-date discovery query; exact range filtering is local. */
object NhentaiDateQuery {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE
    private const val DAY_MS = 86_400_000L

    fun build(
        startDate: String,
        endDate: String,
        nowMillis: Long,
        excludedTags: List<String> = emptyList(),
    ): String {
        val start = LocalDate.parse(startDate, formatter)
        val end = LocalDate.parse(endDate, formatter)
        require(!start.isAfter(end)) { "Start date must not be after end date" }

        val startMillis = start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val ageDays = ((nowMillis - startMillis).coerceAtLeast(0L) + DAY_MS - 1L) / DAY_MS
        // nhentai's absolute-date syntax currently returns zero results. Fetch a safe
        // relative-date superset; the worker filters each result's upload_date exactly.
        val filters = mutableListOf("uploaded:<${(ageDays + 2L).coerceAtLeast(2L)}d")
        filters += excludedTags.map { "-tags:$it" }
        return filters.joinToString(" ")
    }
}
