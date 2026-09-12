package com.sg.linuxgo

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * In-app Terms of Service, Privacy Policy, and project credits.
 * Keep collection claims aligned with [PrivacyDisclosures] and [TelemetryManager].
 */
object LegalDocuments {
    const val LAST_UPDATED = "2026-09-12"
    const val CONTACT_URL =
        "https://github.com/sanketg2112/PocketLinux-OpenSource/issues"

    const val TERMS_TITLE = "Terms of Service"
    const val PRIVACY_TITLE = "Privacy Policy"
    const val SETTINGS_PAGE_TITLE = "Legal and others"
    const val SETTINGS_PAGE_SUBTITLE = "Documents, reports, and resources"
    const val SETTINGS_PAGE_HEADER_SUBTITLE = "Documents, reports, and resources"
    const val TERMS_ROW_SUBTITLE = "How you may use PocketLinux"
    const val PRIVACY_ROW_SUBTITLE = "Crash reports, feedback, and what we collect"
    const val RESOURCES_TITLE = "Resources & thanks"
    const val RESOURCES_SUBTITLE = "Open-source projects we use"
    const val RESOURCES_HIDE_SUBTITLE = "Hide open-source credits"

    data class OpenSourceCredit(val name: String, val url: String)

    val OPEN_SOURCE_CREDITS: List<OpenSourceCredit> = listOf(
        OpenSourceCredit("PRoot", "https://github.com/termux/proot"),
        OpenSourceCredit("proot-distro", "https://github.com/termux/proot-distro"),
        OpenSourceCredit("Termux", "https://github.com/termux/termux-packages"),
        OpenSourceCredit("Termux:X11 (Lorie)", "https://github.com/termux/termux-x11"),
        OpenSourceCredit("X.Org", "https://www.x.org"),
        OpenSourceCredit("tawc / tawcroot", "https://github.com/wmww/tawc"),
        OpenSourceCredit("Smithay", "https://github.com/Smithay/smithay"),
        OpenSourceCredit("Mesa & Turnip", "https://github.com/lfdevs/mesa-for-android-container"),
        OpenSourceCredit("Toybox", "http://landley.net/toybox"),
        OpenSourceCredit("PulseAudio", "https://www.freedesktop.org/wiki/Software/PulseAudio/"),
        OpenSourceCredit("D-Bus", "https://www.freedesktop.org/wiki/Software/dbus/"),
        OpenSourceCredit("libhybris", "https://github.com/wmww/libhybris"),
        OpenSourceCredit("Nerd Fonts", "https://github.com/ryanoasis/nerd-fonts"),
        OpenSourceCredit(
            "PocketLinux",
            "https://github.com/sanketg2112/PocketLinux-OpenSource"
        )
    )

    const val AGREE_BUTTON = "Agree and continue"

    const val CONSENT_LEAD = "By tapping Agree and continue, you agree to the "
    const val CONSENT_JOIN = " and "
    const val CONSENT_TAIL = " of PocketLinux."

    const val PREF_LEGAL_ACCEPTED = "legal_terms_accepted"
    private const val PREF_ONBOARDING_COMPLETED = "onboarding_completed"

    data class Section(val heading: String, val body: String)

    enum class Kind {
        TERMS,
        PRIVACY;

        val title: String
            get() = when (this) {
                TERMS -> TERMS_TITLE
                PRIVACY -> PRIVACY_TITLE
            }

        val sections: List<Section>
            get() = when (this) {
                TERMS -> termsSections
                PRIVACY -> privacySections
            }
    }

