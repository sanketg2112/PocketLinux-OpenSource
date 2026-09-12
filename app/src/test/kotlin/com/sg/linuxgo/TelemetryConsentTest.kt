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
class TelemetryConsentTest {

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TelemetryManager.resetForTests(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @Test
    fun onlyCrashReportAndFeedbackAreSent() {
        assertTrue(TelemetryManager.shouldSendEvent(context, TelemetryManager.EVENT_CRASH_REPORT))
        assertTrue(TelemetryManager.shouldSendEvent(context, TelemetryManager.EVENT_USER_FEEDBACK))
        listOf(
            "app_open",
            "distro_boot",
            "gui_session_end",
            "app_crash",
            "distro_crash",
            "permission_decision",
            "premium_purchase",
            "diagnostics_opt_in",
            "diagnostics_opt_out",
            "dev_override_unlock"
        ).forEach { event ->
            assertFalse(
                "automatic event should not send: $event",
                TelemetryManager.shouldSendEvent(context, event)
            )
        }
    }

    @Test
    fun trackEventDropsAutomaticPings() {
        TelemetryManager.trackEvent(context, "app_open")
        assertTrue(TelemetryManager.lastTrackSkipped)
        assertEquals("app_open", TelemetryManager.lastTrackedEventType)
        assertTrue(TelemetryManager.lastPayloadKeys.isEmpty())
    }

    @Test
    fun trackEventAllowsCrashReport() {
        TelemetryManager.trackEvent(
            context,
            TelemetryManager.EVENT_CRASH_REPORT,
            activeDistro = "debian",
            metadata = """{"user_note":"boom","stacktrace_snippet":"at Foo.bar","user_submitted":true}"""
        )
        assertFalse(TelemetryManager.lastTrackSkipped)
        assertEquals(TelemetryManager.EVENT_CRASH_REPORT, TelemetryManager.lastTrackedEventType)
        assertTrue(TelemetryManager.lastPayloadKeys.contains("installation_id"))
        assertTrue(TelemetryManager.lastPayloadKeys.contains("app_version"))
        assertTrue(TelemetryManager.lastPayloadKeys.contains("android_sdk"))
        assertTrue(TelemetryManager.lastPayloadKeys.contains("device_model"))
        assertTrue(TelemetryManager.lastPayloadKeys.contains("active_distro"))
        assertTrue(TelemetryManager.lastPayloadKeys.contains("user_note"))
        assertTrue(TelemetryManager.lastPayloadKeys.contains("stacktrace_snippet"))
        assertTrue(TelemetryManager.lastPayloadKeys.contains("total_ram_mb"))
        assertTrue(TelemetryManager.lastPayloadKeys.contains("avail_ram_mb"))
        assertTrue(TelemetryManager.lastPayloadKeys.contains("storage_free_mb"))
        assertTrue(TelemetryManager.lastPayloadKeys.contains("cpu_abi"))
        assertTrue(TelemetryManager.lastPayloadKeys.contains("cpu_cores"))
        assertFalse(TelemetryManager.lastPayloadKeys.contains("is_premium"))
        assertFalse(TelemetryManager.lastPayloadKeys.contains("notification_permission"))
    }

    @Test
    fun trackEventAllowsFeedback() {
        TelemetryManager.trackEvent(
            context,
            TelemetryManager.EVENT_USER_FEEDBACK,
            metadata = """{"rating":5,"category":"feedback","message":"great"}"""
        )
        assertFalse(TelemetryManager.lastTrackSkipped)
        assertEquals(TelemetryManager.EVENT_USER_FEEDBACK, TelemetryManager.lastTrackedEventType)
        assertTrue(TelemetryManager.lastPayloadKeys.contains("rating"))
        assertTrue(TelemetryManager.lastPayloadKeys.contains("message"))
        assertFalse(TelemetryManager.lastPayloadKeys.contains("is_premium"))
        assertFalse(TelemetryManager.lastPayloadKeys.contains("total_ram_mb"))
        assertFalse(TelemetryManager.lastPayloadKeys.contains("cpu_abi"))
        assertFalse(TelemetryManager.lastPayloadKeys.contains("gles_version"))
    }

    @Test
    fun crashHardwareDiagnosticsHasRamCpuAndGpuHints() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString("gpu_driver_mode", "llvmpipe")
            .commit()
        val diag = TelemetryManager.crashHardwareDiagnostics(context)
        assertTrue(diag.has("total_ram_mb"))
        assertTrue(diag.has("avail_ram_mb"))
        assertTrue(diag.has("cpu_abi"))
        assertTrue(diag.has("cpu_cores"))
        assertTrue(diag.has("os_build"))
        assertEquals("llvmpipe", diag.optString("gpu_driver_mode"))
        assertFalse(diag.has("is_premium"))
        assertFalse(diag.has("notification_permission"))
    }

    @Test
    fun resetInstallIdentityRotatesId() {
        val first = TelemetryManager.getInstallationId(context)
        TelemetryManager.resetInstallIdentity(context)
        val second = TelemetryManager.getInstallationId(context)
        assertTrue(first.isNotEmpty())
        assertTrue(second.isNotEmpty())
        assertTrue(first != second)
    }

    @Test
    fun installationIdIsStableUntilReset() {
        val first = TelemetryManager.getInstallationId(context)
        val second = TelemetryManager.getInstallationId(context)
        assertEquals(first, second)
    }
}
