package ai.rever.boss.plugin.dynamic.terminaltab

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupWizardRendererOwnershipTest {
    @AfterTest
    fun resetOwnership() {
        SetupWizardRendererOwnership.resetForTest()
    }

    @Test
    fun `only the claiming composition owns the setup renderer`() {
        assertTrue(SetupWizardRendererOwnership.claim("first-token", "first-window"))
        assertFalse(SetupWizardRendererOwnership.claim("second-token", "second-window"))
        assertFalse(SetupWizardRendererOwnership.claim("duplicate-token", "first-window"))
        assertEquals("first-window", SetupWizardRendererOwnership.ownerWindowId())
    }

    @Test
    fun `only owner release makes renderer available`() {
        assertTrue(SetupWizardRendererOwnership.claim("first-token", "first-window"))

        SetupWizardRendererOwnership.release("not-the-owner")
        assertFalse(SetupWizardRendererOwnership.claim("second-token", "second-window"))

        SetupWizardRendererOwnership.release("first-token")
        assertTrue(SetupWizardRendererOwnership.claim("second-token", "second-window"))
        assertEquals("second-window", SetupWizardRendererOwnership.ownerWindowId())
    }
}
