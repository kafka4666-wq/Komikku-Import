package exh.ui.nhentaidate

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class NhentaiDateQueryTest {
    @Test
    fun `range includes both selected calendar days`() {
        assertEquals(
            "uploaded:>2026-09-21 uploaded:<2026-10-03",
            NhentaiDateQuery.build("2026-09-22", "2026-10-02"),
        )
    }

    @Test
    fun `same day uses adjacent exclusive boundaries`() {
        assertEquals(
            "uploaded:>2026-10-01 uploaded:<2026-10-03",
            NhentaiDateQuery.build("2026-10-02", "2026-10-02"),
        )
    }

    @Test
    fun `excluded tags remain part of the query`() {
        assertEquals(
            "uploaded:>2026-09-21 uploaded:<2026-10-03 -tags:yaoi -tags:futanari",
            NhentaiDateQuery.build("2026-09-22", "2026-10-02", listOf("yaoi", "futanari")),
        )
    }
}
