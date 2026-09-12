package com.sg.linuxgo

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Observable bridge views for Compose state.
 *
 * IMPORTANT: Android View super-constructors often call setText/setVisibility
 * before Kotlin subclass properties are initialized. Store callbacks only in
 * nullable fields assigned in init{}, and always use safe-call (?.) so parent
 * constructor callbacks are no-ops.
 */

class ObservableTextView(
    context: Context,
    onTextChange: (String) -> Unit,
    onVisibilityChange: ((Int) -> Unit)? = null
) : androidx.appcompat.widget.AppCompatTextView(context) {
    private var textListener: ((String) -> Unit)? = null
    private var visibilityListener: ((Int) -> Unit)? = null
    init {
        textListener = onTextChange
        visibilityListener = onVisibilityChange
    }
    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        textListener?.invoke(text?.toString() ?: "")
    }
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        visibilityListener?.invoke(visibility)
    }
}

class ObservableStepTextView(
    context: Context,
    stepIndex: Int,
    onStepUpdated: (Int, String, Int) -> Unit
) : androidx.appcompat.widget.AppCompatTextView(context) {
    private var index: Int = 0
    private var stepListener: ((Int, String, Int) -> Unit)? = null
    init {
        index = stepIndex
        stepListener = onStepUpdated
    }
    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        notifyStep()
    }
    override fun setTextColor(color: Int) {
        super.setTextColor(color)
        notifyStep()
    }
    private fun notifyStep() {
        stepListener?.invoke(index, text.toString(), textColors.defaultColor)
    }
}

class ObservableLogTextView(
    context: Context,
    onLogUpdated: (CharSequence) -> Unit
) : androidx.appcompat.widget.AppCompatTextView(context) {
    private var logListener: ((CharSequence) -> Unit)? = null
    init {
        logListener = onLogUpdated
        setText("", BufferType.EDITABLE)
        try {
            typeface = Typeface.createFromAsset(context.assets, "fonts/MesloLGS-NF-Regular.ttf")
        } catch (_: Exception) {
            typeface = Typeface.MONOSPACE
        }
        textSize = 12f
        setTextColor(0xFFABB2BF.toInt())
    }
    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        logListener?.invoke(text ?: "")
    }
    override fun append(text: CharSequence?, start: Int, end: Int) {
        super.append(text, start, end)
        logListener?.invoke(this.text.toString())
    }
}

class ObservableToggleLogsButton(
    context: Context,
    onTextChanged: (String) -> Unit
) : com.google.android.material.button.MaterialButton(context) {
    private var textListener: ((String) -> Unit)? = null
    init {
        textListener = onTextChanged
    }
    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        textListener?.invoke(text?.toString() ?: "")
    }
}

class ObservableLogCardView(
    context: Context,
    onVisibilityChanged: (Int) -> Unit
) : com.google.android.material.card.MaterialCardView(context) {
    private var visibilityListener: ((Int) -> Unit)? = null
    init {
        visibilityListener = onVisibilityChanged
    }
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        visibilityListener?.invoke(visibility)
    }
}

class ObservableMetricsCardView(
    context: Context,
    onVisibilityChanged: (Int) -> Unit
) : com.google.android.material.card.MaterialCardView(context) {
    private var visibilityListener: ((Int) -> Unit)? = null
    init {
        visibilityListener = onVisibilityChanged
    }
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        visibilityListener?.invoke(visibility)
    }
}

class ObservableInstallPathTextView(
    context: Context,
    onTextChanged: (String) -> Unit
) : androidx.appcompat.widget.AppCompatTextView(context) {
    private var textListener: ((String) -> Unit)? = null
    init {
        textListener = onTextChanged
    }
    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        textListener?.invoke(text?.toString() ?: "")
    }
}

