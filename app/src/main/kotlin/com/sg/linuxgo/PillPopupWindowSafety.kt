package com.sg.linuxgo

/**
 * Pure predicates for Crash F (PopupWindow not attached to window manager).
 * Used by [PillPopupController] before any [android.widget.PopupWindow.update] / show.
 */
object PillPopupWindowSafety {

    /**
     * @param activityFinishingOrDestroyed true if finishing/destroyed
     * @param decorAttached activity window decor is attached
     */
    fun isActivityWindowUsable(
        activityFinishingOrDestroyed: Boolean,
        decorAttached: Boolean,
    ): Boolean = !activityFinishingOrDestroyed && decorAttached

    /**
     * Whether [android.widget.PopupWindow.update] is safe to call.
     * Some OEMs report isShowing after content is already detached.
     */
    fun isPopupSafeToUpdate(
        activityUsable: Boolean,
        popupIsShowing: Boolean,
        contentAttached: Boolean?,
    ): Boolean {
        if (!activityUsable || !popupIsShowing) return false
        if (contentAttached == false) return false
        return true
    }
}
