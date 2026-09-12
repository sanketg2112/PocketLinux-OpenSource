package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallCompleteTargetTest {

    @Test
    fun prefersExplicitCompletedIdOverActiveAndInstalling() {
        val id = InstallCompleteTarget.resolve(
            completedContainerId = "new-install",
            installingHint = "installing-other",
            activeContainerId = "first-card",
        )
        assertEquals("new-install", id)
    }

    @Test
    fun restoreIdWinsWhenOnlyRestoreSnapshotPresent() {
        val id = InstallCompleteTarget.resolve(
            completedContainerId = null,
            activeContainerId = "first-card",
            lastCompleteRestoreId = "restored-env",
        )
        assertEquals("restored-env", id)
    }

    @Test
    fun installingHintBeatsStaleActiveContainer() {
        val id = InstallCompleteTarget.resolve(
            completedContainerId = null,
            installingHint = "second-installing",
            activeContainerId = "first-card",
        )
        assertEquals("second-installing", id)
    }

    @Test
    fun doesNotInventFirstContainerWhenAllHintsMissing() {
        assertNull(
            InstallCompleteTarget.resolve(
                completedContainerId = null,
                installingHint = null,
                activeInstallingId = null,
                lastCompleteInstallId = null,
                lastCompleteRestoreId = null,
                activeContainerId = null,
            )
        )
    }

    @Test
    fun fallsBackToActiveOnlyAsLastResort() {
        val id = InstallCompleteTarget.resolve(
            completedContainerId = null,
            activeContainerId = "only-active",
        )
        assertEquals("only-active", id)
    }

    @Test
    fun consumeInstallCompleteIsOneShot() {
        val vm = MainViewModel(android.app.Application())
        vm.installCompleteContainerId = "fresh-debian"

        assertEquals("fresh-debian", vm.consumeInstallCompleteContainerId())
        assertNull(vm.installCompleteContainerId)
        // Second claim (e.g. Launch desktop after Tips) must no-op.
        assertNull(vm.consumeInstallCompleteContainerId())
    }
}