    val termsSections: List<Section> = listOf(
        Section(
            "1. Agreement and acceptance",
            "These Terms of Service (“Terms”) are a legally binding agreement between you and " +
                "the solo developer of PocketLinux (“we”, “us”, “our”) for the PocketLinux " +
                "Android application, package com.sg.linuxgo (the “app”), including this " +
                "open-source source tree.\n\n" +
                "By tapping Agree and continue, or by installing, accessing, or using the app, " +
                "you represent that you have read, understood, and agree to be bound by these " +
                "Terms and by the Privacy Policy. If you do not agree, do not use the app."
        ),
        Section(
            "2. The service",
            "PocketLinux provides a rootless Linux environment on Android. Subject to these " +
                "Terms and to device limitations, the app may include:\n\n" +
                "• A Linux desktop session and a terminal\n" +
                "• The ability to install and run Linux software inside an environment " +
                "created by the app\n" +
                "• Operation without device root, dual boot, or a separate computer\n\n" +
                "The app is not, and must not be treated as:\n\n" +
                "• A separate operating system or a replacement for Android\n" +
                "• A full personal computer, virtual private server, or hosted server\n" +
                "• A grant of real root privileges, loadable kernel modules, systemd as on a " +
                "conventional Linux installation, or standard Docker\n\n" +
                "Intended use is everyday desktop work, coding, package installs, and terminal " +
                "use. Performance, hardware support, and stability vary by device and by " +
                "Android. We do not warrant any particular result."
        ),
        Section(
            "3. License",
            "This project is licensed under the GNU General Public License v2.0 (GPL-2.0). " +
                "You may use, copy, modify, and redistribute the source and binaries under " +
                "that license. See the LICENSE file in the repository. Third-party components " +
                "bundled with the app keep their own licenses."
        ),
        Section(
            "4. Your responsibilities",
            "You are solely responsible for:\n\n" +
                "• Software, commands, and services you install or run inside Linux\n" +
                "• Backing up data you care about\n" +
                "• Complying with applicable law and with licenses of Linux distributions " +
                "and packages you install\n" +
                "• Use of the device network (Linux uses the same network as the app)\n\n" +
                "You must not use the app to:\n\n" +
                "• Attack, scan, or disrupt systems you are not authorized to access\n" +
                "• Develop or distribute malware\n" +
                "• Violate any applicable law"
        ),
        Section(
            "5. Features in this source tree",
            "This open-source build does not sell subscriptions or in-app purchases. " +
                "Subject to device limits and published container images, you may:\n\n" +
                "• Create more than one Linux environment\n" +
                "• Use published distributions and desktops from the catalog\n" +
                "• Back up and restore environments to shared storage\n" +
                "• Use experimental settings such as Wayland or tawcroot\n\n" +
                "Catalog images and experimental features may change or be withdrawn."
        ),
        Section(
            "6. Permissions and storage",
            "The following Android permissions are optional. The Linux environment still " +
                "runs if you deny them:\n\n" +
                "• Notifications — help keep a Linux session alive in the background so " +
                "Android is less likely to kill it when you switch apps\n" +
                "• Storage / all-files access — needed to write backups to shared storage " +
                "and, if you enable Mount phone storage, to expose phone files inside Linux " +
                "at /sdcard\n\n" +
                "If you enable Mount phone storage and grant storage access, software you " +
                "install in Linux can read and write those shared files. You are responsible " +
                "for that choice."
        ),
        Section(
            "7. Privacy",
            "Use of the app is also governed by the Privacy Policy, which is incorporated " +
                "by reference. The app does not send automatic usage or crash analytics. " +
                "This open-source build does not upload crash reports or feedback to a remote " +
                "server."
        ),
        Section(
            "8. Backups and data loss",
            "Linux environments are stored in the app’s private storage. They may be " +
                "permanently lost if you:\n\n" +
                "• Uninstall the app\n" +
                "• Clear the app’s data\n" +
                "• Experience a failed install, restore, or device failure\n\n" +
                "Backups you create are written only to shared storage you choose (typically " +
                "/sdcard/PocketLinux Backup/). The app does not upload backups. Without a " +
                "password, a backup is an ordinary archive of the environment. With a " +
                "password, the archive is encrypted on the device before it is saved. TO THE " +
                "MAXIMUM EXTENT PERMITTED BY LAW, WE ARE NOT LIABLE FOR DATA LOSS."
        ),
        Section(
            "9. Third-party software",
            "The app incorporates third-party open-source software, including PRoot and " +
                "related tools, each licensed by its authors. Linux distributions and packages " +
                "you install are provided by their upstream projects, not by us, and are " +
                "subject to their own licenses and terms."
        ),
        Section(
            "10. Disclaimer of warranties",
            "THE APP IS PROVIDED “AS IS” AND “AS AVAILABLE”, WITHOUT WARRANTIES OF ANY KIND, " +
                "WHETHER EXPRESS, IMPLIED, OR STATUTORY, INCLUDING MERCHANTABILITY, FITNESS " +
                "FOR A PARTICULAR PURPOSE, AND NON-INFRINGEMENT, TO THE MAXIMUM EXTENT " +
                "PERMITTED BY LAW.\n\n" +
                "Linux on Android is best-effort. Without limitation:\n\n" +
                "• Hardware support and GPU acceleration depend on the device\n" +
                "• Sessions may end when Android reclaims memory\n" +
                "• Sessions are more likely to end in the background if notification " +
                "permission is denied\n" +
                "• Experimental features may be unstable or withdrawn"
        ),
        Section(
            "11. Limitation of liability",
            "TO THE MAXIMUM EXTENT PERMITTED BY LAW, WE ARE NOT LIABLE FOR ANY INDIRECT, " +
                "INCIDENTAL, SPECIAL, CONSEQUENTIAL, EXEMPLARY, OR PUNITIVE DAMAGES, OR FOR " +
                "LOST DATA, LOST PROFITS, LOST BUSINESS, OR COST OF SUBSTITUTE SERVICES, " +
                "ARISING OUT OF OR RELATED TO YOUR USE OF THE APP, WHETHER BASED ON CONTRACT, " +
                "TORT, STRICT LIABILITY, OR ANY OTHER THEORY, EVEN IF WE HAVE BEEN ADVISED OF " +
                "THE POSSIBILITY OF SUCH DAMAGES.\n\n" +
                "OUR TOTAL LIABILITY FOR ALL CLAIMS ARISING OUT OF THE APP SHALL NOT EXCEED " +
                "TEN US DOLLARS (US$10)."
        ),
        Section(
            "12. Indemnification",
            "To the extent permitted by law, you will defend, indemnify, and hold us " +
                "harmless from claims, damages, and expenses (including reasonable legal " +
                "fees) arising from: (a) your use of the app; (b) software or content you " +
                "install or run inside Linux; or (c) your breach of these Terms."
        ),
        Section(
            "13. Changes and termination",
            "We may update the app and these Terms. The copy in the app is the current " +
                "version. Material changes are reflected by updating the Last updated date " +
                "($LAST_UPDATED). Continued use after an update constitutes acceptance of the " +
                "updated Terms.\n\n" +
                "We may stop offering the app or particular distributions at any time. You " +
                "may stop using the app at any time by uninstalling it. Provisions that by " +
                "their nature should survive (including disclaimers, limitation of liability, " +
                "and indemnification) survive termination."
        ),
        Section(
            "14. Contact",
            "Questions about these Terms: $CONTACT_URL"
        )
    )

