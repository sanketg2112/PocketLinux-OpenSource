package com.sg.linuxgo

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LegalDocumentsTest {

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TelemetryManager.resetForTests(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @Test
    fun newInstallHasNotAccepted() {
        assertFalse(LegalDocuments.hasAccepted(context))
    }

    @Test
    fun completedOnboardingCountsAsAccepted() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean("onboarding_completed", true)
            .commit()
        assertTrue(LegalDocuments.hasAccepted(context))
    }

    @Test
    fun agreeAcceptsTermsWithoutEnablingAutomaticTelemetry() {
        LegalDocuments.accept(context)
        assertTrue(LegalDocuments.hasAccepted(context))
        assertFalse(TelemetryManager.shouldSendEvent(context, "app_open"))
        assertTrue(
            TelemetryManager.shouldSendEvent(context, TelemetryManager.EVENT_CRASH_REPORT)
        )
        assertTrue(
            TelemetryManager.shouldSendEvent(context, TelemetryManager.EVENT_USER_FEEDBACK)
        )
    }

    @Test
    fun diagnosticsSettingsSurfacesTermsAndPrivacy() {
        assertEquals("Legal and others", LegalDocuments.SETTINGS_PAGE_TITLE)
        assertEquals("Terms of Service", LegalDocuments.TERMS_TITLE)
        assertEquals("Privacy Policy", LegalDocuments.PRIVACY_TITLE)
        assertEquals("Resources & thanks", LegalDocuments.RESOURCES_TITLE)
        assertEquals("Open-source projects we use", LegalDocuments.RESOURCES_SUBTITLE)
        assertTrue(LegalDocuments.TERMS_ROW_SUBTITLE.isNotBlank())
        assertTrue(LegalDocuments.PRIVACY_ROW_SUBTITLE.isNotBlank())
        assertTrue(LegalDocuments.SETTINGS_PAGE_SUBTITLE.contains("documents", ignoreCase = true))
        assertFalse(LegalDocuments.SETTINGS_PAGE_TITLE.contains("open-source", ignoreCase = true))
        assertFalse(LegalDocuments.SETTINGS_PAGE_SUBTITLE.contains("open-source", ignoreCase = true))
        assertEquals(LegalDocuments.SETTINGS_PAGE_SUBTITLE, LegalDocuments.SETTINGS_PAGE_HEADER_SUBTITLE)
        assertFalse(LegalDocuments.SETTINGS_PAGE_SUBTITLE.contains("Anonymous diagnostics"))
        assertTrue(LegalDocuments.OPEN_SOURCE_CREDITS.any { it.name.contains("PRoot") })
        assertTrue(
            LegalDocuments.OPEN_SOURCE_CREDITS.any {
                it.url == "https://github.com/termux/proot"
            }
        )
        assertTrue(LegalDocuments.OPEN_SOURCE_CREDITS.any { it.name.contains("Termux:X11") })
        assertTrue(LegalDocuments.OPEN_SOURCE_CREDITS.any { it.name.contains("tawc") })
        assertTrue(LegalDocuments.OPEN_SOURCE_CREDITS.any { it.name.contains("Nerd Fonts") })
        assertFalse(LegalDocuments.OPEN_SOURCE_CREDITS.any { it.name.contains("Omarchy") })
        val privacy = LegalDocuments.markdown(LegalDocuments.Kind.PRIVACY)
        assertTrue(privacy.contains("Settings → Help & about → ${LegalDocuments.SETTINGS_PAGE_TITLE}"))
        assertFalse(privacy.contains("Help & about → Diagnostics"))
        assertFalse(privacy.contains("Anonymous diagnostics"))
    }

    @Test
    fun onboardingConsentNamesBothDocumentsAndButton() {
        val sentence = LegalDocuments.CONSENT_LEAD +
            LegalDocuments.TERMS_TITLE +
            LegalDocuments.CONSENT_JOIN +
            LegalDocuments.PRIVACY_TITLE +
            LegalDocuments.CONSENT_TAIL
        assertTrue(sentence.contains("Agree and continue"))
        assertTrue(sentence.contains(LegalDocuments.TERMS_TITLE))
        assertTrue(sentence.contains(LegalDocuments.PRIVACY_TITLE))
        assertTrue(sentence.contains("PocketLinux"))
        assertEquals("Agree and continue", LegalDocuments.AGREE_BUTTON)
    }

    @Test
    fun notNowDeclineDoesNotAcceptTerms() {
        assertFalse(LegalDocuments.hasAccepted(context))
        assertFalse(
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(LegalDocuments.PREF_LEGAL_ACCEPTED, false)
        )
    }

    @Test
    fun privacyPolicyMatchesTelemetryDisclosure() {
        val text = LegalDocuments.markdown(LegalDocuments.Kind.PRIVACY)
        listOf(
            "does not send automatic usage analytics",
            "Nothing leaves the device unless you tap Send",
            "not uploaded to a remote server",
            "random install id",
            "Device maker and model",
            "Android version",
            "crash report",
            "Feedback and suggestions",
            "RAM (total and available)",
            "CPU type",
            "GPU/GLES version",
            "Feedback does not include that hardware snapshot",
            "GPS",
            LegalDocuments.CONTACT_URL,
            "Copy or reset the install id",
            "Mount phone storage",
            "not directed at children under 13"
        ).forEach { needle ->
            assertTrue("privacy policy should mention: $needle", text.contains(needle))
        }
        assertFalse(text.contains("opt-out ping"))
        assertFalse(text.contains("Whether Premium is currently active"))
        assertFalse(text.contains("Google Play"))
        assertFalse(text.contains("Play Billing"))
        assertFalse(text.contains("Supabase"))
        assertFalse(text.contains("Help improve PocketLinux"))
        assertFalse(text.contains("Anonymous diagnostics"))
    }

    @Test
    fun termsCoverAppWithoutAutomaticDiagnostics() {
        val text = LegalDocuments.markdown(LegalDocuments.Kind.TERMS)
        listOf(
            "rootless",
            "GPL-2.0",
            "does not sell subscriptions or in-app purchases",
            "does not send automatic usage or crash analytics",
            "PocketLinux Backup",
            LegalDocuments.CONTACT_URL,
            "Agree and continue",
            "legally binding",
            "AS IS",
            "indemnify"
        ).forEach { needle ->
            assertTrue("terms should mention: $needle", text.contains(needle))
        }
        assertFalse(text.contains("Anonymous diagnostics turn on"))
        assertFalse(text.contains("Google Play"))
        assertFalse(text.contains("Play Billing"))
        assertTrue(text.contains("• "))
    }

    @Test
    fun inAppSpannedDocumentContainsTermsHeadings() {
        val text = com.sg.linuxgo.ui.legal.legalDocumentSpanned(LegalDocuments.Kind.TERMS).toString()
        assertTrue(text.contains("Last updated: ${LegalDocuments.LAST_UPDATED}"))
        assertTrue(text.contains("1. Agreement and acceptance"))
        assertTrue(text.contains("rootless"))
        val privacy = com.sg.linuxgo.ui.legal.legalDocumentSpanned(LegalDocuments.Kind.PRIVACY).toString()
        assertTrue(privacy.contains("Crash reports and feedback"))
        assertTrue(privacy.contains("random install id"))
        assertFalse(privacy.contains("Anonymous diagnostics"))
    }
}
