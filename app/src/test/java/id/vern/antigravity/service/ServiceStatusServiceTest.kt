package id.vern.antigravity.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStatusServiceTest {

    @Test
    fun deriveHealth_reflectsComponentState() {
        assertEquals("ok", ServiceStatusService.deriveHealth(true, true))
        assertEquals("partial", ServiceStatusService.deriveHealth(true, false))
        assertEquals("partial", ServiceStatusService.deriveHealth(false, true))
        assertEquals("stopped", ServiceStatusService.deriveHealth(false, false))
    }

    @Test
    fun summaryText_containsAllReportedComponents() {
        val text = ServiceStatus(
            daemonRunning = true,
            gatewayRunning = false,
            wakeLockHeld = true,
            health = "partial",
            updatedAt = 123L
        ).summaryText()
        assertTrue(text.contains("daemon=on"))
        assertTrue(text.contains("gateway=off"))
        assertTrue(text.contains("wakeLock=on"))
        assertTrue(text.contains("health=partial"))
    }
}
