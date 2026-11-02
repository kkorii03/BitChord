package com.music.bitchord.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A restored account whose plain preference file is otherwise empty is still
 * an existing install: the welcome pages must not run again, and the completion
 * flag has to be written the same launch.
 */
class AppSettingsOnboardingFallbackTest {

    @Test
    fun `a signed in install with empty plain prefs is not onboarded again`() {
        assertTrue(
            "a restored session means this is not a fresh install",
            AppSettings.onboardingWasMissedBeforeAuth(
                saved = false,
                existingInstall = false,
                signedIn = true,
            ),
        )
    }

    @Test
    fun `a fresh install without a session still sees onboarding`() {
        assertFalse(
            AppSettings.onboardingWasMissedBeforeAuth(
                saved = false,
                existingInstall = false,
                signedIn = false,
            ),
        )
    }

    @Test
    fun `an install that already finished onboarding is left alone`() {
        assertFalse(
            AppSettings.onboardingWasMissedBeforeAuth(
                saved = true,
                existingInstall = false,
                signedIn = true,
            ),
        )
    }

    @Test
    fun `an install with other stored settings keeps its existing-install answer`() {
        assertFalse(
            AppSettings.onboardingWasMissedBeforeAuth(
                saved = false,
                existingInstall = true,
                signedIn = true,
            ),
        )
    }
}
