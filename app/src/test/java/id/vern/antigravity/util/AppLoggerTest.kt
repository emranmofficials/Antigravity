package id.vern.antigravity.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppLoggerTest {

    @Test
    fun redact_masksTokenAssignments() {
        assertEquals("x-codeium-csrf-token=<redacted>", AppLogger.redact("x-codeium-csrf-token: superSecretTokenValue1234567890"))
        assertEquals("token=<redacted>", AppLogger.redact("token=\"abc123\""))
        assertEquals("Authorization=<redacted>", AppLogger.redact("Authorization: 'secret-value'"))
    }

    @Test
    fun redact_masksBearerAndOpaqueValues() {
        val redacted = AppLogger.redact("Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123456789")
        assertFalse(redacted.contains("abcdefghijklmnop"))
        assertEquals("session=<redacted>", AppLogger.redact("session=0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun redact_leavesOrdinaryMessagesUntouched() {
        assertEquals("Downloading core engine (attempt 2/5)", AppLogger.redact("Downloading core engine (attempt 2/5)"))
    }
}
