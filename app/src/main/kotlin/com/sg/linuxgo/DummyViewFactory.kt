package com.sg.linuxgo

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.sg.linuxgo.ui.screens.ChecklistItemState
import com.sg.linuxgo.x11.LorieView

/**
 * Builds the in-memory View registry used by Compose + legacy bridges.
 * Keeps [MainActivity] free of the large view-construction block.
 */
object DummyViewFactory {

    fun create(
        context: Context,
        onScreenChange: (Screen) -> Unit,
        onGuiLoadingVisible: (Boolean) -> Unit,
        onGuiHomeButtonVisible: (Boolean) -> Unit,
        onGuiKeyBarVisible: (Boolean) -> Unit,
        onGuiLoadingStatus: (String) -> Unit,
        onSetupChecklistItems: (List<ChecklistItemState>) -> Unit,
        getSetupChecklistItems: () -> List<ChecklistItemState>,
        onSetupLogToggleText: (String) -> Unit,
        onSetupRestoreVisible: (Boolean) -> Unit,
        onSetupMetricsVisible: (Boolean) -> Unit,
        onSetupLogCardVisible: (Boolean) -> Unit,
        onSetupInstallPath: (String) -> Unit,
        onSetupWelcomeVisible: (Boolean) -> Unit,
        onSetupStepDescText: (String) -> Unit,
        onSetupStepDescVisible: (Boolean) -> Unit,
        onSetupProgressStatusText: (String) -> Unit,
        onSetupProgressStatusVisible: (Boolean) -> Unit,
        onSetupLogText: (String) -> Unit,
        onSetupProgressIndeterminate: (Boolean) -> Unit,
        onSetupProgress: (Float) -> Unit,
        onSetupProgressVisible: (Boolean) -> Unit
    ): HashMap<Int, View> {
        val viewRegistry = HashMap<Int, View>()

        val terminalScrollLayout = FrameLayout(context)
        val terminalBg = 0xFF121216.toInt()
        terminalScrollLayout.setBackgroundColor(terminalBg)
        val tvTerminalOutput = TextView(context).apply {
            id = R.id.tvTerminalOutput
            try {
                typeface = android.graphics.Typeface.createFromAsset(context.assets, "fonts/MesloLGS-NF-Regular.ttf")
            } catch (_: Exception) {
                typeface = android.graphics.Typeface.MONOSPACE
            }
            textSize = 12f
            setTextColor(0xFFABB2BF.toInt())
            setBackgroundColor(terminalBg)
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            letterSpacing = 0f
            // Selectable for copy, but never take IME focus away from etTerminalInput.
            setTextIsSelectable(true)
            isFocusable = false
            isFocusableInTouchMode = false
            setLineSpacing(0f, 1f)
        }
        // ScrollView itself must not reclaim focus on scroll-to-bottom.
        // (fullScroll used to; we also block descendant focus reclaim here.)
        val terminalCursor = View(context).apply {
            id = R.id.terminalCursor
            setBackgroundColor(0xFF00E5FF.toInt())
        }
        terminalScrollLayout.addView(tvTerminalOutput)
        terminalScrollLayout.addView(terminalCursor)

        val terminalScroll = ScrollView(context).apply {
            id = R.id.terminalScroll
            setBackgroundColor(terminalBg)
            isFillViewport = true
            isFocusable = false
            isFocusableInTouchMode = false
            // Prefer keeping IME host focused over output TextView.
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(
                terminalScrollLayout,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // Full-width 1px-tall IME host (InputConnection-based; reliable Enter/Backspace).
        val etTerminalInput = TerminalImeEditText(context).apply {
            id = R.id.etTerminalInput
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            )
        }

        viewRegistry[R.id.terminalScroll] = terminalScroll
        viewRegistry[R.id.tvTerminalOutput] = tvTerminalOutput
        viewRegistry[R.id.terminalCursor] = terminalCursor
        viewRegistry[R.id.etTerminalInput] = etTerminalInput

        viewRegistry[R.id.setupView] = ObservableSetupScrollView(context, onScreenChange).apply { id = R.id.setupView }
        viewRegistry[R.id.terminalView] = ObservableTerminalLinearLayout(context, onScreenChange).apply { id = R.id.terminalView }
        viewRegistry[R.id.guiLoadingOverlay] = ObservableGuiLoadingOverlay(context) {
            onGuiLoadingVisible(it == View.VISIBLE)
        }.apply { id = R.id.guiLoadingOverlay }
        viewRegistry[R.id.guiContainer] = ObservableGuiFrameLayout(
            context,
            onScreenChange
        ) { onGuiHomeButtonVisible(it) }.apply { id = R.id.guiContainer }
        viewRegistry[R.id.homeContainer] = ObservableHomeScrollView(context, onScreenChange).apply { id = R.id.homeContainer }
        viewRegistry[R.id.checklistContainer] = LinearLayout(context).apply { id = R.id.checklistContainer }

        val updateStepStateLambda = { stepIndex: Int, text: String, colorVal: Int ->
            val currentList = getSetupChecklistItems().toMutableList()
            if (stepIndex in currentList.indices) {
                currentList[stepIndex] = ChecklistItemState(
                    text = text,
                    color = androidx.compose.ui.graphics.Color(colorVal)
                )
                onSetupChecklistItems(currentList)
            }
        }
        viewRegistry[R.id.step1] = ObservableStepTextView(context, 0, updateStepStateLambda).apply { id = R.id.step1 }
        viewRegistry[R.id.step2] = ObservableStepTextView(context, 1, updateStepStateLambda).apply { id = R.id.step2 }
        viewRegistry[R.id.step3] = ObservableStepTextView(context, 2, updateStepStateLambda).apply { id = R.id.step3 }
        viewRegistry[R.id.step4] = ObservableStepTextView(context, 3, updateStepStateLambda).apply { id = R.id.step4 }
        viewRegistry[R.id.step5] = ObservableStepTextView(context, 4, updateStepStateLambda).apply { id = R.id.step5 }
        viewRegistry[R.id.step6] = ObservableStepTextView(context, 5, updateStepStateLambda).apply { id = R.id.step6 }

        viewRegistry[R.id.btnToggleLogs] = ObservableToggleLogsButton(context, onSetupLogToggleText).apply {
            id = R.id.btnToggleLogs
        }
        viewRegistry[R.id.btnRestoreSetup] = ObservableMaterialButton(context) {
            onSetupRestoreVisible(it == View.VISIBLE)
        }.apply { id = R.id.btnRestoreSetup }
        viewRegistry[R.id.btnPrimaryAction] = MaterialButton(context).apply { id = R.id.btnPrimaryAction }
        viewRegistry[R.id.btnBackup] = MaterialButton(context).apply { id = R.id.btnBackup }
        viewRegistry[R.id.btnRestore] = MaterialButton(context).apply { id = R.id.btnRestore }
        viewRegistry[R.id.metricsCard] = ObservableMetricsCardView(context) {
            onSetupMetricsVisible(it == View.VISIBLE)
        }.apply { id = R.id.metricsCard }
        viewRegistry[R.id.logCardView] = ObservableLogCardView(context) {
            onSetupLogCardVisible(it == View.VISIBLE)
        }.apply { id = R.id.logCardView }
        viewRegistry[R.id.tvInstallPath] = ObservableInstallPathTextView(context, onSetupInstallPath).apply {
            id = R.id.tvInstallPath
        }
        viewRegistry[R.id.welcomeContainer] = FrameLayout(context).apply {
            id = R.id.welcomeContainer
            visibility = View.VISIBLE
            onSetupWelcomeVisible(true)
        }

        viewRegistry[R.id.footerAction] = FrameLayout(context).apply { id = R.id.footerAction }

        val lorieView = LorieView(context).apply { id = R.id.lorieView }
        viewRegistry[R.id.lorieView] = lorieView

        viewRegistry[R.id.btnGuiHome] = androidx.appcompat.widget.AppCompatImageButton(context).apply {
            id = R.id.btnGuiHome
        }
        viewRegistry[R.id.rvContainers] = RecyclerView(context).apply { id = R.id.rvContainers }
        viewRegistry[R.id.btnAddContainer] = View(context).apply { id = R.id.btnAddContainer }

        viewRegistry[R.id.tvStepDescription] = ObservableTextView(
            context,
            onSetupStepDescText
        ) { onSetupStepDescVisible(it == View.VISIBLE) }.apply { id = R.id.tvStepDescription }
        viewRegistry[R.id.tvProgressStatus] = ObservableTextView(
            context,
            onSetupProgressStatusText
        ) { onSetupProgressStatusVisible(it == View.VISIBLE) }.apply { id = R.id.tvProgressStatus }
        viewRegistry[R.id.tvMiniLog] = ObservableLogTextView(context) {
            onSetupLogText(it.toString())
        }.apply { id = R.id.tvMiniLog }
        viewRegistry[R.id.miniLogScroll] = ScrollView(context).apply { id = R.id.miniLogScroll }
        viewRegistry[R.id.progressBar] = ObservableProgressBar(
            context,
            onSetupProgressIndeterminate,
            { progress, max -> onSetupProgress(if (max > 0) progress.toFloat() / max else 0f) }
        ) { onSetupProgressVisible(it == View.VISIBLE) }.apply { id = R.id.progressBar }
        viewRegistry[R.id.tabLayout] = TabLayout(context).apply { id = R.id.tabLayout }
        viewRegistry[R.id.specialKeysScroll] = View(context).apply { id = R.id.specialKeysScroll }
        viewRegistry[R.id.btnCopyLog] = MaterialButton(context).apply { id = R.id.btnCopyLog }
        viewRegistry[R.id.btnDownloadLog] = MaterialButton(context).apply { id = R.id.btnDownloadLog }
        viewRegistry[R.id.btnFullscreenLog] = MaterialButton(context).apply { id = R.id.btnFullscreenLog }
        viewRegistry[R.id.promptContainer] = LinearLayout(context).apply { id = R.id.promptContainer }

        viewRegistry[R.id.tabLayoutTerminal] = TabLayout(context).apply { id = R.id.tabLayoutTerminal }
        viewRegistry[R.id.btnNewSession] = MaterialButton(context).apply { id = R.id.btnNewSession }
        viewRegistry[R.id.btnCloseSession] = MaterialButton(context).apply { id = R.id.btnCloseSession }
        viewRegistry[R.id.fabKeyboard] = FloatingActionButton(context).apply { id = R.id.fabKeyboard }
        viewRegistry[R.id.guiKeyBar] = ObservableGuiKeyBarCardView(context) {
            onGuiKeyBarVisible(it == View.VISIBLE)
        }.apply { id = R.id.guiKeyBar }

        viewRegistry[R.id.tvGuiLoadingStatus] = ObservableLoadingTextView(context, onGuiLoadingStatus).apply {
            id = R.id.tvGuiLoadingStatus
        }

        viewRegistry[R.id.mainRoot] = FrameLayout(context).apply { id = R.id.mainRoot }
        // Do NOT register android.R.id.content — Activity content must come from the
        // real window hierarchy (insets, IME, Compose root). Shadowing it with a
        // detached FrameLayout broke layout helpers and hid real children.

        return viewRegistry
    }
}
