package com.sg.linuxgo

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.sg.linuxgo.x11.LorieView
import com.sg.linuxgo.x11.input.InputStub

/**
 * Touch / pointer / keyboard wiring for the embedded Lorie (X11) view.
 */
object GuiLorieWiring {

    fun wireTouchAndPointer(
        lorieView: LorieView,
        guiInputHandler: GuiInputHandler
    ) {
        lorieView.setOnTouchListener { _, e ->
            guiInputHandler.handleLorieTouchEvent(e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            lorieView.setOnCapturedPointerListener { _, e ->
                guiInputHandler.handleLorieTouchEvent(e)
            }
        }
    }

    fun wireHoverScrollAndKeys(
        context: Context,
        lorieView: LorieView,
        guiScaleX: () -> Float,
        guiScaleY: () -> Float,
        onDoubleEscHome: () -> Unit
    ) {
        lorieView.setOnHoverListener { _, e ->
            val x = e.x * guiScaleX()
            val y = e.y * guiScaleY()
            lorieView.sendMouseEvent(x, y, InputStub.BUTTON_UNDEFINED, false, false)
            true
        }

        lorieView.setOnGenericMotionListener { _, e ->
            if (e.action == MotionEvent.ACTION_SCROLL) {
                val vScroll = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val hScroll = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                // Sensitivity increased and direction reversed as requested.
                // X11 expects positive for scroll down, but Android provides positive for scroll up.
                lorieView.sendMouseWheelEvent(-hScroll * 50f, -vScroll * 50f)
                return@setOnGenericMotionListener true
            }
            false
        }

        lorieView.isFocusable = true
        lorieView.isFocusableInTouchMode = true
        var doubleEscPressedOnce = false
        lorieView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
            ) {
                return@setOnKeyListener false
            }
            if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.action == KeyEvent.ACTION_DOWN) {
                if (doubleEscPressedOnce) {
                    doubleEscPressedOnce = false
                    onDoubleEscHome()
                    return@setOnKeyListener true
                }
                doubleEscPressedOnce = true
                Toast.makeText(context, "Press ESC again to go back", Toast.LENGTH_SHORT).show()
                Handler(Looper.getMainLooper()).postDelayed({
                    doubleEscPressedOnce = false
                }, 2000)
            }
            val pressed = event.action == KeyEvent.ACTION_DOWN
            lorieView.sendKeyEvent(0, keyCode, pressed)
            true
        }
    }

    fun wireSurfaceCallback(
        lorieView: LorieView,
        onScaleChanged: (Float, Float) -> Unit
    ) {
        lorieView.setCallback { surfaceWidth, surfaceHeight, screenWidth, screenHeight ->
            val scaleX = screenWidth.toFloat() / surfaceWidth.toFloat()
            val scaleY = screenHeight.toFloat() / surfaceHeight.toFloat()
            onScaleChanged(scaleX, scaleY)
            Log.d(
                "X11Interaction",
                "Scale updated: $scaleX x $scaleY (Surface: ${surfaceWidth}x${surfaceHeight}, X11: ${screenWidth}x${screenHeight})"
            )
            LorieView.sendWindowChange(screenWidth, screenHeight, 120, "builtin")
        }
    }

    fun hideKeyboardAndKeybar(context: Context, mainRoot: View, guiKeyBar: View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(mainRoot.windowToken, 0)
        guiKeyBar.visibility = View.GONE
    }
}