    val privacySections: List<Section> = listOf(
        Section(
            "1. Who we are",
            "PocketLinux is developed by a solo developer (“we”, “us”, “our”). This Privacy " +
                "Policy describes information the Android app (package com.sg.linuxgo) may " +
                "handle, how Linux on your phone relates to privacy, and what this " +
                "open-source build does not send off the device.\n\n" +
                "Contact: $CONTACT_URL"
        ),
        Section(
            "2. Scope",
            "This policy covers the PocketLinux Android app built from this source tree. " +
                "It does not cover:\n\n" +
                "• Websites or software you open or install inside Linux\n" +
                "• Third-party services you choose to use from a desktop or terminal session"
        ),
        Section(
            "3. Crash reports and feedback",
            "The app does not send automatic usage analytics or automatic crash pings. " +
                "Nothing leaves the device unless you tap Send, and in this open-source " +
                "build even those submissions are not uploaded to a remote server.\n\n" +
                "In-app crash and feedback prompts may still collect, on the device only:\n\n" +
                "• A crash report (optional note, short stack, recent session log)\n" +
                "• Feedback and suggestions (rating, category, message)\n\n" +
                "Each local report may include:\n\n" +
                "• A random install id stored on the device (not your Google account)\n" +
                "• Device maker and model, Android version, and app version\n" +
                "• Distro id (for example debian or archlinux) if a desktop session was involved\n\n" +
                "Crash reports also include hardware that helps diagnose the failure:\n\n" +
                "• RAM (total and available) and free/total storage\n" +
                "• CPU type (ABI and core count)\n" +
                "• GPU/GLES version, Vulkan if advertised, SoC name when Android reports it, " +
                "and the GPU driver mode selected in the app\n\n" +
                "Feedback does not include that hardware snapshot.\n\n" +
                "Your choices:\n\n" +
                "• You can decline a crash-report prompt; nothing is stored for sending\n" +
                "• You can tap Don’t ask again to stop crash-report prompts\n" +
                "• Copy or reset the install id in Settings → Help & about → " +
                "$SETTINGS_PAGE_TITLE\n" +
                "• Resetting the install id replaces it on this device"
        ),
        Section(
            "4. Information we do not collect",
            "We do not collect:\n\n" +
                "• Automatic app opens, distro boots, session length, or permission outcomes\n" +
                "• Hardware specs except as part of a crash report you choose to write\n" +
                "• Name, email, phone number, or Google account (unless you email us yourself)\n" +
                "• Linux files, shell history, SSH keys, or browser profiles\n" +
                "• GPS / location coordinates\n" +
                "• Advertising identifiers for ads — the app does not show ads and does not " +
                "sell your data\n\n" +
                "This open-source build does not open a network connection to upload reports."
        ),
        Section(
            "5. Payments",
            "This open-source build does not process payments, subscriptions, or in-app " +
                "purchases."
        ),
        Section(
            "6. Backups",
            "Backups you create are written to shared storage " +
                "(/sdcard/PocketLinux Backup/). They are not uploaded by the app.\n\n" +
                "• Without a password, a backup is an ordinary archive and can contain " +
                "anything inside the environment\n" +
                "• With a password, the archive is encrypted on the device before it is saved\n" +
                "• Anyone with access to that shared-storage file can read an unencrypted backup"
        ),
        Section(
            "7. Linux isolation",
            "PocketLinux is rootless (PRoot). Linux processes run as the app’s Android user.\n\n" +
                "• They can use the same network as the app\n" +
                "• If you turn on Mount phone storage and grant All files access, they can " +
                "use the same shared storage\n" +
                "• Other apps’ private data stays protected by Android\n" +
                "• Software you install inside Linux may collect its own data; that is " +
                "outside this policy"
        ),
        Section(
            "8. Permissions",
            "• Notifications help keep a Linux session alive in the background\n" +
                "• Storage access is optional and is used for backups and for mounting phone " +
                "files inside Linux if you enable that\n" +
                "• Both can be denied; Linux still runs"
        ),
        Section(
            "9. Children",
            "The app is not directed at children under 13, and we do not knowingly collect " +
                "personal information from children. If you believe a child has provided " +
                "information, open an issue at $CONTACT_URL."
        ),
        Section(
            "10. Retention, access, and deletion",
            "This open-source build does not store crash reports or feedback on a remote " +
                "server, so there are no hosted rows to delete.\n\n" +
                "You can still copy or reset the local install id in Settings → Help & about → " +
                "$SETTINGS_PAGE_TITLE. Resetting the install id or uninstalling the app " +
                "clears that id on the device."
        ),
        Section(
            "11. Changes",
            "We may update this policy. The copy in the app is the current version. Material " +
                "changes are reflected by updating the Last updated date ($LAST_UPDATED)."
        ),
        Section(
            "12. Contact",
            "Privacy questions: $CONTACT_URL"
        )
    )

    fun markdown(kind: Kind): String {
        return buildString {
            appendLine("# ${kind.title}")
            appendLine()
            appendLine("Last updated: $LAST_UPDATED")
            appendLine()
            kind.sections.forEach { section ->
                appendLine("## ${section.heading}")
                appendLine()
                appendLine(section.body)
                appendLine()
            }
        }.trimEnd() + "\n"
    }

    fun markAccepted(context: Context) {
        prefs(context).edit().putBoolean(PREF_LEGAL_ACCEPTED, true).apply()
    }

    /**
     * New installs accept during onboarding. Existing installs that already
     * finished onboarding are treated as accepted so they are not locked out.
     */
    fun hasAccepted(context: Context): Boolean {
        val p = prefs(context)
        if (p.getBoolean(PREF_LEGAL_ACCEPTED, false)) return true
        return p.getBoolean(PREF_ONBOARDING_COMPLETED, false)
    }

    /** Agree and continue: accept the Terms and Privacy Policy. */
    fun accept(context: Context) {
        markAccepted(context)
    }

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
}
