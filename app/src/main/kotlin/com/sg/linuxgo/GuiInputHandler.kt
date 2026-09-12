package com.sg.linuxgo

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import com.sg.linuxgo.x11.input.InputStub
import com.sg.linuxgo.x11.LorieView

class GuiInputHandler(
    private val context: Context,
    private val lorieView: LorieView,
    private val guiScaleXProvider: () -> Float,
    private val guiScaleYProvider: () -> Float
) {
    private var isLongPressPending = false
    private var isLongPressDragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastScrollY = 0f

    private var isTwoFingerTouch = false
    private var hasTwoFingerMoved = false
    private var isWindowDragging = false
    private var isDoubleTouchOnTopBar = false

    val gestureDetector: GestureDetector by lazy {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
                val touchMode = sharedPrefs.getString("touchMode", "1") ?: "1"
                if (touchMode == "2") {
                    val x = e.x * guiScaleXProvider()
                    val y = e.y * guiScaleYProvider()
                    lorieView.sendMouseEvent(x, y, InputStub.BUTTON_LEFT, true, false)
                    lorieView.sendMouseEvent(x, y, InputStub.BUTTON_LEFT, false, false)
                } else if (touchMode == "1") {
                    lorieView.sendMouseEvent(0f, 0f, InputStub.BUTTON_LEFT, true, true)
                    lorieView.sendMouseEvent(0f, 0f, InputStub.BUTTON_LEFT, false, true)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
                val touchMode = sharedPrefs.getString("touchMode", "1") ?: "1"
                if (touchMode == "2") {
                    val x = e.x * guiScaleXProvider()
                    val y = e.y * guiScaleYProvider()
                    lorieView.sendMouseEvent(x, y, InputStub.BUTTON_LEFT, true, false)
                    lorieView.sendMouseEvent(x, y, InputStub.BUTTON_LEFT, false, false)
                    lorieView.sendMouseEvent(x, y, InputStub.BUTTON_LEFT, true, false)
                    lorieView.sendMouseEvent(x, y, InputStub.BUTTON_LEFT, false, false)
                } else if (touchMode == "1") {
                    lorieView.sendMouseEvent(0f, 0f, InputStub.BUTTON_LEFT, true, true)
                    lorieView.sendMouseEvent(0f, 0f, InputStub.BUTTON_LEFT, false, true)
                    lorieView.sendMouseEvent(0f, 0f, InputStub.BUTTON_LEFT, true, true)
                    lorieView.sendMouseEvent(0f, 0f, InputStub.BUTTON_LEFT, false, true)
                }
                return true
            }

            override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                isLongPressPending = true
                isLongPressDragging = false
            }
        })
    }

    fun handleLorieTouchEvent(e: MotionEvent): Boolean {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val touchMode = sharedPrefs.getString("touchMode", "1") ?: "1"
        val scaleTouchpad = sharedPrefs.getBoolean("scaleTouchpad", true)
        val speedFactor = sharedPrefs.getInt("capturedPointerSpeedFactor", 100) / 100f
        val stylusIsMouse = sharedPrefs.getBoolean("stylusIsMouse", false)

        val action = e.actionMasked
        val index = e.actionIndex
        val id = e.getPointerId(index)
        val toolType = e.getToolType(0)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        val isMouse = toolType == MotionEvent.TOOL_TYPE_MOUSE || (isStylus && stylusIsMouse)

        val hasCapture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) lorieView.hasPointerCapture() else false

        val x = e.x * guiScaleXProvider()
        val y = e.y * guiScaleYProvider()

        if (!isMouse && touchMode != "3") {
            gestureDetector.onTouchEvent(e)
        }

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                if (isMouse) {
                    val button = getStubButton(e.buttonState)
                    if (button != InputStub.BUTTON_UNDEFINED) {
                        lorieView.sendMouseEvent(x, y, button, true, false)
                    }
                } else if (touchMode == "2") {
                    lorieView.sendMouseEvent(x, y, InputStub.BUTTON_UNDEFINED, false, false)
                    isLongPressPending = false
                    isLongPressDragging = false
                    lastTouchX = e.x
                    lastTouchY = e.y

                    isTwoFingerTouch = false
                    hasTwoFingerMoved = false
                    isWindowDragging = false
                    isDoubleTouchOnTopBar = false
                } else if (touchMode == "1") {
                    isLongPressPending = false
                    isLongPressDragging = false
                    lastTouchX = e.x
                    lastTouchY = e.y

                    isTwoFingerTouch = false
                    hasTwoFingerMoved = false
                    isWindowDragging = false
                    isDoubleTouchOnTopBar = false
                }
                lorieView.requestFocus()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (isMouse) {
                    lorieView.sendMouseEvent(x, y, InputStub.BUTTON_LEFT, false, false)
                    lorieView.sendMouseEvent(x, y, InputStub.BUTTON_RIGHT, false, false)
                    lorieView.sendMouseEvent(x, y, InputStub.BUTTON_MIDDLE, false, false)
                    lorieView.sendMouseEvent(x, y, 8, false, false)
                    lorieView.sendMouseEvent(x, y, 9, false, false)
                } else {
                    val isRelative = (touchMode == "1")
                    if (isTwoFingerTouch && !hasTwoFingerMoved && touchMode != "3") {
                        isTwoFingerTouch = false
                        lorieView.sendMouseEvent(if (isRelative) 0f else x, if (isRelative) 0f else y, InputStub.BUTTON_RIGHT, true, isRelative)
                        lorieView.sendMouseEvent(if (isRelative) 0f else x, if (isRelative) 0f else y, InputStub.BUTTON_RIGHT, false, isRelative)
                    }
                    if (isWindowDragging) {
                        lorieView.sendMouseEvent(if (isRelative) 0f else x, if (isRelative) 0f else y, InputStub.BUTTON_LEFT, false, isRelative)
                        isWindowDragging = false
                    }
                    isTwoFingerTouch = false

                    if (isLongPressPending) {
                        lorieView.sendMouseEvent(if (isRelative) 0f else x, if (isRelative) 0f else y, InputStub.BUTTON_RIGHT, true, isRelative)
                        lorieView.sendMouseEvent(if (isRelative) 0f else x, if (isRelative) 0f else y, InputStub.BUTTON_RIGHT, false, isRelative)
                        isLongPressPending = false
                    } else if (isLongPressDragging) {
                        lorieView.sendMouseEvent(if (isRelative) 0f else x, if (isRelative) 0f else y, InputStub.BUTTON_LEFT, false, isRelative)
                        isLongPressDragging = false
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                isLongPressPending = false
                isLongPressDragging = false
                if (isWindowDragging) {
                    val isRelative = (touchMode == "1")
                    lorieView.sendMouseEvent(if (isRelative) 0f else x, if (isRelative) 0f else y, InputStub.BUTTON_LEFT, false, isRelative)
                    isWindowDragging = false
                }
                isTwoFingerTouch = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (isMouse) {
                    if (hasCapture) {
                        val dx = e.x * speedFactor
                        val dy = e.y * speedFactor
                        lorieView.sendMouseEvent(dx, dy, InputStub.BUTTON_UNDEFINED, false, true)
                    } else {
                        lorieView.sendMouseEvent(x, y, InputStub.BUTTON_UNDEFINED, false, false)
                    }
                } else if (touchMode == "1" && e.pointerCount == 1) {
                    var dx = (e.x - lastTouchX)
                    var dy = (e.y - lastTouchY)
                    
                    if (scaleTouchpad) {
                        dx *= guiScaleXProvider()
                        dy *= guiScaleYProvider()
                    }
                    dx *= speedFactor
                    dy *= speedFactor
                    
                    if (isLongPressPending && (dx != 0f || dy != 0f)) {
                        isLongPressPending = false
                        isLongPressDragging = true
                        lorieView.sendMouseEvent(0f, 0f, InputStub.BUTTON_LEFT, true, true)
                    }

                    if (dx != 0f || dy != 0f) {
                        lorieView.sendMouseEvent(dx, dy, InputStub.BUTTON_UNDEFINED, isLongPressDragging, true)
                    }
                    
                    lastTouchX = e.x
                    lastTouchY = e.y
                } else if (touchMode == "2" && e.pointerCount == 1) {
                    val dx = (e.x - lastTouchX)
                    val dy = (e.y - lastTouchY)
                    
                    if (isLongPressPending && (dx != 0f || dy != 0f)) {
                        isLongPressPending = false
                        isLongPressDragging = true
                        lorieView.sendMouseEvent(x, y, InputStub.BUTTON_LEFT, true, false)
                    }
                    
                    lorieView.sendMouseEvent(x, y, InputStub.BUTTON_UNDEFINED, isLongPressDragging, false)
                    
                    lastTouchX = e.x
                    lastTouchY = e.y
                } else if (e.pointerCount == 2 && touchMode != "3") {
                    if (isDoubleTouchOnTopBar) {
                        val isRelative = (touchMode == "1")
                        val x0 = e.getX(0) * guiScaleXProvider()
                        val y0 = e.getY(0) * guiScaleYProvider()

                        var dx = e.getX(0) - lastTouchX
                        var dy = e.getY(0) - lastTouchY

                        if (isRelative) {
                            if (scaleTouchpad) {
                                dx *= guiScaleXProvider()
                                dy *= guiScaleYProvider()
                            }
                            dx *= speedFactor
                            dy *= speedFactor
                        }

                        if (Math.abs(dx) > 1f || Math.abs(dy) > 1f) {
                            hasTwoFingerMoved = true
                            if (!isWindowDragging) {
                                isWindowDragging = true
                                lorieView.sendMouseEvent(if (isRelative) 0f else x0, if (isRelative) 0f else y0, InputStub.BUTTON_LEFT, true, isRelative)
                            }
                            lorieView.sendMouseEvent(if (isRelative) dx else x0, if (isRelative) dy else y0, InputStub.BUTTON_UNDEFINED, true, isRelative)
                        }
                        lastTouchX = e.getX(0)
                        lastTouchY = e.getY(0)
                    } else {
                        val dy = e.getY(0) - lastScrollY
                        if (Math.abs(dy) > 1) {
                            hasTwoFingerMoved = true
                            lorieView.sendMouseWheelEvent(0f, -dy)
                            lastScrollY = e.getY(0)
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!isMouse && e.pointerCount == 2 && touchMode != "3") {
                    isLongPressPending = false
                    lastScrollY = e.getY(0)

                    isTwoFingerTouch = true
                    hasTwoFingerMoved = false

                    val y0 = e.getY(0) * guiScaleYProvider()
                    val y1 = e.getY(1) * guiScaleYProvider()
                    val topBarThreshold = 150f
                    isDoubleTouchOnTopBar = ((y0 + y1) / 2f) < topBarThreshold

                    lastTouchX = e.getX(0)
                    lastTouchY = e.getY(0)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (!isMouse && e.pointerCount == 2 && touchMode != "3") {
                    val x0 = e.getX(0) * guiScaleXProvider()
                    val y0 = e.getY(0) * guiScaleYProvider()
                    if (isTwoFingerTouch && !hasTwoFingerMoved) {
                        isTwoFingerTouch = false
                        val isRelative = (touchMode == "1")
                        lorieView.sendMouseEvent(if (isRelative) 0f else x0, if (isRelative) 0f else y0, InputStub.BUTTON_RIGHT, true, isRelative)
                        lorieView.sendMouseEvent(if (isRelative) 0f else x0, if (isRelative) 0f else y0, InputStub.BUTTON_RIGHT, false, isRelative)
                    }
                    if (isWindowDragging) {
                        val isRelative = (touchMode == "1")
                        lorieView.sendMouseEvent(if (isRelative) 0f else x0, if (isRelative) 0f else y0, InputStub.BUTTON_LEFT, false, isRelative)
                        isWindowDragging = false
                    }
                    isTwoFingerTouch = false

                    val remainingIndex = if (e.actionIndex == 0) 1 else 0
                    lastTouchX = e.getX(remainingIndex)
                    lastTouchY = e.getY(remainingIndex)
                }
            }
        }

        if (touchMode == "3" && !isMouse) {
            val tx = (e.getX(index) * guiScaleXProvider()).toInt()
            val ty = (e.getY(index) * guiScaleYProvider()).toInt()
            lorieView.sendTouchEvent(action, id, tx, ty)
        }

        return true
    }

    private fun getStubButton(buttonState: Int): Int {
        return when {
            (buttonState and MotionEvent.BUTTON_PRIMARY) != 0 -> InputStub.BUTTON_LEFT
            (buttonState and MotionEvent.BUTTON_SECONDARY) != 0 -> InputStub.BUTTON_RIGHT
            (buttonState and MotionEvent.BUTTON_TERTIARY) != 0 -> InputStub.BUTTON_MIDDLE
            (buttonState and MotionEvent.BUTTON_BACK) != 0 -> 8
            (buttonState and MotionEvent.BUTTON_FORWARD) != 0 -> 9
            else -> InputStub.BUTTON_UNDEFINED
        }
    }
}
