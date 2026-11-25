package id.vern.antigravity.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CloudCodeQuotaServiceTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun formatResetCountdown_isDeterministic() {
        assertEquals("2 minutes", CloudCodeQuotaService.formatResetCountdown("2026-01-01T00:02:00Z", now))
        assertEquals("<1 minute", CloudCodeQuotaService.formatResetCountdown("2026-01-01T00:00:30Z", now))
        assertEquals("<1 minute", CloudCodeQuotaService.formatResetCountdown(null, now))
        assertEquals("1 days, 2 hours", CloudCodeQuotaService.formatResetCountdown("2026-01-02T02:00:00Z", now))
    }

    @Test
    fun formatSubtext_isDeterministic() {
        assertEquals("Resets in 2m", CloudCodeQuotaService.formatSubtext("2026-01-01T00:02:00Z", now))
        assertEquals("Resets in 1d 2h", CloudCodeQuotaService.formatSubtext("2026-01-02T02:00:00Z", now))
        assertEquals(null, CloudCodeQuotaService.formatSubtext(null, now))
    }

    @Test
    fun isFiveHourBucket_recognizesKnownVariants() {
        assertTrue(CloudCodeQuotaService.isFiveHourBucket("5h", "other", ""))
        assertTrue(CloudCodeQuotaService.isFiveHourBucket("", "gemini-5h-weekly", ""))
        assertTrue(CloudCodeQuotaService.isFiveHourBucket("", "", "Five Hour Limit Remaining"))
        assertFalse(CloudCodeQuotaService.isFiveHourBucket("weekly", "weekly", "Weekly Limit"))
    }

    @Test
    fun isWeeklyBucket_recognizesKnownVariants() {
        assertTrue(CloudCodeQuotaService.isWeeklyBucket("weekly", "", ""))
        assertTrue(CloudCodeQuotaService.isWeeklyBucket("168h", "", ""))
        assertTrue(CloudCodeQuotaService.isWeeklyBucket("", "argon-weekly", ""))
        assertFalse(CloudCodeQuotaService.isWeeklyBucket("5h", "gemini-5h", "Five Hour"))
    }
}
