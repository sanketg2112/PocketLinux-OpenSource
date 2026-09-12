package com.sg.linuxgo

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.preference.PreferenceManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.sg.linuxgo.x11.LorieView
import java.io.File

/** Logging */

internal fun MainActivity.logToMini(message: String) {
    val line = SetupLogSupport.formatLogLine(message)
    Log.d("MiniLog", line)
    runOnUiThread {
        appendLogLineToMini(line)
    }
}

internal fun MainActivity.appendLogLineToMini(line: String) {
    if (!isLateInit_tvMiniLog()) return
    SetupLogSupport.appendLogLineToMini(
        line = line,
        tvMiniLog = tvMiniLog,
        miniLogScroll = if (isLateInit_miniLogScroll()) miniLogScroll else null,
        lastMiniLogInteractionTime = lastMiniLogInteractionTime,
        setProgrammaticScroll = { isProgrammaticScroll = it },
        onTextChanged = { setupLogText = it }
    )
}
