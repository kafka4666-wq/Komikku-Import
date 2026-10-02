package exh.ui.nhentaidate

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class NhentaiDateQueryTest {
    @Test
    fun `range includes both selected calendar days`() {
        assertEquals(
            "uploaded:<12d",
            NhentaiDateQuery.build("2026-09-22", "2026-10-02", 1_790_899_200_000L),
        )
    }

    @Test
    fun `same day uses adjacent exclusive boundaries`() {
        assertEquals(
            "uploaded:<2d",
            NhentaiDateQuery.build("2026-10-02", "2026-10-02", 1_790_899_200_000L),
        )
    }

    @Test
    fun `excluded tags remain part of the query`() {
        assertEquals(
            "uploaded:<12d -tags:yaoi -tags:futanari",
            NhentaiDateQuery.build("2026-09-22", "2026-10-02", 1_790_899_200_000L, listOf("yaoi", "futanari")),
        )
    }
}
