package com.sg.linuxgo

import com.sg.linuxgo.x11.LorieView

interface GuiViewDelegate {
    fun setGuiLayoutVisible(visible: Boolean)
    fun showLoadingStatus(status: String)
    fun hideLoadingOverlay()
    fun isGuiVisible(): Boolean
    fun getLorieView(): LorieView
    fun onSessionStarted()
    fun onSessionTerminated()
    /** Ensure host PulseAudio (AAudio TCP :14713) is running for the X11 guest. */
    fun startX11AudioBridge()    fun acquireWakeLock()
    fun releaseWakeLock()
    fun startStatsPoller()
    fun stopStatsPoller()
}
