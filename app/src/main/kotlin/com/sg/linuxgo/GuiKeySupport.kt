package com.sg.linuxgo

import android.view.KeyEvent
import com.sg.linuxgo.x11.LorieView

/**
 * Maps Compose GUI key-bar names to X11 key events.
 */
object GuiKeySupport {

    fun onGuiKeyPressed(
        keyName: String,
        lorieView: LorieView,
        isGuiCtrlActive: () -> Boolean,
        setGuiCtrlActive: (Boolean) -> Unit,
        isGuiAltActive: () -> Boolean,
        setGuiAltActive: (Boolean) -> Unit,
        isGuiShiftActive: () -> Boolean,
        setGuiShiftActive: (Boolean) -> Unit,
        isGuiFnActive: () -> Boolean,
        setGuiFnActive: (Boolean) -> Unit
    ) {
        when (keyName) {
            "ESC" -> sendGuiKey(lorieView, KeyEvent.KEYCODE_ESCAPE)
            "TAB" -> sendGuiKey(lorieView, KeyEvent.KEYCODE_TAB)
            "ENTER" -> sendGuiKey(lorieView, KeyEvent.KEYCODE_ENTER)
            "LEFT" -> sendGuiKey(lorieView, KeyEvent.KEYCODE_DPAD_LEFT)
            "UP" -> sendGuiKey(lorieView, KeyEvent.KEYCODE_DPAD_UP)
            "DOWN" -> sendGuiKey(lorieView, KeyEvent.KEYCODE_DPAD_DOWN)
            "RIGHT" -> sendGuiKey(lorieView, KeyEvent.KEYCODE_DPAD_RIGHT)
            "HOME" -> sendGuiKey(lorieView, KeyEvent.KEYCODE_MOVE_HOME)
            "END" -> sendGuiKey(lorieView, KeyEvent.KEYCODE_MOVE_END)
            "PGUP" -> sendGuiKey(lorieView, KeyEvent.KEYCODE_PAGE_UP)
            "PGDN" -> sendGuiKey(lorieView, KeyEvent.KEYCODE_PAGE_DOWN)
            "INS" -> sendGuiKey(lorieView, KeyEvent.KEYCODE_INSERT)
            "DEL" -> sendGuiKey(lorieView, KeyEvent.KEYCODE_FORWARD_DEL)
            "COPY" -> sendShortcut(lorieView, KeyEvent.KEYCODE_C)
            "PASTE" -> sendShortcut(lorieView, KeyEvent.KEYCODE_V)
            "SAVE" -> sendShortcut(lorieView, KeyEvent.KEYCODE_S)
            "CTRL" -> {
                val next = !isGuiCtrlActive()
                setGuiCtrlActive(next)
                lorieView.sendKeyEvent(0, KeyEvent.KEYCODE_CTRL_LEFT, next)
            }
            "ALT" -> {
                val next = !isGuiAltActive()
                setGuiAltActive(next)
                lorieView.sendKeyEvent(0, KeyEvent.KEYCODE_ALT_LEFT, next)
            }
            "SHIFT" -> {
                val next = !isGuiShiftActive()
                setGuiShiftActive(next)
                lorieView.sendKeyEvent(0, KeyEvent.KEYCODE_SHIFT_LEFT, next)
            }
            "FN" -> {
                val next = !isGuiFnActive()
                setGuiFnActive(next)
                lorieView.sendKeyEvent(0, KeyEvent.KEYCODE_FUNCTION, next)
            }
        }
    }

    fun sendShortcut(lorieView: LorieView, keyCode: Int) {
        lorieView.sendKeyEvent(0, KeyEvent.KEYCODE_CTRL_LEFT, true)
        lorieView.sendKeyEvent(0, keyCode, true)
        lorieView.sendKeyEvent(0, keyCode, false)
        lorieView.sendKeyEvent(0, KeyEvent.KEYCODE_CTRL_LEFT, false)
    }

    fun sendGuiKey(lorieView: LorieView, keyCode: Int) {
        lorieView.sendKeyEvent(0, keyCode, true)
        lorieView.sendKeyEvent(0, keyCode, false)
    }
}
