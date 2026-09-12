package com.sg.linuxgo

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText

/**
 * Terminal soft-input wiring and modifier-key handling for the Compose terminal.
 */
object TerminalInputSupport {

    /**
     * Wire [et] as the terminal IME host.
     * Prefer [TerminalImeEditText] (InputConnection-based). Falls back to legacy
     * TextWatcher for a plain [EditText].
     */
    fun setupTerminalInput(
        etTerminalInput: EditText,
        isResetting: () -> Boolean,
        setResetting: (Boolean) -> Unit,
        writeToTerminal: (String) -> Unit,
        applicationCursor: () -> Boolean = { false },
        applicationKeypad: () -> Boolean = { false }
    ) {
        if (etTerminalInput is TerminalImeEditText) {
            setupImeEditText(etTerminalInput, writeToTerminal, applicationCursor, applicationKeypad)
            return
        }
        setupLegacyTextWatcher(etTerminalInput, isResetting, setResetting, writeToTerminal)
    }

    private fun setupImeEditText(
        et: TerminalImeEditText,
        writeToTerminal: (String) -> Unit,
        applicationCursor: () -> Boolean,
        applicationKeypad: () -> Boolean
    ) {
        et.applicationCursorKeys = applicationCursor
        et.applicationKeypad = applicationKeypad
        // Do NOT call stabilizeBuffer after every key — setText/setSelection restarts the
        // InputConnection mid-word on many IMEs and makes the soft keyboard appear dead.
        et.onImeCommit = { text ->
            if (text.isNotEmpty()) writeToTerminal(text)
        }
        et.onImeBackspace = {
            writeToTerminal("\u007F")
        }
        et.onImeEnter = {
            writeToTerminal("\r")
        }

        // Hardware / external keyboards: OnKeyListener runs before onKeyDown.
        // TerminalImeEditText.handleHardwareKey forwards printable + special keys to the PTY
        // (soft keyboard uses InputConnection and does not rely on this path for letters).
        et.setOnKeyListener { _, _, event ->
            et.handleHardwareKey(event)
        }

        et.stabilizeBuffer()
    }

