package com.sg.linuxgo

/**
 * User-facing copy for crash reports, feedback, and Settings.
 * Keep this aligned with [TelemetryManager] fields.
 */
object PrivacyDisclosures {
    const val SETTINGS_DATA_SECTION = "CRASH REPORTS AND FEEDBACK"

    const val INSTALL_ID_TITLE = "Report install id"
    const val RESET_ID_TITLE = "Reset install id"
    const val RESET_ID_SUBTITLE =
        "Starts a new anonymous id on this device. This open-source build does not upload reports."
    const val RESET_ID_CONFIRM =
        "This replaces the install id on this device. This open-source build does not store reports on a remote server."
    const val INSTALL_ID_COPIED = "Install id copied"

    const val DETAILS_TITLE = "What we send"

    const val DETAILS_BODY =
        "Nothing is sent automatically. No app opens, distro boots, session length, " +
            "or permissions.\n\n" +
            "This open-source build does not upload crash reports or feedback.\n\n" +
            "If you tap Send, the app may keep on the device only:\n" +
            "• Crash report (optional note, short stack, recent session log, plus RAM, " +
            "storage, CPU, and GPU/SoC)\n" +
            "• Feedback & suggestions (rating, category, message — no hardware snapshot)\n\n" +
            "Each local report may include:\n" +
            "• Random install id (not your Google account)\n" +
            "• Device maker and model, Android version, app version\n" +
            "• Distro id if a desktop session was involved\n\n" +
            "Not collected: name, email, Google account, Linux files, terminal history, " +
            "or GPS. Not sold. Not used for ads."
}
