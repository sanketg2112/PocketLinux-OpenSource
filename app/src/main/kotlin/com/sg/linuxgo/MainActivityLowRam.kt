package com.sg.linuxgo

/**
 * Low free-RAM edge-pill warnings while GUI is active.
 * Warning at ≤500 MB free, critical at ≤250 MB — never blocks or limits the user.
 */

internal fun MainActivity.clearLowRamPillState() {
    viewModel.lowRamPhase = LowRamPillPhase.None
    viewModel.lowRamAvailMb = 0
    viewModel.lowRamWarningDismissed = false
    viewModel.lowRamLastShakePhase = LowRamPillPhase.None
}

/**
 * Drive edge-pill low-RAM state from device [availRamMb].
 * Call on the main thread from the usage-check loop while GUI is visible.
 *
 * @return true when under the warning threshold (caller may poll faster).
 */
internal fun MainActivity.applyLowRamPillState(availRamMb: Int): Boolean {
    val phase = lowRamPhaseFor(availRamMb)
    val previous = viewModel.lowRamPhase
    viewModel.lowRamPhase = phase
    viewModel.lowRamAvailMb = availRamMb.coerceAtLeast(0)

    if (phase == LowRamPillPhase.None) {
        if (previous != LowRamPillPhase.None) {
            viewModel.lowRamWarningDismissed = false
            viewModel.lowRamLastShakePhase = LowRamPillPhase.None
            pillPopupController.updatePillPopupSize(viewModel.menuExpanded)
        }
        return false
    }

    // Entering Warning or Critical: shake once, re-show dismissible chip, force pill visible.
    if (phase != viewModel.lowRamLastShakePhase) {
        viewModel.lowRamLastShakePhase = phase
        viewModel.sessionShakeNonce = viewModel.sessionShakeNonce + 1
        viewModel.isPillHidden = false
        // New severity → show chip again (still dismissible).
        viewModel.lowRamWarningDismissed = false
        // Collapse menu so the alert chip is visible (same as session-limit path).
        if (viewModel.menuExpanded) {
            viewModel.menuExpanded = false
        }
        pillPopupController.updatePillPopupSize(false)
    } else if (lowRamShowsChip(phase, viewModel.lowRamWarningDismissed)) {
        if (viewModel.isPillHidden) {
            viewModel.isPillHidden = false
            pillPopupController.updatePillPopupSize(viewModel.menuExpanded)
        }
    } else if (viewModel.menuExpanded) {
        // Keep expanded menu width in sync while free MB ticks.
        pillPopupController.updatePillPopupSize(true)
    }

    return true
}

internal fun MainActivity.dismissLowRamWarning() {
    if (viewModel.lowRamPhase == LowRamPillPhase.None) return
    viewModel.lowRamWarningDismissed = true
    pillPopupController.updatePillPopupSize(viewModel.menuExpanded)
    pillPopupController.resetPillAutoHideTimer()
}
