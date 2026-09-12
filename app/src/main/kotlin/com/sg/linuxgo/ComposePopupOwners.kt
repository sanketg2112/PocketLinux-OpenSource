package com.sg.linuxgo

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Compose inside a [PopupWindow] builds its window recomposer on
 * `PopupWindow$PopupDecorView`, which only exists after [PopupWindow.showAtLocation]
 * and does **not** inherit [ViewTreeLifecycleOwner] from the content view.
 *
 * Missing owners → process crash:
 * `IllegalStateException: ViewTreeLifecycleOwner not found from PopupWindow$PopupDecorView`
 * (Crash H — GUI launch / session pill).
 */
object ComposePopupOwners {

    fun installOnViewTree(root: View, lifecycleOwner: LifecycleOwner) {
        val saved = lifecycleOwner as? SavedStateRegistryOwner
        val vm = lifecycleOwner as? ViewModelStoreOwner

        fun View.applyOwners() {
            setViewTreeLifecycleOwner(lifecycleOwner)
            if (saved != null) setViewTreeSavedStateRegistryOwner(saved)
            if (vm != null) setViewTreeViewModelStoreOwner(vm)
        }

        // Walk up to the popup window root (PopupDecorView).
        var cur: View? = root
        while (cur != null) {
            cur.applyOwners()
            cur = cur.parent as? View
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                root.getChildAt(i).applyOwners()
            }
        }
    }

    fun installOnPopup(popup: PopupWindow, lifecycleOwner: LifecycleOwner) {
        val content = popup.contentView ?: return
        installOnViewTree(content, lifecycleOwner)
    }

    /**
     * Content host that installs owners on its parent **before** child
     * [androidx.compose.ui.platform.ComposeView] attaches and starts composition.
     * ViewGroup attaches parent first, then children — this is the safe window.
     */
    fun contentContainer(context: Context, lifecycleOwner: LifecycleOwner): FrameLayout {
        return object : FrameLayout(context) {
            override fun onAttachedToWindow() {
                (parent as? View)?.let { installOnViewTree(it, lifecycleOwner) }
                installOnViewTree(this, lifecycleOwner)
                super.onAttachedToWindow()
            }
        }.also { installOnViewTree(it, lifecycleOwner) }
    }
}