class ObservableProgressBar(
    context: Context,
    onIndeterminateChanged: (Boolean) -> Unit,
    onProgressChanged: (Int, Int) -> Unit,
    onVisibilityChanged: (Int) -> Unit
) : com.google.android.material.progressindicator.LinearProgressIndicator(context) {
    private var indeterminateListener: ((Boolean) -> Unit)? = null
    private var progressListener: ((Int, Int) -> Unit)? = null
    private var visibilityListener: ((Int) -> Unit)? = null
    init {
        indeterminateListener = onIndeterminateChanged
        progressListener = onProgressChanged
        visibilityListener = onVisibilityChanged
    }
    override fun setIndeterminate(indeterminate: Boolean) {
        super.setIndeterminate(indeterminate)
        indeterminateListener?.invoke(indeterminate)
    }
    override fun setProgress(progress: Int) {
        super.setProgress(progress)
        progressListener?.invoke(progress, max)
    }
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        visibilityListener?.invoke(visibility)
    }
}

class ObservableContainerView(
    context: Context,
    screen: Screen,
    onActiveScreenChanged: (Screen) -> Unit
) : FrameLayout(context) {
    private var targetScreen: Screen = Screen.HOME
    private var screenListener: ((Screen) -> Unit)? = null
    init {
        targetScreen = screen
        screenListener = onActiveScreenChanged
    }
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == View.VISIBLE) {
            screenListener?.invoke(targetScreen)
        }
    }
}

class ObservableMaterialButton(
    context: Context,
    onVisibilityChange: ((Int) -> Unit)? = null
) : com.google.android.material.button.MaterialButton(context) {
    private var visibilityListener: ((Int) -> Unit)? = null
    init {
        visibilityListener = onVisibilityChange
    }
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        visibilityListener?.invoke(visibility)
    }
}

class ObservableHomeScrollView(
    context: Context,
    onActiveScreenChanged: (Screen) -> Unit
) : ScrollView(context) {
    private var screenListener: ((Screen) -> Unit)? = null
    init {
        screenListener = onActiveScreenChanged
    }
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == View.VISIBLE) {
            screenListener?.invoke(Screen.HOME)
        }
    }
}

class ObservableSetupScrollView(
    context: Context,
    onActiveScreenChanged: (Screen) -> Unit
) : ScrollView(context) {
    private var screenListener: ((Screen) -> Unit)? = null
    init {
        screenListener = onActiveScreenChanged
    }
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == View.VISIBLE) {
            screenListener?.invoke(Screen.SETUP)
        }
    }
}

class ObservableTerminalLinearLayout(
    context: Context,
    onActiveScreenChanged: (Screen) -> Unit
) : LinearLayout(context) {
    private var screenListener: ((Screen) -> Unit)? = null
    init {
        screenListener = onActiveScreenChanged
    }
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == View.VISIBLE) {
            screenListener?.invoke(Screen.TERMINAL)
        }
    }
}

class ObservableGuiFrameLayout(
    context: Context,
    onActiveScreenChanged: (Screen) -> Unit,
    onHomeButtonVisibilityChanged: (Boolean) -> Unit
) : FrameLayout(context) {
    private var screenListener: ((Screen) -> Unit)? = null
    private var homeButtonListener: ((Boolean) -> Unit)? = null
    init {
        screenListener = onActiveScreenChanged
        homeButtonListener = onHomeButtonVisibilityChanged
    }
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == View.VISIBLE) {
            screenListener?.invoke(Screen.GUI)
            homeButtonListener?.invoke(true)
        } else {
            homeButtonListener?.invoke(false)
        }
    }
}

class ObservableGuiLoadingOverlay(
    context: Context,
    onVisibilityChanged: (Int) -> Unit
) : View(context) {
    private var visibilityListener: ((Int) -> Unit)? = null
    init {
        visibilityListener = onVisibilityChanged
    }
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        visibilityListener?.invoke(visibility)
    }
}

class ObservableGuiKeyBarCardView(
    context: Context,
    onVisibilityChanged: (Int) -> Unit
) : com.google.android.material.card.MaterialCardView(context) {
    private var visibilityListener: ((Int) -> Unit)? = null
    init {
        visibilityListener = onVisibilityChanged
    }
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        visibilityListener?.invoke(visibility)
    }
}

class ObservableLoadingTextView(
    context: Context,
    onTextChanged: (String) -> Unit
) : androidx.appcompat.widget.AppCompatTextView(context) {
    private var textListener: ((String) -> Unit)? = null
    init {
        textListener = onTextChanged
    }
    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        textListener?.invoke(text?.toString() ?: "")
    }
}
