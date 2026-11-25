package id.vern.antigravity.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionConfigServiceTest {

    @Test
    fun defaultsAreAskAndDeny() {
        val state = PermissionGrantsState()
        assertEquals("CASCADE_COMMANDS_AUTO_EXECUTION_ASK", state.autoExecutionPolicy)
        assertEquals("AGENT_SETTING_POLICY_DENY", state.nonWorkspaceFileAccessPolicy)
        assertEquals("Always Ask", state.planReviewPolicy)
        assertEquals("Default", state.securityPreset)
        assertEquals(PermissionDefaultMode.ASK, PermissionConfigService.getDefaultPermissionMode())
    }

    @Test
    fun defaultModeForAction_appliesPolicy() {
        try {
            assertEquals("ask", PermissionConfigService.defaultModeForAction("command"))
            assertEquals("deny", PermissionConfigService.defaultModeForAction("unsandboxed"))
            assertEquals("deny", PermissionConfigService.defaultModeForAction("execute_url"))

            PermissionConfigService.setDefaultPermissionMode(PermissionDefaultMode.DENY)
            assertEquals("deny", PermissionConfigService.defaultModeForAction("read_file"))

            PermissionConfigService.setDefaultPermissionMode(PermissionDefaultMode.ALLOW)
            assertEquals("allow", PermissionConfigService.defaultModeForAction("read_file"))
        } finally {
            PermissionConfigService.setDefaultPermissionMode(PermissionDefaultMode.ASK)
        }
    }
}
