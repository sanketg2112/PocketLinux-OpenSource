package com.sg.linuxgo

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.preference.PreferenceManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.sg.linuxgo.x11.LorieView
import java.io.File

/** Prompts */

internal fun MainActivity.showChoicePrompt(step: Bootstrap.SetupStep, choices: List<Bootstrap.Choice>) {
    runOnUiThread {
        SetupPromptUi.showChoicePrompt(
            context = this,
            promptContainer = promptContainer,
            tvStepDescription = tvStepDescription,
            step = step,
            choices = choices,
            onConsumed = {
                pendingStep = null
                pendingChoices = null
            }
        )
    }
}

internal fun MainActivity.showInputPrompt(step: Bootstrap.SetupStep, title: String, hint: String) {
    runOnUiThread {
        SetupPromptUi.showInputPrompt(
            context = this,
            promptContainer = promptContainer,
            tvStepDescription = tvStepDescription,
            step = step,
            title = title,
            hint = hint,
            onConsumed = {
                pendingStep = null
                pendingInputHint = null
            }
        )
    }
}

internal fun MainActivity.getStepTitle(step: Bootstrap.SetupStep): String = SetupPromptUi.getStepTitle(step)

internal fun MainActivity.setupTerminalInput() {
    TerminalInputSupport.setupTerminalInput(
        etTerminalInput = etTerminalInput,
        isResetting = { isResettingTerminalInputText },
        setResetting = { isResettingTerminalInputText = it },
        writeToTerminal = { writeToTerminal(it) },
        applicationCursor = {
            terminalSessions.getOrNull(activeSessionIndex)?.emulator?.applicationCursorKeys == true
        },
        applicationKeypad = {
            terminalSessions.getOrNull(activeSessionIndex)?.emulator?.applicationKeypad == true
        }
    )
}

internal fun MainActivity.onTerminalKeyPressed(keyName: String) {
    val session = terminalSessions.getOrNull(activeSessionIndex)
    val appCursor = session?.emulator?.applicationCursorKeys == true
    TerminalInputSupport.onTerminalKeyPressed(
        keyName = keyName,
        writeToTerminal = { writeToTerminal(it) },
        toggleCtrl = { isCtrlActive = !isCtrlActive },
        toggleAlt = { isAltActive = !isAltActive },
        toggleShift = { isShiftActive = !isShiftActive },
        toggleFn = { isFnActive = !isFnActive },
        applicationCursor = appCursor,
        onCopy = { copyActiveTerminalSelection() },
        onPaste = { pasteIntoActiveTerminal() }
    )
}

internal fun MainActivity.copyActiveTerminalSelection() {
    val session = terminalSessions.getOrNull(activeSessionIndex) ?: return
    val text = session.transcriptText()
    if (text.isBlank()) return
    TerminalClipboard.writeText(this, text)
    android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show()
}

internal fun MainActivity.pasteIntoActiveTerminal() {
    val session = terminalSessions.getOrNull(activeSessionIndex) ?: return
    val payload = TerminalClipboard.pastePayload(this, session.emulator.bracketedPaste)
    if (payload.isNotEmpty()) writeToTerminal(payload)
}

internal fun MainActivity.onGuiKeyPressed(keyName: String) {
    GuiKeySupport.onGuiKeyPressed(
        keyName = keyName,
        lorieView = lorieView,
        isGuiCtrlActive = { isGuiCtrlActive },
        setGuiCtrlActive = { isGuiCtrlActive = it },
        isGuiAltActive = { isGuiAltActive },
        setGuiAltActive = { isGuiAltActive = it },
        isGuiShiftActive = { isGuiShiftActive },
        setGuiShiftActive = { isGuiShiftActive = it },
        isGuiFnActive = { isGuiFnActive },
        setGuiFnActive = { isGuiFnActive = it }
    )
}

