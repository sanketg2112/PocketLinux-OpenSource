package com.sg.linuxgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PillPopupWindowSafetyTest {

    @Test
    fun activityUsableRequiresAttachedDecorAndNotDestroyed() {
        assertTrue(PillPopupWindowSafety.isActivityWindowUsable(false, true))
        assertFalse(PillPopupWindowSafety.isActivityWindowUsable(true, true))
        assertFalse(PillPopupWindowSafety.isActivityWindowUsable(false, false))
        assertFalse(PillPopupWindowSafety.isActivityWindowUsable(true, false))
    }

    @Test
    fun popupUpdateUnsafeWhenNotShowingOrDetached() {
        assertTrue(
            PillPopupWindowSafety.isPopupSafeToUpdate(
                activityUsable = true,
                popupIsShowing = true,
                contentAttached = true,
            )
        )
        // contentAttached null = unknown, allow if showing (normal after create)
        assertTrue(
            PillPopupWindowSafety.isPopupSafeToUpdate(
                activityUsable = true,
                popupIsShowing = true,
                contentAttached = null,
            )
        )
        assertFalse(
            PillPopupWindowSafety.isPopupSafeToUpdate(
                activityUsable = true,
                popupIsShowing = true,
                contentAttached = false,
            )
        )
        assertFalse(
            PillPopupWindowSafety.isPopupSafeToUpdate(
                activityUsable = true,
                popupIsShowing = false,
                contentAttached = true,
            )
        )
        assertFalse(
            PillPopupWindowSafety.isPopupSafeToUpdate(
                activityUsable = false,
                popupIsShowing = true,
                contentAttached = true,
            )
        )
    }
}
