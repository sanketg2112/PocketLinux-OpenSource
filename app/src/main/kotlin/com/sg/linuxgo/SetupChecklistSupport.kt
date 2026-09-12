package com.sg.linuxgo

import android.view.View
import android.widget.TextView

/**
 * Maps install progress messages onto the 6-step setup checklist views.
 */
object SetupChecklistSupport {

    fun updateChecklist(
        checklistContainer: View,
        step1: TextView,
        step2: TextView,
        step3: TextView,
        step4: TextView,
        step5: TextView,
        step6: TextView,
        msg: String
    ) {
        checklistContainer.visibility = View.VISIBLE
        val msgLower = msg.lowercase()

        fun markCompleted(vararg steps: TextView) {
            steps.forEach {
                it.setTextColor(0xFFFFFFFF.toInt())
                if (it.text.startsWith("[ ]")) {
                    it.text = it.text.toString().replaceFirst("[ ]", "[✓]")
                }
            }
        }
        fun markActive(step: TextView) {
            step.setTextColor(0xFF00E5FF.toInt())
            if (step.text.startsWith("[✓]")) {
                step.text = step.text.toString().replaceFirst("[✓]", "[ ]")
            }
        }

        when {
            msgLower.contains("benchmarking") || msgLower.contains("system context") -> markActive(step1)
            msgLower.contains("downloading") -> {
                markCompleted(step1); markActive(step2)
            }
            msgLower.contains("extracting") -> {
                markCompleted(step1, step2); markActive(step3)
            }
            msgLower.contains("executing native setup") || msgLower.contains("configuring") -> {
                markCompleted(step1, step2, step3); markActive(step4)
            }
            msgLower.contains("installing optional") || msgLower.contains("installing software") -> {
                markCompleted(step1, step2, step3, step4); markActive(step5)
            }
            msgLower.contains("installation complete") || msgLower.contains("success") || msgLower.contains("ready") -> {
                markCompleted(step1, step2, step3, step4, step5, step6)
            }
        }
    }

    fun isLogWorthyProgressMessage(cleanMsg: String): Boolean {
        return cleanMsg.startsWith("Starting setup") ||
            cleanMsg.startsWith("Container preset") ||
            cleanMsg.startsWith("Fetching container catalog") ||
            cleanMsg.startsWith("Downloading container") ||
            cleanMsg.startsWith("Verifying image") ||
            cleanMsg.startsWith("Installing container") ||
            cleanMsg.startsWith("Installing ") ||
            cleanMsg.startsWith("Finalizing") ||
            cleanMsg.startsWith("Downloading") && cleanMsg.contains("rootfs") ||
            cleanMsg.startsWith("Extracting rootfs") ||
            cleanMsg.startsWith("Executing native setup script") ||
            cleanMsg.startsWith("Installing Package") ||
            cleanMsg.startsWith("✓ Successfully") ||
            cleanMsg.startsWith("⚠ Failed to install") ||
            (cleanMsg.contains("Phase ") && cleanMsg.contains("/8") && !cleanMsg.endsWith(".")) ||
            cleanMsg.contains("Benchmarking mirrors") ||
            cleanMsg.contains("Fastest mirror") ||
            cleanMsg.startsWith("✓ Install script completed") ||
            cleanMsg.startsWith("✓ Rootfs already") ||
            cleanMsg.startsWith("✓ Found cached") ||
            cleanMsg.startsWith("✓ Using cached image") ||
            cleanMsg.startsWith("✓ Image verified") ||
            cleanMsg.startsWith("✓ Install complete") ||
            cleanMsg.contains("link_shim")
    }

    fun isHighLevelProgressMessage(cleanMsg: String): Boolean {
        return cleanMsg.contains("Phase ") && cleanMsg.contains("/8") ||
            cleanMsg.startsWith("Starting setup") ||
            cleanMsg.startsWith("Fetching container catalog") ||
            cleanMsg.startsWith("Downloading container") ||
            cleanMsg.startsWith("Verifying image") ||
            cleanMsg.startsWith("Installing container") ||
            cleanMsg.startsWith("Finalizing") ||
            cleanMsg.startsWith("Finishing setup") ||
            (cleanMsg.contains("Downloading") && cleanMsg.contains("rootfs")) ||
            cleanMsg.startsWith("Extracting rootfs") ||
            cleanMsg.startsWith("Executing native setup script") ||
            cleanMsg.startsWith("✓ Install complete") ||
            cleanMsg.startsWith("✓ Using cached image") ||
            cleanMsg.startsWith("✓ Image verified")
    }
}