    /** Legacy path kept for safety if a plain EditText is ever passed in. */
    private fun setupLegacyTextWatcher(
        etTerminalInput: EditText,
        isResetting: () -> Boolean,
        setResetting: (Boolean) -> Unit,
        writeToTerminal: (String) -> Unit
    ) {
        fun resetBuffer() {
            setResetting(true)
            try {
                etTerminalInput.setText("\u200B")
                etTerminalInput.setSelection(etTerminalInput.text?.length ?: 0)
            } finally {
                setResetting(false)
            }
        }

        resetBuffer()

        etTerminalInput.setOnEditorActionListener { _, actionId, event ->
            val isEnter = actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                actionId == EditorInfo.IME_ACTION_NONE ||
                actionId == EditorInfo.IME_ACTION_UNSPECIFIED ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (isEnter) {
                if (event == null || event.action == KeyEvent.ACTION_DOWN) {
                    writeToTerminal("\r")
                    resetBuffer()
                }
                true
            } else {
                false
            }
        }

        etTerminalInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        writeToTerminal("\r"); resetBuffer(); true
                    }
                    KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                        writeToTerminal("\u007F"); resetBuffer(); true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        writeToTerminal("\u001B[A"); true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        writeToTerminal("\u001B[B"); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        writeToTerminal("\u001B[D"); true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        writeToTerminal("\u001B[C"); true
                    }
                    else -> false
                }
            } else {
                false
            }
        }

        etTerminalInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isResetting()) return
                if (s == null) return
                val newText = s.toString().replace("\u200B", "")
                if (newText.isEmpty()) return
                val normalized = newText.replace("\r\n", "\n").replace('\r', '\n')
                val parts = normalized.split('\n')
                for (i in parts.indices) {
                    if (parts[i].isNotEmpty()) writeToTerminal(parts[i])
                    if (i < parts.lastIndex) writeToTerminal("\r")
                }
                resetBuffer()
            }
        })
    }

    fun onTerminalKeyPressed(
        keyName: String,
        writeToTerminal: (String) -> Unit,
        toggleCtrl: () -> Unit,
        toggleAlt: () -> Unit,
        toggleShift: () -> Unit,
        toggleFn: () -> Unit,
        applicationCursor: Boolean = false,
        onCopy: (() -> Unit)? = null,
        onPaste: (() -> Unit)? = null
    ) {
        when (keyName) {
            "COPY" -> {
                onCopy?.invoke() ?: writeToTerminal("\u0003")
            }
            "PASTE" -> {
                onPaste?.invoke() ?: writeToTerminal("")
            }
            "SAVE" -> writeToTerminal("\u0013")
            "CTRL" -> toggleCtrl()
            "ALT" -> toggleAlt()
            "SHIFT" -> toggleShift()
            "FN" -> toggleFn()
            "SETTINGS_ACTION", "SFTP_ACTION", "MACRO_ACTION" -> Unit
            else -> {
                val mapped = TerminalKeyHandler.extraKeyName(keyName, applicationCursor)
                if (mapped != null) {
                    writeToTerminal(mapped)
                } else if (keyName.isNotEmpty()) {
                    writeToTerminal(com.sg.linuxgo.ui.components.resolveKeyPayload(keyName))
                }
            }
        }
    }

    fun writeToTerminal(
        command: String,
        sessions: List<TerminalSession>,
        activeSessionIndex: Int,
        isShiftActive: () -> Boolean,
        setShiftActive: (Boolean) -> Unit,
        isFnActive: () -> Boolean,
        setFnActive: (Boolean) -> Unit,
        isCtrlActive: () -> Boolean,
        setCtrlActive: (Boolean) -> Unit,
        isAltActive: () -> Boolean,
        setAltActive: (Boolean) -> Unit,
        hideCursor: () -> Unit,
        runOnUiThread: (() -> Unit) -> Unit
    ) {
        if (activeSessionIndex < 0 || activeSessionIndex >= sessions.size) return
        val session = sessions[activeSessionIndex]
        if (command == "\r" || command == "\n") {
            session.isCommandRunning = true
            runOnUiThread { hideCursor() }
        }
        var finalCommand = command
        // Modifier UI is Compose-driven (ViewModel state). Clearing the flags is enough —
        // never look up legacy XML key-button views (they are not in the view registry).
        if (isShiftActive() && finalCommand.length == 1) {
            val c = finalCommand[0]
            finalCommand = if (c.isLowerCase()) c.uppercaseChar().toString() else c.toString()
            setShiftActive(false)
        }
        if (isFnActive() && finalCommand.length == 1) {
            val c = finalCommand[0]
            val mapped = when (c) {
                '1' -> "\u001BOP"
                '2' -> "\u001BOQ"
                '3' -> "\u001BOR"
                '4' -> "\u001BOS"
                '5' -> "\u001B[15~"
                '6' -> "\u001B[17~"
                '7' -> "\u001B[18~"
                '8' -> "\u001B[19~"
                '9' -> "\u001B[20~"
                '0' -> "\u001B[21~"
                else -> null
            }
            if (mapped != null) {
                finalCommand = mapped
            }
            setFnActive(false)
        }
        if (isCtrlActive() && finalCommand.length == 1) {
            val c = finalCommand[0].uppercaseChar()
            if (c in 'A'..'Z') {
                finalCommand = (c.code - 'A'.code + 1).toChar().toString()
            } else if (c == '[') {
                finalCommand = "\u001B"
            }
            setCtrlActive(false)
        } else if (isAltActive() && finalCommand.length == 1) {
            val c = finalCommand[0]
            finalCommand = "\u001B$c"
            setAltActive(false)
        }
        session.write(finalCommand)
    }
}
