package app.polarbear

class KeyboardAccessibilityService {
    external fun nativeOnKeyEvent(action: Int, keyCode: Int, scanCode: Int, eventTimeMs: Long): Boolean
}
