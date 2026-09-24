package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.boss.plugin.dynamic.terminaltab.onboarding.BossTermSetupState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossTermSetupStatusRegistrationTest {
    @Test
    fun `status item follows session lifetime without duplicate registration`() {
        var registrations = 0
        var removals = 0
        var registered = false

        fun update(hasSession: Boolean) {
            registered = updateSetupStatusRegistration(
                hasSession = hasSession,
                isRegistered = registered,
                register = { registrations += 1 },
                unregister = { removals += 1 },
            )
        }

        update(false)
        assertFalse(registered)
        update(true)
        update(true)
        assertTrue(registered)
        update(false)
        update(false)

        assertFalse(registered)
        assertEquals(1, registrations)
        assertEquals(1, removals)
    }

    @Test
    fun `all unacknowledged session outcomes retain the status item`() {
        val states = listOf(
            BossTermSetupState(sessionId = "running"),
            BossTermSetupState(sessionId = "failed", finished = true, failureMessage = "failed"),
            BossTermSetupState(sessionId = "complete", finished = true),
            BossTermSetupState(sessionId = "auth", finished = true, authenticateGitHubAfterSetup = true),
        )
        states.forEach { state ->
            var registered = false
            registered = updateSetupStatusRegistration(
                hasSession = state.sessionId != null,
                isRegistered = registered,
                register = {},
                unregister = { error("unacknowledged session was removed") },
            )
            assertTrue(registered)
        }
    }

    @Test
    fun `status opens only a backgrounded session and distinguishes pending auth`() {
        val foreground = BossTermSetupState(sessionId = "same", isBackgrounded = false)
        val background = foreground.copy(isBackgrounded = true)
        val auth = background.copy(finished = true, authenticateGitHubAfterSetup = true)

        assertFalse(setupStatusCanOpen(foreground))
        assertTrue(setupStatusCanOpen(background))
        assertEquals("BOSS Term GitHub sign-in", setupStatusLabel(auth))
    }
}
